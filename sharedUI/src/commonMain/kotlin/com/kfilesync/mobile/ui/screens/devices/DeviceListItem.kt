package com.kfilesync.mobile.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.domain.model.DeviceState
import com.kfilesync.mobile.domain.model.TrustStatus
import com.kfilesync.mobile.domain.port.DiscoveredDevice
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.kfilesync.mobile.ui.components.DeviceStatusBadge
import com.kfilesync.mobile.ui.components.OnlineDot

/**
 * Compose row for a paired device (design doc §11.2).
 *
 * Layout (left-to-right):
 * [platform icon] alias       Paired-badge
 * device id.. Online-dot      [revoke]
 */
@Composable
fun DeviceListItem(
    row: PairedDeviceRow,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trust = row.device.state.toTrustStatus()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(platformGlyph(row.device.platform.name), style = MaterialTheme.typography.titleLarge)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(row.device.alias, style = MaterialTheme.typography.titleMedium)
                    DeviceStatusBadge(status = trust)
                    OnlineDot(online = row.online)
                }
                Text(
                    text = row.device.id.value.take(12) + "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trust == TrustStatus.Paired) {
                TextButton(onClick = onRevoke) { Text(stringResource(Res.string.devices_action_revoke)) }
            }
        }
    }
}

/**
 * Compose row for a newly-discovered, unpaired device. Tapping kicks off
 * the pairing flow via the parent `DevicesScreen`'s `onPair` callback.
 */
@Composable
fun DiscoveredDeviceItem(
    peer: DiscoveredDevice,
    onPair: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(platformGlyph(peer.platform.name), style = MaterialTheme.typography.titleLarge)
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.alias, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = peer.fingerprint.hex.take(12) + "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onPair) { Text(stringResource(Res.string.devices_action_pair)) }
        }
    }
}

@Composable
internal fun platformGlyph(name: String): String = when (name) {
    "Android" -> stringResource(Res.string.devices_platform_android)
    "iOS" -> stringResource(Res.string.devices_platform_ios)
    "Windows", "MacOS", "Linux" -> stringResource(Res.string.devices_platform_computer)
    else -> stringResource(Res.string.devices_platform_unknown)
}

private fun DeviceState.toTrustStatus(): TrustStatus = when (this) {
    is DeviceState.Discovered -> TrustStatus.Discovered
    is DeviceState.Paired -> TrustStatus.Paired
    is DeviceState.Revoked -> TrustStatus.Revoked
}