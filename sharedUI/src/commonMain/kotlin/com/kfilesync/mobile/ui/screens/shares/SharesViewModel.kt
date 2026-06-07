package com.kfilesync.mobile.ui.screens.shares

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kfilesync.mobile.application.service.ShareAppService
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.domain.model.ShareId
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.port.DirectoryPicker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the Shares tab (T3.6).
 *
 * Pulls the single hot stream from [ShareAppService.observeShares] and
 * partitions into three UI sections (Pending invitations, Active+Paused, Left).
 * Local-path picking happens via [AcceptInvitationState] which the screen
 * threads through a SAF / UIDocumentPicker dialog on the platform side.
 */
class SharesViewModel(
    private val shareService: ShareAppService,
    private val directoryPicker: DirectoryPicker
) : ViewModel() {

    private val _acceptUi = MutableStateFlow<AcceptInvitationState>(value = AcceptInvitationState.Hidden)
    val acceptUi: StateFlow<AcceptInvitationState> = _acceptUi.asStateFlow()

    private val _uiState = MutableStateFlow(value = SharesUiState.EMPTY)
    val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

    init {
        combine(
            flow = shareService.observeShares(),
            flow2 = _acceptUi
        ) { rows, acceptUi ->
            buildState(rows, acceptUi)
        }.onEach { _uiState.value = it }.launchIn(viewModelScope)
    }

    fun beginAccept(row: ShareRow) {
        _acceptUi.value = AcceptInvitationState.PickingPath(row)
        viewModelScope.launch {
            val picked = try {
                directoryPicker.pickDirectory()
            } catch (t: Throwable) {
                Napier.w(message = "directoryPicker.pickDirectory failed", throwable = t)
                null
            }

            if (picked.isNullOrBlank()) {
                _acceptUi.value = AcceptInvitationState.Hidden
                return@launch
            }

            val outcome = shareService.acceptInvitation(row.shareId, localPath = picked)
            _acceptUi.value = if (outcome.isSuccess) {
                AcceptInvitationState.Hidden
            } else {
                AcceptInvitationState.Failed(outcome.exceptionOrNull()?.message ?: "Accept failed")
            }
        }
    }

    /** Direct path-pass for tests / future deep-link flows. */
    fun submitAcceptPath(shareId: ShareId, localPath: String) {
        viewModelScope.launch {
            val outcome = shareService.acceptInvitation(shareId, localPath)
            _acceptUi.value = if (outcome.isSuccess) {
                AcceptInvitationState.Hidden
            } else {
                AcceptInvitationState.Failed(outcome.exceptionOrNull()?.message ?: "Accept failed")
            }
        }
    }

    fun dismissAccept() {
        _acceptUi.value = AcceptInvitationState.Hidden
    }

    fun decline(shareId: ShareId) {
        viewModelScope.launch {
            shareService.declineInvitation(shareId)
                .onFailure { Napier.w(message = "decline failed", throwable = it) }
        }
    }

    fun pause(shareId: ShareId) {
        viewModelScope.launch { shareService.pauseShare(shareId) }
    }

    fun resume(shareId: ShareId) {
        viewModelScope.launch { shareService.resumeShare(shareId) }
    }

    fun leave(shareId: ShareId) {
        viewModelScope.launch { shareService.leaveShare(shareId) }
    }

    // ---- state assembly ----

    private fun buildState(rows: List<ShareRow>, acceptUi: AcceptInvitationState): SharesUiState {
        val pending = rows.filter { it.status == ShareStatus.Pending }
        val active = rows.filter { it.status == ShareStatus.Active || it.status == ShareStatus.Paused }
        val left = rows.filter { it.status == ShareStatus.Left }
        return SharesUiState(
            pending = pending,
            active = active,
            history = left,
            acceptUi = acceptUi
        )
    }
}

// ---------- UI state types ----------

/**
 * Snapshot fed to [SharesScreen]. Marked [Immutable] (Phase 6 & T6.2) so
 * Compose treats it as stable; the nested lists are replaced as wholes.
 */
@androidx.compose.runtime.Immutable
data class SharesUiState(
    val pending: List<ShareRow>,
    val active: List<ShareRow>,
    val history: List<ShareRow>,
    val acceptUi: AcceptInvitationState
) {
    companion object {
        val EMPTY = SharesUiState(
            pending = emptyList(),
            active = emptyList(),
            history = emptyList(),
            acceptUi = AcceptInvitationState.Hidden
        )
    }
}

/** Accept-invitation dialog state (T3.6). */
sealed class AcceptInvitationState {
    /** Dialog closed. */
    data object Hidden : AcceptInvitationState()

    /** OS-level directory picker is being shown for [row]. */
    data class PickingPath(val row: ShareRow) : AcceptInvitationState()

    /** Terminal error - show, then user dismisses. */
    data class Failed(val message: String) : AcceptInvitationState()
}