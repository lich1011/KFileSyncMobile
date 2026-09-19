package com.kfilesync.mobile.ui.screens.shares

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.domain.model.SharePermission
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncMode
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One row in the Active / History share list (design doc §11.4, T3.6).
 *
 * Layout:
 * - Header line: "📁 Design Files"
 * - Mode + permission line: "Two-way sync • Read/Write"
 * - Status line: "Active" / "Paused - tap Resume" / "Left"
 * - Member line: "From Alice's MacBook • 3 members"
 * - Local-path line (if set): "/storage/emulated/0/Shares/Design"
 * - Controls row: Pause | Leave (or Resume | Leave; nothing on history)
 *
 * 'readOnly' suppresses the controls row for the Left / history section so
 * users can still see why a share is gone but can't act on it.
 */
@Composable
fun ShareListItem(
    row: ShareRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onLeave: () -> Unit,
    readOnly: Boolean = false,
    onOpen: (()-> Unit)? =null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header
            Text(
                text = stringResource(Res.string.shares_glyph_folder) + " ${row.name}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            // Mode + permission
            Text(
                text = "${row.syncMode.label()} • ${row.permission.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Status Line
            Text(
                text = statusLine(row.status),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(row.status)
            )

            // Members
            val membersText = if (row.memberCount == 1) {
                stringResource(Res.string.shares_members_info_singular, row.createdByAlias, row.memberCount)
            } else {
                stringResource(Res.string.shares_members_info, row.createdByAlias, row.memberCount)
            }
            Text(
                text = membersText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Local path (when accepted)
            if (row.localPath.isNotBlank()) {
                Text(
                    text = row.localPath.take(80) + if (row.localPath.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Controls
            if (!readOnly) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (row.status) {
                        ShareStatus.Active -> {
                            OutlinedButton(onClick = onPause) { Text(stringResource(Res.string.transfers_action_pause)) }
                            TextButton(onClick = onLeave) { Text(stringResource(Res.string.shares_action_leave)) }
                        }
                        ShareStatus.Paused -> {
                            OutlinedButton(onClick = onResume) { Text(stringResource(Res.string.transfers_action_resume)) }
                            TextButton(onClick = onLeave) { Text(stringResource(Res.string.shares_action_leave)) }
                        }
                        else -> { /* Pending uses its own card; Left is read-only */ }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLine(status: ShareStatus): String = when (status) {
    ShareStatus.Pending -> stringResource(Res.string.shares_status_pending)
    ShareStatus.Active -> stringResource(Res.string.shares_status_active)
    ShareStatus.Paused -> stringResource(Res.string.shares_status_paused)
    ShareStatus.Left -> stringResource(Res.string.shares_status_left)
}

@Composable
private fun statusColor(status: ShareStatus) = when (status) {
    ShareStatus.Active -> MaterialTheme.colorScheme.primary
    ShareStatus.Left -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Typed enum -> user-visible label. Centralised here so both the card and screen agree. */
@Composable
private fun SyncMode.label(): String = when (this) {
    SyncMode.OneWayPush -> stringResource(Res.string.shares_sync_mode_push)
    SyncMode.OneWayPull -> stringResource(Res.string.shares_sync_mode_pull)
    SyncMode.TwoWay -> stringResource(Res.string.shares_sync_mode_twoway)
}

@Composable
private fun SharePermission.label(): String = when (this) {
    SharePermission.ReadOnly -> stringResource(Res.string.shares_permission_readonly)
    SharePermission.ReadWrite -> stringResource(Res.string.shares_permission_readwrite)
    SharePermission.SendOnly -> stringResource(Res.string.shares_permission_sendonly)
    SharePermission.ReceiveOnly -> stringResource(Res.string.shares_permission_receiveonly)
}