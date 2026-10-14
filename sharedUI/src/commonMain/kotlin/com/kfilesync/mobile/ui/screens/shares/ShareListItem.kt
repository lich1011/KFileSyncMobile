package com.kfilesync.mobile.ui.screens.shares

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.domain.model.SharePermission
import com.kfilesync.mobile.domain.model.ShareStatus
import com.kfilesync.mobile.domain.model.SyncMode
import com.kfilesync.mobile.ui.theme.AppIcons
import com.kfilesync.mobile.ui.theme.AppPalette
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Material You row in the Active / History share list.
 */
@Composable
fun ShareListItem(
    row: ShareRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onLeave: () -> Unit,
    readOnly: Boolean = false,
    onOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${row.syncMode.label()} • ${row.permission.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status chip
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(statusBgColor(row.status))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLine(row.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusFgColor(row.status),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Members & path info
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

            if (row.localPath.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = row.localPath.take(70) + if (row.localPath.length > 70) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Controls
            if (!readOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (row.status) {
                        ShareStatus.Active -> {
                            OutlinedButton(
                                onClick = onPause,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(AppIcons.Pause, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(Res.string.transfers_action_pause))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = onLeave,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(Res.string.shares_action_leave))
                            }
                        }
                        ShareStatus.Paused -> {
                            FilledTonalButton(
                                onClick = onResume,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(Res.string.transfers_action_resume))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = onLeave,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(Res.string.shares_action_leave))
                            }
                        }
                        else -> { /* Pending uses its own card */ }
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
private fun statusBgColor(status: ShareStatus) = when (status) {
    ShareStatus.Active -> AppPalette.successContainer
    ShareStatus.Left -> MaterialTheme.colorScheme.errorContainer
    ShareStatus.Paused -> AppPalette.warningContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
private fun statusFgColor(status: ShareStatus) = when (status) {
    ShareStatus.Active -> AppPalette.onSuccessContainer
    ShareStatus.Left -> MaterialTheme.colorScheme.onErrorContainer
    ShareStatus.Paused -> AppPalette.onWarningContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun SyncMode.label(): String = when (this) {
    SyncMode.OneWayPush -> stringResource(Res.string.shares_sync_mode_push)
    SyncMode.OneWayPull -> stringResource(Res.string.shares_sync_mode_pull)
    SyncMode.TwoWay -> stringResource(Res.string.shares_sync_mode_twoway)
}

@Composable
internal fun SharePermission.label(): String = when (this) {
    SharePermission.ReadOnly -> stringResource(Res.string.shares_permission_readonly)
    SharePermission.ReadWrite -> stringResource(Res.string.shares_permission_readwrite)
}