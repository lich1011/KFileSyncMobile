package com.kfilesync.mobile.ui.screens.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.IncomingTransferRequest
import com.kfilesync.mobile.domain.model.Device
import com.kfilesync.mobile.ui.components.AppHeader
import com.kfilesync.mobile.ui.components.EmptyStateHint
import com.kfilesync.mobile.ui.components.EmptyStatePanel
import com.kfilesync.mobile.ui.components.SectionHeader
import com.kfilesync.mobile.ui.screens.devices.platformVector
import com.kfilesync.mobile.ui.theme.AppIcons
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Transfers tab Composable adhering to Google Material You (Material 3).
 */
@Composable
fun TransfersScreen(
    viewModel: TransfersViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()
    val hasPaired by remember { derivedStateOf { ui.pairedDevices.isNotEmpty() } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("header") {
            Spacer(Modifier.height(4.dp))
            AppHeader(
                title = stringResource(Res.string.transfers_title),
                subtitle = stringResource(Res.string.transfers_subtitle)
            )
        }

        item("send-hero-action") {
            val sendFilesDesc = if (hasPaired) {
                stringResource(Res.string.transfers_send_files)
            } else {
                stringResource(Res.string.transfers_send_files_disabled)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Upload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(Res.string.transfers_send_files),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!hasPaired) {
                                Text(
                                    text = stringResource(Res.string.transfers_pair_first),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.openSendDialog() },
                        enabled = hasPaired,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.semantics {
                            contentDescription = sendFilesDesc
                        }
                    ) {
                        Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.transfers_send_files))
                    }
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
            SectionHeader(
                title = stringResource(Res.string.transfers_section_active),
                count = ui.active.size
            )
        }
        if (ui.active.isEmpty()) {
            item("active-empty") {
                EmptyStatePanel(
                    title = stringResource(Res.string.transfers_section_active),
                    body = stringResource(Res.string.transfers_empty_active),
                    icon = AppIcons.Transfers
                )
            }
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
            SectionHeader(
                title = stringResource(Res.string.transfers_section_history),
                count = ui.history.size
            )
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
        is SendDialogState.PickingFiles -> { /* OS file picker takes over */ }
        is SendDialogState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismissSendDialog() },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissSendDialog() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(Res.string.devices_ok))
                }
            },
            title = { Text(stringResource(Res.string.transfers_send_failed), style = MaterialTheme.typography.titleLarge) },
            text = { Text(state.message, style = MaterialTheme.typography.bodyMedium) }
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
        shape = RoundedCornerShape(28.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(Res.string.transfers_send_to_which),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paired.isEmpty()) {
                    Text(
                        stringResource(Res.string.transfers_no_paired_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    paired.forEach { d ->
                        Card(
                            onClick = { onPick(d) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = platformVector(d.platform.name),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    text = d.alias,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(Res.string.devices_cancel))
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    stringResource(
                        Res.string.transfers_incoming_request,
                        request.fromAlias,
                        request.fileNames.size
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Text(
                request.fileNames.take(3).joinToString(", ") +
                        if (request.fileNames.size > 3) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    stringResource(Res.string.transfers_total_size, formatBytes(request.totalBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAccept,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.transfers_action_accept))
                }
                OutlinedButton(
                    onClick = onReject,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(stringResource(Res.string.transfers_action_reject))
                }
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
    return if (unit == 0) "$bytes B" else "$rounded ${units[unit]}"
}