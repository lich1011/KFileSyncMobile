package com.kfilesync.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.domain.model.TrustStatus

/**
 * Tiny pill-style badge representing a device's trust state.
 *
 * Colour scheme:
 * - Discovered -> neutral surfaceVariant
 * - Paired     -> green (success)
 * - Revoked    -> red (error)
 *
 * Intentionally not theme-tinted: the meaning ("trusted vs not") is more
 * important than tracking the user's accent colour, so we use Compose's
 * fixed semantic colours.
 */
@Composable
fun DeviceStatusBadge(status: TrustStatus, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (status) {
        TrustStatus.Discovered -> Triple(
            "Discovered",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        TrustStatus.Paired -> Triple(
            "Paired",
            Color(0xFFD1FAE5), // tailwind emerald-100
            Color(0xFF065F46)  // emerald-800
        )
        TrustStatus.Revoked -> Triple(
            "Revoked",
            Color(0xFFFECACA), // tailwind red-200
            Color(0xFF991B1B)  // red-800
        )
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .background(bg, shape = RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * Slightly different shape: a small coloured dot used to indicate live
 * online status next to a device alias. Phase 1 T1.9 wires the data source.
 */
@Composable
fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = if (online) "●" else "○",
        color = if (online) Color(0xFF10B981) /* emerald-500 */ else Color(0xFF9CA3AF) /* gray-400 */,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
    )
}