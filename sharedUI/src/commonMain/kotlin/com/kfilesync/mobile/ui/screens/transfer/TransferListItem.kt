package com.kfilesync.mobile.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.TransferRow
import com.kfilesync.mobile.domain.model.TransferDirection
import com.kfilesync.mobile.domain.model.TransferState
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One transfer row in the Active / History lists (design doc §11.4, T2.6).
 *
 * Layout (top -> bottom):
 * - Header Line: "↑ Photo.jpg -> Alice's MacBook" • "32% • 4.2 MB / 13.0 MB"
 * - Progress bar (Linear; indeterminate when state == Verifying)
 * - State Line: "Active" / "Paused - tap Resume" / "Completed 2s ago" / "Failed: ..."
 * - Controls row: Pause | Cancel (or Resume | Cancel or nothing on history)
 *
 * The `readOnly` flag suppresses the controls row for history items - we still
 * render the row so the user can see what just happened, but can't pause a
 * completed job.
 */
@Composable
fun TransferListItem(
    row: TransferRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ---- Header ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = directionGlyph(row.direction),
                    style = MaterialTheme.typography.titleMedium
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headerTitle(row),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = headerSubtitle(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Progress bar ----
            // Determinate for Active / Paused (we know transferredBytes); indeterminate
            // for Verifying (final SHA-256 may take seconds on large files).
            when (row.state) {
                TransferState.Verifying -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                is TransferState.Completed -> LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth()
                )
                TransferState.Cancelled,
                is TransferState.Failed -> { /* no progress bar; state line carries the message */ }
                else -> LinearProgressIndicator(
                    progress = { row.ratio },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- State Line ----
            Text(
                text = stateLine(row),
                style = MaterialTheme.typography.bodySmall,
                color = stateLineColor(row)
            )

            // ---- Controls ----
            if (!readOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (row.state) {
                        is TransferState.Active -> {
                            // Resume only works for incoming after restart; outgoing
                            // resume needs a re-pick (TransferServiceImpl returns
                            // PermissionDenied). We surface Pause for outgoing only.
                            if (row.direction == TransferDirection.Outgoing) {
                                OutlinedButton(onClick = onPause) { Text(stringResource(Res.string.transfers_action_pause)) }
                            }
                            TextButton(onClick = onCancel) { Text(stringResource(Res.string.transfers_action_cancel)) }
                        }
                        is TransferState.Paused -> {
                            OutlinedButton(onClick = onResume) { Text(stringResource(Res.string.transfers_action_resume)) }
                            TextButton(onClick = onCancel) { Text(stringResource(Res.string.transfers_action_cancel)) }
                        }
                        TransferState.Pending,
                        TransferState.Verifying -> {
                            TextButton(onClick = onCancel) { Text(stringResource(Res.string.transfers_action_cancel)) }
                        }
                        else -> { /* terminal state - no controls */ }
                    }
                }
            }
        }
    }
}

@Composable
private fun directionGlyph(d: TransferDirection): String = when (d) {
    TransferDirection.Outgoing -> stringResource(Res.string.transfers_glyph_outgoing)
    TransferDirection.Incoming -> stringResource(Res.string.transfers_glyph_incoming)
}

private fun headerTitle(row: TransferRow): String {
    val name = row.firstFileName.substringAfterLast('/').ifBlank { row.firstFileName }
    val suffix = if (row.totalFiles > 1) " (+${row.totalFiles - 1} more)" else ""
    val arrow = if (row.direction == TransferDirection.Outgoing) " -> " else " <- "
    return "$name$suffix$arrow${row.peerAlias}"
}

private fun headerSubtitle(row: TransferRow): String {
    val percent = (row.ratio * 100).toInt()
    val sent = formatBytes(row.transferredBytes)
    val total = formatBytes(row.totalBytes)
    val files = if (row.totalFiles > 1) " • ${row.completedFiles}/${row.totalFiles} files" else ""
    return "$percent% • $sent / $total$files"
}

@Composable
private fun stateLine(row: TransferRow): String = when (val s = row.state) {
    TransferState.Pending -> stringResource(Res.string.transfers_state_pending)
    is TransferState.Active -> stringResource(Res.string.transfers_state_active)
    is TransferState.Paused -> stringResource(Res.string.transfers_state_paused)
    TransferState.Verifying -> stringResource(Res.string.transfers_state_verifying)
    is TransferState.Completed -> stringResource(Res.string.transfers_state_completed)
    is TransferState.Failed -> stringResource(Res.string.transfers_state_failed, row.errorMessage ?: s.errorMessage)
    TransferState.Cancelled -> stringResource(Res.string.transfers_state_cancelled)
}

@Composable
private fun stateLineColor(row: TransferRow) = when (row.state) {
    is TransferState.Failed -> MaterialTheme.colorScheme.error
    is TransferState.Completed -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}