package com.kfilesync.mobile.ui.screens.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.TransferRow
import com.kfilesync.mobile.domain.model.TransferDirection
import com.kfilesync.mobile.domain.model.TransferState
import com.kfilesync.mobile.ui.theme.AppIcons
import com.kfilesync.mobile.ui.theme.AppPalette
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Material You transfer row in Active / History lists.
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
    val isOutgoing = row.direction == TransferDirection.Outgoing
    val avatarBg = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val avatarFg = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- Header Row ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Direction icon in circle container
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(avatarBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isOutgoing) AppIcons.ArrowUp else AppIcons.ArrowDown,
                        contentDescription = null,
                        tint = avatarFg,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headerTitle(row),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = headerSubtitle(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Progress bar ----
            val progressModifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))

            when (row.state) {
                TransferState.Verifying -> LinearProgressIndicator(modifier = progressModifier)
                TransferState.Completed -> LinearProgressIndicator(
                    progress = { 1f },
                    color = AppPalette.success,
                    modifier = progressModifier
                )
                TransferState.Cancelled,
                TransferState.Failed -> { /* no progress bar */ }
                else -> LinearProgressIndicator(
                    progress = { row.ratio },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = progressModifier
                )
            }

            // ---- State line & controls row ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stateLine(row),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateLineColor(row),
                    fontWeight = FontWeight.Medium
                )

                if (!readOnly) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (row.state) {
                            TransferState.Active -> {
                                if (row.direction == TransferDirection.Outgoing) {
                                    OutlinedButton(
                                        onClick = onPause,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(AppIcons.Pause, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(Res.string.transfers_action_pause))
                                    }
                                }
                                TextButton(
                                    onClick = onCancel,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.transfers_action_cancel))
                                }
                            }
                            TransferState.Paused -> {
                                FilledTonalButton(
                                    onClick = onResume,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.transfers_action_resume))
                                }
                                TextButton(
                                    onClick = onCancel,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.transfers_action_cancel))
                                }
                            }
                            TransferState.Pending,
                            TransferState.Requested,
                            TransferState.Verifying -> {
                                TextButton(
                                    onClick = onCancel,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.transfers_action_cancel))
                                }
                            }
                            else -> { /* terminal state */ }
                        }
                    }
                }
            }
        }
    }
}

private fun headerTitle(row: TransferRow): String {
    val name = row.firstFileName.substringAfterLast('/').ifBlank { row.firstFileName }
    val suffix = if (row.totalFiles > 1) " (+${row.totalFiles - 1} more)" else ""
    val arrow = if (row.direction == TransferDirection.Outgoing) " → " else " ← "
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
private fun stateLine(row: TransferRow): String = when (row.state) {
    TransferState.Pending, TransferState.Requested -> stringResource(Res.string.transfers_state_pending)
    TransferState.Active -> stringResource(Res.string.transfers_state_active)
    TransferState.Paused -> stringResource(Res.string.transfers_state_paused)
    TransferState.Verifying -> stringResource(Res.string.transfers_state_verifying)
    TransferState.Completed -> stringResource(Res.string.transfers_state_completed)
    TransferState.Failed -> stringResource(Res.string.transfers_state_failed, row.errorMessage ?: "")
    TransferState.Cancelled -> stringResource(Res.string.transfers_state_cancelled)
}

@Composable
private fun stateLineColor(row: TransferRow) = when (row.state) {
    TransferState.Failed -> MaterialTheme.colorScheme.error
    TransferState.Completed -> AppPalette.success
    TransferState.Active -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}