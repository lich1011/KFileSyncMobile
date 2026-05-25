package com.kfilesync.mobile.ui.screens.devices

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

/**
 * Devices tab Composable (design doc §11.2 mock).
 *
 * Sections (top to bottom):
 * - Self header: "I am «alias»"
 * - Action row: "Enter IP" button
 * - Paired devices list
 * - Discovered (unpaired) devices list
 * - Optional pairing dialog (PIN entry / result toast / manual IP dialog)
 */
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()
    var showManualIp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Self header ----
        SelfHeader(selfAlias = ui.selfAlias)

        // ---- Action row ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { showManualIp = true }) { Text("Enter IP") }
        }

        // ---- Paired list ----
        SectionTitle("Paired devices (${ui.paired.size})")
        if (ui.paired.isEmpty()) {
            EmptyHint("No paired devices yet. Pair from the list below.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(ui.paired, key = { it.device.id.value }) { row ->
                    DeviceListItem(
                        row = row,
                        onRevoke = { viewModel.revoke(row.device.id) }
                    )
                }
            }
        }

        // ---- Discovered list ----
        SectionTitle("Discovered (${ui.discovered.size})")
        if (ui.discovered.isEmpty()) {
            EmptyHint(
                "No nearby devices found. Make sure both devices are on the same Wi-Fi, " +
                        "or use \"Enter IP\" above."
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(ui.discovered, key = { it.deviceId.value }) { peer ->
                    DiscoveredDeviceItem(
                        peer = peer,
                        onPair = { viewModel.beginPairing(peer) }
                    )
                }
            }
        }
    }

    // ---- Pairing dialog ----
    when (val pairing = ui.pairing) {
        PairingUiState.Idle -> { /* no dialog */ }
        is PairingUiState.AwaitingPin -> PairDialog(
            target = pairing.target,
            descriptor = pairing.descriptor,
            onConfirm = { pin -> viewModel.submitPin(pin) },
            onCancel = { viewModel.cancelPairing() }
        )
        is PairingUiState.Succeeded -> PairingResultDialog(
            title = "Paired",
            message = "${pairing.peer.alias} is now trusted.",
            onDismiss = { viewModel.dismissPairingResult() }
        )
        is PairingUiState.Failed -> PairingResultDialog(
            title = "Pairing failed",
            message = pairing.message,
            onDismiss = { viewModel.dismissPairingResult() }
        )
    }

    // ---- Manual IP dialog ----
    if (showManualIp) {
        ManualIpDialog(
            onSubmit = { host, port ->
                viewModel.probeManual(host, port)
                showManualIp = false
            },
            onCancel = { showManualIp = false }
        )
    }
}

@Composable
private fun SelfHeader(selfAlias: String) {
    Column {
        Text(
            text = if (selfAlias.isNotBlank()) "I am «$selfAlias»" else "Devices",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Trust devices on your LAN to send and sync files.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}