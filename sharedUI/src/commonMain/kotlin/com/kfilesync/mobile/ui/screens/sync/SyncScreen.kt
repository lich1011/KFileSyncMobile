package com.kfilesync.mobile.ui.screens.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.ShareRow
import com.kfilesync.mobile.domain.model.SyncConflict
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.kfilesync.mobile.ui.components.EmptyStateHint
import com.kfilesync.mobile.ui.components.SectionHeader
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sync tab Composable (design doc §11.4, T4.9).
 *
 * Sections (top to bottom):
 * - Header + global status line (running / last synced / last error)
 * - Active shares with "Sync now" buttons
 * - Conflicts list (each with three resolution buttons)
 *
 * Phase 6 (T6.1) polish:
 * - The status banner uses [Crossfade] so flipping between Idle / Running /
 * Last-synced / Last-error doesn't jump-cut.
 * - Conflicts list collapses/expands via [AnimatedVisibility] when the
 * list goes from empty to non-empty (or vice versa) - a freshly
 * detected conflict slides in rather than appearing.
 * - Whole screen is a single LazyColumn for the same reasons as
 * Devices/Transfers (cheaper, virtualised).
 * - Conflict row gets a content description that includes the path so
 * TalkBack/VoiceOver announces *which* file is conflicting.
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Header ----
        item("header") {
            Spacer(Modifier.height(8.dp))
            Column {
                Text(stringResource(Res.string.sync_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(Res.string.sync_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Global status ----
        item("status") { StatusBanner(ui) }

        // ---- Per-share rows ----
        item("shares-header") {
            SectionHeader(stringResource(Res.string.sync_section_active_shares), count = ui.shares.size)
        }
        if (ui.shares.isEmpty()) {
            item("shares-empty") {
                EmptyStateHint(stringResource(Res.string.sync_active_shares_empty))
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
                SectionHeader(stringResource(Res.string.sync_section_conflicts), count = ui.conflicts.size)
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

/**
 * Status banner reduces to one of four "mode" enum values. We hoist the
 * mode into [StatusMode] so [Crossfade] only triggers on the kind, not on
 * every text mutation inside running / idle.
 */
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
        colors = CardDefaults.cardColors(
            containerColor = when (mode) {
                StatusMode.Failed -> MaterialTheme.colorScheme.errorContainer
                StatusMode.Running -> MaterialTheme.colorScheme.secondaryContainer
                StatusMode.Synced -> MaterialTheme.colorScheme.primaryContainer
                StatusMode.Idle -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Crossfade(targetState = mode, animationSpec = tween(220), label = "sync-status") { m ->
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (m) {
                    StatusMode.Running -> {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).padding(2.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(Res.string.sync_state_syncing), style = MaterialTheme.typography.bodyMedium)
                    }
                    StatusMode.Failed -> {
                        Text(stringResource(Res.string.sync_glyph_warning), style = MaterialTheme.typography.titleMedium)
                        Column {
                            Text(
                                stringResource(Res.string.sync_state_last_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = (ui.lastError ?: "").take(120),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    StatusMode.Synced -> {
                        Text(
                            stringResource(Res.string.sync_glyph_check),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(Res.string.sync_state_last_synced, formatRelativeMs(ui.lastSyncedAt ?: 0L)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    StatusMode.Idle -> {
                        Text(
                            stringResource(Res.string.sync_state_idle),
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.shares_glyph_folder) + " ${row.name}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = membersText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The button stays enabled when *another* share is syncing, so the
            // user can queue the next sync - the service's per-(share, peer)
            // gate prevents double-runs.
            Button(
                onClick = onSyncNow,
                enabled = !busy,
                modifier = Modifier.semantics {
                    contentDescription = syncBtnDesc
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).padding(2.dp),
                        strokeWidth = 2.dp
                    )
                } else {
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp).semantics {
                contentDescription = conflictDesc
            },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(Res.string.sync_glyph_warning) + " ${conflict.path}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = stringResource(Res.string.sync_conflict_action_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onKeepLocal) { Text(stringResource(Res.string.sync_action_keep_mine)) }
                OutlinedButton(onClick = onKeepRemote) { Text(stringResource(Res.string.sync_action_keep_theirs)) }
                OutlinedButton(onClick = onKeepBoth) { Text(stringResource(Res.string.sync_action_keep_both)) }
            }
        }
    }
}

/**
 * Roughly format "how long ago" from an epoch-ms timestamp.
 * We don't depend on kotlinx-datetime here (the project deliberately avoids
 * pulling it just for one screen); a coarse minutes/hours/days bucketing is
 * enough for an "at-a-glance" line.
 */
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