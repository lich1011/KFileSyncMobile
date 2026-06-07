package com.kfilesync.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 6 (T6.1) shared UI primitives - small, theme-aware, accessible.
 *
 * These replace the per-screen `SectionTitle` / `EmptyHint` helpers that
 * were copy-pasted across every tab. Keeping them in one place means the
 * dark-mode palette + typography tweaks land everywhere at once.
 */

/**
 * Section header with a heading-role semantics annotation so screen readers
 * announce it as a heading. The 'count' argument, if non-null, renders a
 * subtle count suffix - used by lists ("Paired devices (3)").
 */
@Composable
fun SectionHeader(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Lightweight empty-state hint - a one-line muted message used inside lists
 * when there's nothing to show. Designed to read as supplementary content
 * (no card, no border), so it sits naturally between section headers.
 */
@Composable
fun EmptyStateHint(
    message: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
    )
}

/**
 * A more prominent empty-state widget for whole-screen "you have nothing
 * here yet" moments. Used by the Sync and Transfers tabs when the user has
 * no shares or no transfers.
 */
@Composable
fun EmptyStatePanel(
    title: String,
    body: String,
    icon: String = stringResource(Res.string.transfers_empty_panel_icon),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = icon, style = MaterialTheme.typography.headlineLarge)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Subtle horizontal divider with appropriate vertical breathing room. */
@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * Immutable display row for "key: value" lines (fingerprint card, device id
 * card). Stable type so callers can lift into LazyColumn keys without
 * worrying about recomposition stability.
 */
@Immutable
data class KeyValueRow(val label: String, val value: String)