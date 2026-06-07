package com.kfilesync.mobile.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kfilesync.mobile.domain.model.ConflictResolution
import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.application.service.SyncAppService
import com.kfilesync.mobile.application.service.SyncStatus
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncConflict
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the Sync tab (Phase 4 T4.9).
 *
 * Pulls three hot streams and folds them into [SyncUiState]:
 * - `syncService.observeSyncStatus()` — "running / idle" + lastSyncedAt
 * - `syncService.observeConflicts()` — list of pending conflicts
 * - `shareService.observeShares()` — Active shares to render rows for
 *
 * The screen shows two sections:
 * 1. Per-share rows: name, status, "Sync now" button. The button calls
 * [syncShare] which loops over all paired peer members internally
 * (so the UI doesn't need to know about peer selection).
 * 2. Conflicts list: each row has three buttons (Keep mine / Keep theirs /
 * Keep both) that map to the corresponding [ConflictResolution].
 *
 * The "running" indicator is global — we only show one sync in progress
 * at a time even though the underlying service supports per-(share, peer)
 * mutual exclusion. Refining the indicator to per-share would mean another
 * StateFlow; not worth the complexity for Phase 4.
 */
class SyncViewModel(
    private val syncService: SyncAppService,
    private val shareService: ShareAppService
) : ViewModel() {

    private val _busyShareId = MutableStateFlow<ShareId?>(null)

    private val _uiState = MutableStateFlow(SyncUiState.EMPTY)
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        combine(
            syncService.observeSyncStatus(),
            syncService.observeConflicts(),
            shareService.observeShares(),
            _busyShareId
        ) { status, conflicts, shares, busy ->
            buildState(status, conflicts, shares, busy)
        }.onEach { _uiState.value = it }.launchIn(viewModelScope)
    }

    /** "Sync now" button on a share row. */
    fun syncShare(shareId: ShareId) {
        viewModelScope.launch {
            _busyShareId.value = shareId
            try {
                syncService.syncShare(shareId)
                    .onFailure { Napier.w("syncShare failed: ${it.message}") }
            } finally {
                // Even on failure, clear busy so the user can retry.
                _busyShareId.value = null
            }
        }
    }

    fun resolveKeepLocal(shareId: ShareId, path: String) =
        applyResolution(shareId, path, ConflictResolution.KeepLocal)

    fun resolveKeepRemote(shareId: ShareId, path: String) =
        applyResolution(shareId, path, ConflictResolution.KeepRemote)

    fun resolveKeepBoth(shareId: ShareId, path: String) =
        applyResolution(shareId, path, ConflictResolution.KeepBoth)

    private fun applyResolution(shareId: ShareId, path: String, resolution: ConflictResolution) {
        viewModelScope.launch {
            syncService.resolveConflict(shareId, path, resolution)
                .onFailure { Napier.w("resolveConflict($path) failed: ${it.message}") }
        }
    }

    // ---- state assembly ----

    private fun buildState(
        status: SyncStatus,
        conflicts: List<SyncConflict>,
        shares: List<ShareRow>,
        busy: ShareId?
    ): SyncUiState {
        // Only Active shares can be synced. Paused / Pending / Left don't
        // appear here — the Shares tab surfaces them.
        val syncableShares = shares.filter { it.status == ShareStatus.Active }
        return SyncUiState(
            running = status.running,
            lastError = status.lastError,
            lastSyncedAt = status.lastSyncedAt?.toEpochMilliseconds(),
            shares = syncableShares,
            conflicts = conflicts,
            busyShareId = busy
        )
    }
}

// ---------- UI state ----------

/**
 * Snapshot fed to [SyncScreen].
 *
 * Marked [Immutable] (Phase 6 T6.2) so Compose stability inference skips
 * the per-frame equality checks. The nested lists are replaced as wholes;
 * we never mutate the inner items.
 */
@androidx.compose.runtime.Immutable
data class SyncUiState(
    val running: Boolean,
    val lastError: String?,
    val lastSyncedAt: Long?, // epoch ms; UI formats with kotlinx-datetime-free formatter
    val shares: List<ShareRow>,
    val conflicts: List<SyncConflict>,
    val busyShareId: ShareId?
) {
    companion object {
        val EMPTY = SyncUiState(
            running = false,
            lastError = null,
            lastSyncedAt = null,
            shares = emptyList(),
            conflicts = emptyList(),
            busyShareId = null
        )
    }
}