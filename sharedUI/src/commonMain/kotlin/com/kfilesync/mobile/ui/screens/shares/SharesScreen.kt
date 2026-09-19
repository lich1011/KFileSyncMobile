package com.kfilesync.mobile.ui.screens.shares

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.domain.model.SharePermission
import com.kfilesync.mobile.domain.model.SyncMode
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.kfilesync.mobile.ui.components.EmptyStateHint
import com.kfilesync.mobile.ui.components.SectionHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shares tab Composable (design doc §11.4, T3.6).
 *
 * Sections (top to bottom):
 * - Header
 * - Pending invitations (one card per invite with Accept / Decline)
 * - Active shares (Active + Paused; Pause/Resume + Leave actions)
 * - History (Left)
 * - Failure dialog (overlay)
 *
 * Phase 6 (T6.1) polish:
 * - Whole screen is a single LazyColumn (was nested LazyColumns inside
 *   a Column, which is non-deterministic on iOS height-measurement).
 * - Pending-invitation cards animate in via [AnimatedVisibility] so a
 *   fresh invite from the desktop slides into view rather than popping.
 * - Accent containerColor on the pending-invitation card uses the M3
 *   secondaryContainer role for theme-aware contrast.
 */
@Composable
fun SharesScreen(
    viewModel: SharesViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()
    var selectedShareId by remember { mutableStateOf<String?>(null) }

    // Detail view (T3.6): when a row is tapped we swap the list for the detail
    // screen. The tab shell has no nav stack, so selection state lives here.
    val selectedRow = selectedShareId?.let { id ->
        (ui.pending + ui.active + ui.history).firstOrNull { it.shareId.value == id }
    }
    if (selectedRow != null) {
        ShareDetailScreen(
            row = selectedRow,
            onBack = { selectedShareId = null },
            onPause = { viewModel.pause(selectedRow.shareId) },
            onResume = { viewModel.resume(selectedRow.shareId) },
            onLeave = {
                viewModel.leave(selectedRow.shareId)
                selectedShareId = null
            },
            contentPadding = contentPadding
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("header") {
            Spacer(Modifier.height(8.dp))
            Column {
                Text(stringResource(Res.string.shares_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(Res.string.shares_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Pending invitations ----
        if (ui.pending.isNotEmpty()) {
            item("pending-header") {
                SectionHeader(stringResource(Res.string.shares_section_pending), count = ui.pending.size)
            }
            items(ui.pending, key = { "pending-" + it.shareId.value }) { row ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    PendingInvitationCard(
                        row = row,
                        onAccept = { viewModel.beginAccept(row) },
                        onDecline = { viewModel.decline(row.shareId) }
                    )
                }
            }
        }

        // ---- Active + Paused ----
        item("active-header") {
            SectionHeader(stringResource(Res.string.shares_section_active), count = ui.active.size)
        }
        if (ui.active.isEmpty()) {
            item("active-empty") {
                EmptyStateHint(stringResource(Res.string.shares_empty_active))
            }
        } else {
            items(ui.active, key = { "active-" + it.shareId.value }) { row ->
                ShareListItem(
                    row = row,
                    onPause = { viewModel.pause(row.shareId) },
                    onResume = { viewModel.resume(row.shareId) },
                    onLeave = { viewModel.leave(row.shareId) },
                    onOpen = { selectedShareId = row.shareId.value }
                )
            }
        }

        // ---- History ----
        if (ui.history.isNotEmpty()) {
            item("history-header") {
                SectionHeader(stringResource(Res.string.shares_section_history), count = ui.history.size)
            }
            items(ui.history, key = { "history-" + it.shareId.value }) { row ->
                ShareListItem(
                    row = row,
                    onPause = { /* no-op */ },
                    onResume = { /* no-op */ },
                    onLeave = { /* no-op */ },
                    readOnly = true,
                    onOpen = { selectedShareId = row.shareId.value }
                )
            }
        }

        item("bottom-spacer") {
            Spacer(Modifier.height(16.dp))
        }
    }

    // ---- Accept-failure dialog ----
    when (val s = ui.acceptUi) {
        AcceptInvitationState.Hidden,
        is AcceptInvitationState.PickingPath -> { /* none */ }
        is AcceptInvitationState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismissAccept() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissAccept() }) { Text(stringResource(Res.string.devices_ok)) }
            },
            title = { Text(stringResource(Res.string.shares_invite_failure_title)) },
            text = { Text(s.message) }
        )
    }
}

@Composable
private fun PendingInvitationCard(
    row: ShareRow,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val pendingDesc = stringResource(Res.string.shares_pending_invite_desc, row.name, row.createdByAlias)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = pendingDesc
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(Res.string.shares_glyph_envelope) + " " + "${row.name}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(
                    Res.string.shares_invite_from,
                    row.createdByAlias,
                    permissionLabel(row.permission),
                    syncModeLabel(row.syncMode)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onAccept) { Text(stringResource(Res.string.transfers_action_accept)) }
                OutlinedButton(onClick = onDecline) { Text(stringResource(Res.string.shares_action_decline)) }
            }
        }
    }
}

@Composable
private fun permissionLabel(p: SharePermission): String = when (p) {
    SharePermission.ReadOnly -> stringResource(Res.string.shares_permission_readonly)
    SharePermission.ReadWrite -> stringResource(Res.string.shares_permission_readwrite)
    SharePermission.SendOnly -> stringResource(Res.string.shares_permission_sendonly)
    SharePermission.ReceiveOnly -> stringResource(Res.string.shares_permission_receiveonly)
}

@Composable
private fun syncModeLabel(m: SyncMode): String = when (m) {
    SyncMode.OneWayPush -> stringResource(Res.string.shares_sync_mode_push)
    SyncMode.OneWayPull -> stringResource(Res.string.shares_sync_mode_pull)
    SyncMode.TwoWay -> stringResource(Res.string.shares_sync_mode_twoway)
}