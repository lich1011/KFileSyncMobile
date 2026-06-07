package com.kfilesync.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.domain.model.TrustStatus
import com.kfilesync.mobile.ui.theme.AppPalette
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Tiny pill-style badge representing a device's trust state.
 *
 * Colour scheme (Phase 6 polish):
 * - Discovered -> neutral surfaceVariant (read off MaterialTheme)
 * - Paired     -> emerald (theme-aware via [AppPalette.success])
 * - Revoked    -> MaterialTheme errorContainer (flips appropriately in dark)
 *
 * Accessibility: the visible text already conveys the state, so we only need
 * a brief content-description override when consumers wrap the badge in a
 * row with other text (avoids the screen reader saying "Paired Paired").
 */
@Composable
fun DeviceStatusBadge(status: TrustStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        TrustStatus.Discovered -> stringResource(Res.string.devices_trust_discovered)
        TrustStatus.Paired -> stringResource(Res.string.devices_trust_paired)
        TrustStatus.Revoked -> stringResource(Res.string.devices_trust_revoked)
    }
    val (bg, fg) = when (status) {
        TrustStatus.Discovered -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        TrustStatus.Paired -> Pair(
            AppPalette.success.copy(alpha = 0.15f),
            AppPalette.success
        )
        TrustStatus.Revoked -> Pair(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    val statusDesc = stringResource(Res.string.devices_trust_status_desc, label)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .background(bg, shape = RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = statusDesc }
    )
}

/**
 * Small coloured dot used to indicate live online status next to a device alias.
 * Uses the theme-aware [AppPalette.success] for online and the muted outline
 * colour for offline, so both states stay legible in dark mode.
 *
 * Accessibility: the dot is decorative on its own (no caller-supplied label),
 * but we attach a content description so blind users hear "online" / "offline"
 * when focusing the row.
 */
@Composable
fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    val color = if (online) AppPalette.success else MaterialTheme.colorScheme.outline
    val desc = if (online) stringResource(Res.string.devices_online) else stringResource(Res.string.devices_offline)
    Text(
        text = if (online) "\u25CF" /* ● */ else "\u25CB" /* ○ */,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.semantics {
            contentDescription = desc
        }
    )
}