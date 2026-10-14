package com.kfilesync.mobile.ui.screens.shares

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.ui.components.AppHeader
import com.kfilesync.mobile.ui.components.EmptyStatePanel
import com.kfilesync.mobile.ui.components.SectionHeader
import com.kfilesync.mobile.ui.theme.AppIcons
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Shares tab Composable adhering to Google Material You (Material 3).
 */
@Composable
fun SharesScreen(
    viewModel: SharesViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()
    var selectedShareId by remember { mutableStateOf<String?>(null) }

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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("header") {
            Spacer(Modifier.height(4.dp))
            AppHeader(
                title = stringResource(Res.string.shares_title),
                subtitle = stringResource(Res.string.shares_subtitle)
            )
        }

        // ---- Pending invitations ----
        if (ui.pending.isNotEmpty()) {
            item("pending-header") {
                SectionHeader(
                    title = stringResource(Res.string.shares_section_pending),
                    count = ui.pending.size
                )
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
            SectionHeader(
                title = stringResource(Res.string.shares_section_active),
                count = ui.active.size
            )
        }
        if (ui.active.isEmpty()) {
            item("active-empty") {
                EmptyStatePanel(
                    title = stringResource(Res.string.shares_section_active),
                    body = stringResource(Res.string.shares_empty_active),
                    icon = AppIcons.Folder
                )
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
                SectionHeader(
                    title = stringResource(Res.string.shares_section_history),
                    count = ui.history.size
                )
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
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissAccept() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(Res.string.devices_ok))
                }
            },
            title = {
                Text(
                    text = stringResource(Res.string.shares_invite_failure_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium) }
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
            .semantics { contentDescription = pendingDesc },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
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
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Shares,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(
                            Res.string.shares_invite_from,
                            row.createdByAlias,
                            row.permission.label(),
                            row.syncMode.label()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
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
                    onClick = onDecline,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(stringResource(Res.string.shares_action_decline))
                }
            }
        }
    }
}