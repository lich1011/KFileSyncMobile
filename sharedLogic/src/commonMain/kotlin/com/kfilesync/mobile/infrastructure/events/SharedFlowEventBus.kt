package com.kfilesync.mobile.infrastructure.events

import com.kfilesync.mobile.domain.event.DomainEvent
import com.kfilesync.mobile.domain.port.EventBus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/**
 * Reference implementation of [EventBus] built on `MutableSharedFlow`
 * (design doc §6.5.3).
 *
 * Characteristics:
 * - Hot stream – events emitted before any subscriber is attached are
 * buffered up to [BUFFER_CAPACITY] then dropped oldest. This matches
 * the desktop behaviour and is fine for fire-and-forget domain events.
 * - [publish] is non-suspending (`tryEmit`); domain code never blocks on
 * bus pressure.
 * - [subscribe] dispatches handlers on [Dispatchers.Default] so a slow
 * handler can't block the producer.
 * - The bus owns an internal supervisor scope so a crashing handler
 * doesn't tear down peer handlers.
 */
class SharedFlowEventBus(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : EventBus {

    private val _events = MutableSharedFlow<DomainEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun publish(event: DomainEvent) {
        val accepted = _events.tryEmit(event)
        if (!accepted) {
            // tryEmit on a SharedFlow with DROP_OLDEST never returns false in
            // practice, but log defensively so we notice if Kotlin's contract
            // ever changes.
            Napier.w("EventBus dropped event ${event.eventType}#${event.aggregateId}")
        }
    }

    override fun <T : DomainEvent> subscribe(
        eventType: KClass<T>,
        handler: (T) -> Unit
    ) {
        scope.launch {
            _events
                .filter { eventType.isInstance(it) }
                .collect { event ->
                    @Suppress("UNCHECKED_CAST")
                    runCatching { handler(event as T) }
                        .onFailure { Napier.e("handler for ${eventType.simpleName} threw", it) }
                }
        }
    }

    /**
     * Cold view of the bus for callers that want to compose with the rest of
     * the coroutine ecosystem (ViewModels, flows-of-flows, etc).
     */
    override fun events(): Flow<DomainEvent> = _events.asSharedFlow()

    companion object {
        /** Buffer size matches the desktop's tokio broadcast channel capacity. */
        const val BUFFER_CAPACITY: Int = 64
    }
}