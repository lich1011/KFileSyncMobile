package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.port.DeviceRepository
import com.kfilesync.mobile.infrastructure.network.LanSyncHttpClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Heartbeat / presence service (T1.9).
 *
 * Every [pingInterval] (default 30 s) we iterate the set of paired devices,
 * fire a 'GET /info' at each known address, and:
 * - on success: update 'lastSeenAt' in the device repo, mark the device
 * 'Online' in the in-memory presence map exposed via [presence].
 * - on failure: leave 'lastSeenAt' alone. If we haven't successfully
 * reached the device for [offlineThreshold] (default 90 s), drop it to
 * 'Offline'.
 *
 * Why a separate in-memory map instead of stamping the DB? 'Online/Offline'
 * is best-effort, ephemeral state - it doesn't belong in persistent storage.
 * The DB owns the durable 'lastSeenAt' timestamp; this service derives the
 * online flag from it at query time and keeps it in a hot StateFlow so the
 * UI can `collectAsState()` without dipping into SQLite on every tick.
 *
 * Lifecycle: `start()` is idempotent (re-calling does nothing). `stop()`
 * cancels the ticker and clears the presence snapshot.
 */
class HeartbeatService(
    private val deviceRepository: DeviceRepository,
    private val httpClient: LanSyncHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Instant = { Clock.System.now() },
    private val pingInterval: Duration = 30.seconds,
    private val offlineThreshold: Duration = 90.seconds
) {
    private var loopJob: Job? = null

    private val _presence = MutableStateFlow<Map<DeviceId, OnlineState>>(emptyMap())

    /** Read-only presence stream - collect from the ViewModel layer. */
    val presence: StateFlow<Map<DeviceId, OnlineState>> = _presence.asStateFlow()

    fun start() {
        if (loopJob?.isActive == true) {
            Napier.d("HeartbeatService.start() called while already running, ignoring")
            return
        }

        loopJob = scope.launch {
            Napier.i("HeartbeatService loop start (every ${pingInterval})")
            while (isActive) {
                runCatching { tick() }.onFailure { Napier.w("heartbeat tick failed", it) }
                delay(pingInterval)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _presence.value = emptyMap()
    }

    /** Internal - exposed for tests. */
    suspend fun tick() {
        val now = clock()
        val paired = deviceRepository.findPaired()
        val next = _presence.value.toMutableMap()
        for (device in paired) {
            val reached = pingAny(device)
            if (reached) {
                deviceRepository.updateLastSeen(
                    id = device.id,
                    lastSeenAt = now,
                    addresses = null // don't churn the address list on success
                )
                next[device.id] = OnlineState.Online(at = now)
            } else {
                val priorState = next[device.id]
                val lastOnline = (priorState as? OnlineState.Online)?.at
                val staleFor = lastOnline?.let { now - it } ?: offlineThreshold
                next[device.id] = if (staleFor >= offlineThreshold) OnlineState.Offline
                else priorState ?: OnlineState.Offline
            }
        }
        _presence.value = next
    }

    private suspend fun pingAny(device: Device): Boolean {
        if (device.addresses.isEmpty()) return false
        for (addr in device.addresses) {
            val ok = httpClient.fetchInfo("http://${addr.host}:${addr.port}") != null
            if (ok) return true
        }
        return false
    }
}

/** Ephemeral presence flag for a paired device. */
sealed class OnlineState {
    data class Online(val at: Instant) : OnlineState()
    data object Offline : OnlineState()
}