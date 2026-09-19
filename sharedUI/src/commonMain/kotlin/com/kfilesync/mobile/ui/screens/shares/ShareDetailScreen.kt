package com.kfilesync.mobile.ui.screens.shares

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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

/**
 * Share detail screen (design doc §11.4, T3.6) - previously an unimplemented
 * placeholder.
 *
 * Presents everything carried in a [ShareRow] - name, status, sync mode,
 * permission, creator, member count, local mirror path, share id - plus the
 * same lifecycle actions as the list row (Pause / Resume / Leave) and a Back
 * affordance. Read-only when the share is in the `Left` (history) state.
 *
 * Uses plain string literals rather than `Res.string.*`: the generated
 * resource accessors only contain keys already declared in the resource XML,
 * so adding new `Res.string` references here would not compile until those
 * keys exist. Member-by-member and conflict-by-file breakdowns (the richest
 * part of the §11.4 mock) need additional `ShareAppService` queries (the full
 * "Share" aggregate with its "members", and `SyncAppService.observeConflicts`
 * filtered by share); that data plumbing is a follow-up.
 */
@Composable
fun ShareDetailScreen(
    row: ShareRow,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val readOnly = row.status == ShareStatus.Left

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onBack) { Text("\u2190 Back to shares") }

        Text(
            text = "\uD83D\uDCC1 " + row.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DetailRow(label = "Status", value = statusText(row.status), valueColor = statusColor(row.status))
                HorizontalDivider()
                DetailRow(label = "Sync mode", value = syncModeText(row.syncMode))
                DetailRow(label = "Permission", value = permissionText(row.permission))
                HorizontalDivider()
                DetailRow(label = "Created by", value = row.createdByAlias)
                DetailRow(
                    label = "Members",
                    value = if (row.memberCount == 1) "1 member" else "${row.memberCount} members"
                )
                if (row.localPath.isNotBlank()) {
                    HorizontalDivider()
                    DetailRow(label = "Local folder", value = row.localPath)
                }
                HorizontalDivider()
                DetailRow(label = "Share ID", value = row.shareId.value)
            }
        }

        if (!readOnly) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (row.status) {
                    ShareStatus.Active ->
                        OutlinedButton(onClick = onPause) { Text("Pause") }
                    ShareStatus.Paused ->
                        OutlinedButton(onClick = onResume) { Text("Resume") }
                    else -> { /* Pending is handled in the invitation card */ }
                }
                Button(onClick = onLeave) { Text("Leave share") }
            }
        } else {
            Text(
                text = "You left this share. It is shown for history only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

private fun statusText(status: ShareStatus): String = when (status) {
    ShareStatus.Pending -> "Pending invitation"
    ShareStatus.Active -> "Active"
    ShareStatus.Paused -> "Paused"
    ShareStatus.Left -> "Left"
}

@Composable
private fun statusColor(status: ShareStatus) = when (status) {
    ShareStatus.Active -> MaterialTheme.colorScheme.primary
    ShareStatus.Left -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun syncModeText(mode: SyncMode): String = when (mode) {
    SyncMode.OneWayPush -> "One-way push"
    SyncMode.OneWayPull -> "One-way pull"
    SyncMode.TwoWay -> "Two-way sync"
}

private fun permissionText(p: SharePermission): String = when (p) {
    SharePermission.ReadOnly -> "Read only"
    SharePermission.ReadWrite -> "Read / write"
    SharePermission.SendOnly -> "Send only"
    SharePermission.ReceiveOnly -> "Receive only"
}