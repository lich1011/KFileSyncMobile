package com.kfilesync.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.domain.model.TrustStatus
import com.kfilesync.mobile.ui.theme.AppIcons
import com.kfilesync.mobile.ui.theme.AppPalette
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Material You pill badge representing device trust status.
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
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        TrustStatus.Paired -> Pair(
            AppPalette.successContainer,
            AppPalette.onSuccessContainer
        )
        TrustStatus.Revoked -> Pair(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    val statusDesc = stringResource(Res.string.devices_trust_status_desc, label)

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = statusDesc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == TrustStatus.Paired) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg
        )
    }
}

/**
 * Material You status dot indicating live online presence.
 * Features an inner dot with a subtle surrounding halo.
 */
@Composable
fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    val color = if (online) AppPalette.success else MaterialTheme.colorScheme.outlineVariant
    val haloColor = if (online) AppPalette.success.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    val desc = if (online) stringResource(Res.string.devices_online) else stringResource(Res.string.devices_offline)

    Box(
        modifier = modifier
            .size(12.dp)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(haloColor)
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}