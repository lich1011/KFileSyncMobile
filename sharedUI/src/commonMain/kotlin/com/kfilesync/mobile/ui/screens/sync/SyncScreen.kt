package com.kfilesync.mobile.ui.screens.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.domain.model.SyncConflict
import com.kfilesync.mobile.ui.components.AppHeader
import com.kfilesync.mobile.ui.components.EmptyStatePanel
import com.kfilesync.mobile.ui.components.SectionHeader
import com.kfilesync.mobile.ui.theme.AppIcons
import com.kfilesync.mobile.ui.theme.AppPalette
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sync tab Composable adhering to Google Material You (Material 3).
 */
@Composable
fun SyncScreen(
    viewModel: SyncViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- Header ----
        item("header") {
            Spacer(Modifier.height(4.dp))
            AppHeader(
                title = stringResource(Res.string.sync_title),
                subtitle = stringResource(Res.string.sync_subtitle)
            )
        }

        // ---- Global status ----
        item("status") { StatusBanner(ui) }

        // ---- Per-share rows ----
        item("shares-header") {
            SectionHeader(
                title = stringResource(Res.string.sync_section_active_shares),
                count = ui.shares.size
            )
        }
        if (ui.shares.isEmpty()) {
            item("shares-empty") {
                EmptyStatePanel(
                    title = stringResource(Res.string.sync_section_active_shares),
                    body = stringResource(Res.string.sync_active_shares_empty),
                    icon = AppIcons.Sync
                )
            }
        } else {
            items(ui.shares, key = { "share-" + it.shareId.value }) { row ->
                ShareSyncRow(
                    row = row,
                    busy = ui.busyShareId == row.shareId,
                    globalRunning = ui.running,
                    onSyncNow = { viewModel.syncShare(row.shareId) }
                )
            }
        }

        // ---- Conflicts (animated section) ----
        if (ui.conflicts.isNotEmpty()) {
            item("conflicts-header") {
                SectionHeader(
                    title = stringResource(Res.string.sync_section_conflicts),
                    count = ui.conflicts.size
                )
            }
            items(ui.conflicts, key = { "conflict-${it.shareId.value}:${it.path}" }) { conflict ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    ConflictListItem(
                        conflict = conflict,
                        onKeepLocal = { viewModel.resolveKeepLocal(conflict.shareId, conflict.path) },
                        onKeepRemote = { viewModel.resolveKeepRemote(conflict.shareId, conflict.path) },
                        onKeepBoth = { viewModel.resolveKeepBoth(conflict.shareId, conflict.path) }
                    )
                }
            }
        }

        item("bottom-spacer") {
            Spacer(Modifier.height(16.dp))
        }
    }
}

private enum class StatusMode { Running, Failed, Idle, Synced }

@Composable
private fun StatusBanner(ui: SyncUiState) {
    val mode = remember(ui.running, ui.lastError, ui.lastSyncedAt) {
        when {
            ui.running -> StatusMode.Running
            ui.lastError != null -> StatusMode.Failed
            ui.lastSyncedAt != null -> StatusMode.Synced
            else -> StatusMode.Idle
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (mode) {
                StatusMode.Failed -> MaterialTheme.colorScheme.errorContainer
                StatusMode.Running -> MaterialTheme.colorScheme.primaryContainer
                StatusMode.Synced -> AppPalette.successContainer
                StatusMode.Idle -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Crossfade(targetState = mode, animationSpec = tween(220), label = "sync-status") { m ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (m) {
                    StatusMode.Running -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Column {
                            Text(
                                text = stringResource(Res.string.sync_state_syncing),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Exchanging metadata and version vectors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    StatusMode.Failed -> {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(Res.string.sync_state_last_failed),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = (ui.lastError ?: "").take(120),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                    StatusMode.Synced -> {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AppPalette.success.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = null,
                                tint = AppPalette.success,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "All shares up to date",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppPalette.onSuccessContainer
                            )
                            Text(
                                text = stringResource(Res.string.sync_state_last_synced, formatRelativeMs(ui.lastSyncedAt ?: 0L)),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppPalette.onSuccessContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    StatusMode.Idle -> {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = stringResource(Res.string.sync_state_idle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareSyncRow(
    row: ShareRow,
    busy: Boolean,
    @Suppress("UNUSED_PARAMETER") globalRunning: Boolean,
    onSyncNow: () -> Unit
) {
    val syncBtnDesc = if (busy) {
        stringResource(Res.string.sync_action_syncing_share, row.name)
    } else {
        stringResource(Res.string.sync_action_sync_share_now, row.name)
    }
    val membersText = if (row.memberCount == 1) {
        stringResource(Res.string.sync_member_count_singular, row.memberCount)
    } else {
        stringResource(Res.string.sync_member_count, row.memberCount)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
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
                    text = membersText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = onSyncNow,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.semantics {
                    contentDescription = syncBtnDesc
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(AppIcons.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.sync_action_sync_now))
                }
            }
        }
    }
}

@Composable
private fun ConflictListItem(
    conflict: SyncConflict,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onKeepBoth: () -> Unit
) {
    val conflictDesc = stringResource(Res.string.sync_conflict_desc, conflict.path)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = AppPalette.warningContainer
        ),
        border = BorderStroke(1.dp, AppPalette.warning.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics { contentDescription = conflictDesc },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Warning,
                    contentDescription = null,
                    tint = AppPalette.onWarningContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = conflict.path,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.onWarningContainer
                )
            }

            Text(
                text = stringResource(Res.string.sync_conflict_action_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = AppPalette.onWarningContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onKeepLocal,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(Res.string.sync_action_keep_mine),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                OutlinedButton(
                    onClick = onKeepRemote,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(Res.string.sync_action_keep_theirs),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                OutlinedButton(
                    onClick = onKeepBoth,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        stringResource(Res.string.sync_action_keep_both),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun formatRelativeMs(epochMs: Long): String {
    if (epochMs <= 0L) return stringResource(Res.string.sync_time_just_now)
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val diffSec = (now - epochMs) / 1000L
    return when {
        diffSec < 0L -> stringResource(Res.string.sync_time_just_now)
        diffSec < 60L -> stringResource(Res.string.sync_time_seconds_ago, diffSec)
        diffSec < 3600L -> stringResource(Res.string.sync_time_minutes_ago, diffSec / 60L)
        diffSec < 86400L -> stringResource(Res.string.sync_time_hours_ago, diffSec / 3600L)
        else -> stringResource(Res.string.sync_time_days_ago, diffSec / 86400L)
    }
}