package com.kfilesync.mobile.application.service

import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.domain.port.NetworkMonitor
import com.kfilesync.mobile.domain.port.TransferRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Network-change-awareness orchestrator (T5.4).
 *
 * Listens to [NetworkMonitor.observe], and:
 *
 * - When Wi-Fi drops (isWifi flips false): auto-pause every active
 * outgoing TransferJob by flipping its state to Paired. We do NOT
 * cancel - the user expects an interrupted upload to *resume* once
 * they reconnect.
 * - When Wi-Fi returns: surface the previously-auto-paused jobs by
 * setting them back to Active. The Phase 2 chunk loop will then
 * re-establish the HTTPS connection on its next iteration.
 *
 * We track *which* jobs were auto-paused (vs user-paused) using an
 * in-memory set keyed by JobId. User-paused jobs are not auto-resumed.
 *
 * Receive-side resumption is automatic at a different layer: the sender
 * peer's own NetworkAwarenessService will resume its side, and the
 * `/transfer/request` re-POST will carry `skipChunks` so we re-attach to
 * the same temp file.
 *
 * Lifecycle: [start] is called from app bootstrap on Android + iOS; the
 * service runs for the process lifetime. [stop] is mostly a test helper -
 * production never calls it because the OS will tear down the process
 * before we'd want to.
 */
class NetworkAwarenessService(
    private val networkMonitor: NetworkMonitor,
    private val transferRepository: TransferRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    /** JobIds we paused due to a network drop. Resumed automatically on reconnect. */
    private val autoPaused = mutableSetOf<String>()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            // Track previous value so we only act on transitions, not every
            // re-emission. NetworkMonitor implementations should distinctUntilChanged
            // themselves, but we belt-and-brace here to keep the orchestrator
            // resilient against chatty platform sources.
            networkMonitor.observe()
                .map { it.isWifi }
                .distinctUntilChanged()
                .collect { isWifi ->
                    if (isWifi) onWifiRestored() else onWifiLost()
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun onWifiLost() {
        val active = transferRepository.findIncompleteJobs()
            .filter { it.state is TransferState.Active }
        for (j in active) {
            val paused = j.pause(now()).getOrNull() ?: continue
            runCatching { transferRepository.saveJob(paused) }.getOrNull() ?: continue
            autoPaused += j.id.value
        }
        if (active.isNotEmpty()) {
            Napier.i("NetworkAwareness: Wi-Fi lost, auto-paused ${autoPaused.size} transfer(s)")
        }
    }

    private suspend fun onWifiRestored() {
        if (autoPaused.isEmpty()) return
        val ids = autoPaused.toSet()
        autoPaused.clear()

        val toResume = transferRepository.findIncompleteJobs()
            .filter { j -> j.id.value in ids && j.state is TransferState.Paused }
        for (j in toResume) {
            val resumed = j.start(now()).getOrNull() ?: continue
            runCatching { transferRepository.saveJob(resumed) }
                .onFailure { Napier.w("NetworkAwareness: resume failed for ${j.id.value}: ${it.message}") }
        }
        Napier.i("NetworkAwareness: Wi-Fi restored, resumed ${toResume.size} transfer(s)")
    }

    private fun now() = kotlin.time.Clock.System.now()
}