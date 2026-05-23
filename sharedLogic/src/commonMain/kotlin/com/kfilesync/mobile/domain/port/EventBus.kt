package com.kfilesync.mobile.domain.port

import com.kfilesync.mobile.domain.event.DomainEvent
import kotlin.reflect.KClass

/**
 * Driven port for domain-event distribution. The shared infrastructure
 * implementation is 'SharedFlowEventBus' (commonMain).
 *
 * Phase 0: contract only. Phase 1 (T1.5) starts publishing real events.
 */
interface EventBus {
    fun publish(event: DomainEvent)
    fun <T : DomainEvent> subscribe(eventType: KClass<T>, handler: (T) -> Unit)
}