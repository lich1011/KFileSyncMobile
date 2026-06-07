package com.kfilesync.mobile.ui.screens.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.IncomingTransferRequest
import com.kfilesync.mobile.domain.model.Device
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.kfilesync.mobile.ui.components.EmptyStateHint
import com.kfilesync.mobile.ui.components.SectionHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * Transfers tab Composable (design doc §11.4, T2.6).
 *
 * Sections (top to bottom):
 * - Action row: "Send files" button
 * - Incoming-request banner(s) (when applicable)
 * - Active transfers list
 * - History (completed/failed/cancelled)
 * - Send-target dialog (overlay)
 *
 * Phase 6 (T6.1/T6.2) polish:
 * - The whole screen is a single [LazyColumn]; the static header + action
 * row sit at the top as `item { ... }` blocks. This is cheaper than nesting
 * a LazyColumn inside a vertical-scroll Column and gives us per-row
 * virtualisation on the (potentially long) history list.
 * - Incoming-request banners fade/expand in via [AnimatedVisibility], so a
 * request landing mid-scroll doesn't jolt the layout.
 * - `hasPaired` is computed via [derivedStateOf] so the action row only
 * recomposes when the boolean flips, not on every paired-device row
 * update.
 */
@Composable
fun TransfersScreen(
    viewModel: TransfersViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()
    val hasPaired by remember { derivedStateOf { ui.pairedDevices.isNotEmpty() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("header") {
            Spacer(Modifier.height(8.dp))
            Column {
                Text(stringResource(Res.string.transfers_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(Res.string.transfers_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item("actions") {
            val sendFilesDesc = if (hasPaired) {
                stringResource(Res.string.transfers_send_files)
            } else {
                stringResource(Res.string.transfers_send_files_disabled)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.openSendDialog() },
                    enabled = hasPaired,
                    modifier = Modifier.semantics {
                        contentDescription = sendFilesDesc
                    }
                ) { Text(stringResource(Res.string.transfers_send_files)) }
                if (!hasPaired) {
                    Text(
                        text = stringResource(Res.string.transfers_pair_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- Incoming requests (animated) ----
        items(ui.incomingRequests, key = { "incoming-" + it.sessionId }) { req ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                IncomingRequestBanner(
                    request = req,
                    onAccept = { viewModel.acceptIncoming(req.sessionId) },
                    onReject = { viewModel.rejectIncoming(req.sessionId) }
                )
            }
        }

        // ---- Active list ----
        item("active-header") {
            SectionHeader(stringResource(Res.string.transfers_section_active), count = ui.active.size)
        }
        if (ui.active.isEmpty()) {
            item("active-empty") { EmptyStateHint(stringResource(Res.string.transfers_empty_active)) }
        } else {
            items(ui.active, key = { "active-" + it.jobId.value }) { row ->
                TransferListItem(
                    row = row,
                    onPause = { viewModel.pause(row.jobId) },
                    onResume = { viewModel.resume(row.jobId) },
                    onCancel = { viewModel.cancel(row.jobId) }
                )
            }
        }

        // ---- History ----
        item("history-header") {
            SectionHeader(stringResource(Res.string.transfers_section_history), count = ui.history.size)
        }
        if (ui.history.isEmpty()) {
            item("history-empty") {
                EmptyStateHint(stringResource(Res.string.transfers_empty_history))
            }
        } else {
            items(ui.history, key = { "history-" + it.jobId.value }) { row ->
                TransferListItem(
                    row = row,
                    onPause = { /* no-op */ },
                    onResume = { /* no-op */ },
                    onCancel = { /* no-op */ },
                    readOnly = true
                )
            }
        }

        item("bottom-spacer") { Spacer(Modifier.height(16.dp)) }
    }

    // ---- Send dialog ----
    when (val state = ui.sendDialog) {
        SendDialogState.Hidden -> { /* none */ }
        SendDialogState.PickTarget -> SendTargetDialog(
            paired = ui.pairedDevices,
            onPick = { viewModel.selectTarget(it.id) },
            onCancel = { viewModel.dismissSendDialog() }
        )
        is SendDialogState.PickingFiles -> {
            // No dialog body - the OS file picker is on top of us. Closing
            // it (via cancel) just dismisses; the ViewModel handles the
            // empty list case.
        }
        is SendDialogState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismissSendDialog() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSendDialog() }) { Text(stringResource(Res.string.devices_ok)) }
            },
            title = { Text(stringResource(Res.string.transfers_send_failed)) },
            text = { Text(state.message) }
        )
    }
}

@Composable
private fun SendTargetDialog(
    paired: List<Device>,
    onPick: (Device) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.devices_cancel)) }
        },
        title = { Text(stringResource(Res.string.transfers_send_to_which)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (paired.isEmpty()) {
                    Text(stringResource(Res.string.transfers_no_paired_devices))
                } else {
                    paired.forEach { d ->
                        OutlinedButton(
                            onClick = { onPick(d) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(d.alias, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun IncomingRequestBanner(
    request: IncomingTransferRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(
                    Res.string.transfers_incoming_request,
                    request.fromAlias,
                    request.fileNames.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                request.fileNames.take(3).joinToString(", ") +
                        if (request.fileNames.size > 3) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(Res.string.transfers_total_size, formatBytes(request.totalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(stringResource(Res.string.transfers_action_accept)) }
                OutlinedButton(onClick = onReject) { Text(stringResource(Res.string.transfers_action_reject)) }
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit += 1
    }
    val rounded = ((value * 10).toLong() / 10.0)
    return if (unit == 0) "${bytes} B" else "$rounded ${units[unit]}"
}