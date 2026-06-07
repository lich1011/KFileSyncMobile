package com.kfilesync.mobile.ui.screens.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kfilesync.mobile.application.service.DeviceAppService
import com.kfilesync.mobile.application.service.IncomingTransferRequest
import com.kfilesync.mobile.domain.port.PlatformFile
import com.kfilesync.mobile.application.service.TransferAppService
import com.kfilesync.mobile.application.service.TransferRow
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.domain.model.DeviceId
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.JobId
import com.kfilesync.mobile.domain.port.FilePicker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for the Transfers tab (T2.6).
 *
 * Combines:
 * - [TransferAppService.observeTransfers]       - active + historical rows
 * - [TransferAppService.observeIncomingRequests] - pending accept dialogs
 * - [DeviceAppService.observeDevices]           - paired-only list for the
 * Send picker
 *
 * The UI groups transfers into "Active" (Pending/Active/Paused/Verifying)
 * and "Completed" (Completed/Failed/Cancelled) - matches the mock in
 * `mobile-design/11-ui-design.md`.
 */
class TransfersViewModel(
    private val transferService: TransferAppService,
    private val deviceService: DeviceAppService,
    private val filePicker: FilePicker
) : ViewModel() {

    private val _sendDialog = MutableStateFlow<SendDialogState>(SendDialogState.Hidden)
    val sendDialog: StateFlow<SendDialogState> = _sendDialog.asStateFlow()

    private val _uiState = MutableStateFlow<TransfersUiState>(TransfersUiState.EMPTY)
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    init {
        combine(
            transferService.observeTransfers(),
            transferService.observeIncomingRequests(),
            deviceService.observeDevices(),
            _sendDialog
        ) { transfers, incoming, devices, dialog ->
            buildState(transfers, incoming, devices, dialog)
        }.onEach { _uiState.value = it }.launchIn(viewModelScope)
    }

    // ---- Send flow ----

    /**
     * Opens the "send to whom?" sheet. The user picks a paired device, then
     * we launch the OS file picker and call [TransferAppService.sendFiles].
     */
    fun openSendDialog() {
        _sendDialog.value = SendDialogState.PickTarget
    }

    fun selectTarget(target: DeviceId) {
        _sendDialog.value = SendDialogState.PickingFiles(target)
        viewModelScope.launch {
            val files: List<PlatformFile> = try {
                filePicker.pickFiles(allowMultiple = true)
            } catch (t: Throwable) {
                Napier.w("filePicker.pickFiles failed", t)
                emptyList()
            }

            if (files.isEmpty()) {
                _sendDialog.value = SendDialogState.Hidden
                return@launch
            }

            try {
                transferService.sendFiles(target, files)
                _sendDialog.value = SendDialogState.Hidden
            } catch (t: Throwable) {
                Napier.w("sendFiles failed", t)
                _sendDialog.value = SendDialogState.Failed(t.message ?: "Send failed")
            }
        }
    }

    fun dismissSendDialog() {
        _sendDialog.value = SendDialogState.Hidden
    }

    // ---- Accept / reject incoming ----
    fun acceptIncoming(sessionId: String) {
        viewModelScope.launch {
            transferService.acceptTransfer(sessionId, targetDirectory = null)
        }
    }

    fun rejectIncoming(sessionId: String) {
        viewModelScope.launch {
            transferService.rejectTransfer(sessionId)
        }
    }

    // ---- Per-job controls ----
    fun pause(jobId: JobId) {
        viewModelScope.launch { transferService.pauseTransfer(jobId) }
    }

    fun resume(jobId: JobId) {
        viewModelScope.launch { transferService.resumeTransfer(jobId) }
    }

    fun cancel(jobId: JobId) {
        viewModelScope.launch { transferService.cancelTransfer(jobId) }
    }

    // ---- State assembly ----
    private fun buildState(
        transfers: List<TransferRow>,
        incoming: List<IncomingTransferRequest>,
        devices: List<Device>,
        dialog: SendDialogState
    ): TransfersUiState {
        val (active, history) = transfers.partition { row ->
            row.state is com.kfilesync.mobile.domain.model.TransferState.Active ||
                    row.state is com.kfilesync.mobile.domain.model.TransferState.Paused ||
                    row.state == com.kfilesync.mobile.domain.model.TransferState.Pending ||
                    row.state == com.kfilesync.mobile.domain.model.TransferState.Verifying
        }

        val pairedDevices = devices.filter { it.state is DeviceState.Paired }
        return TransfersUiState(
            active = active,
            history = history,
            incomingRequests = incoming,
            pairedDevices = pairedDevices,
            sendDialog = dialog
        )
    }
}

// ---------- UI state types ----------

/**
 * Snapshot fed to [TransfersScreen].
 *
 * Marked [Immutable] (Phase 6 T6.2) so Compose treats it as stable and
 * skips equality checks on every recomposition pass. The nested lists are
 * replaced as wholes, never mutated in place.
 */
@androidx.compose.runtime.Immutable
data class TransfersUiState(
    val active: List<TransferRow>,
    val history: List<TransferRow>,
    val incomingRequests: List<IncomingTransferRequest>,
    val pairedDevices: List<Device>,
    val sendDialog: SendDialogState
) {
    companion object {
        val EMPTY = TransfersUiState(
            active = emptyList(),
            history = emptyList(),
            incomingRequests = emptyList(),
            pairedDevices = emptyList(),
            sendDialog = SendDialogState.Hidden
        )
    }
}

/** Send-flow dialog state (T2.6). */
sealed class SendDialogState {
    /** Dialog closed. */
    data object Hidden : SendDialogState()

    /** Step 1: show the paired-device list. */
    data object PickTarget : SendDialogState()

    /** Step 2: file picker is open. We hold the chosen target while we wait. */
    data class PickingFiles(val target: DeviceId) : SendDialogState()

    /** Terminal error (e.g. peer not trusted, network blew up before the request landed). */
    data class Failed(val message: String) : SendDialogState()
}