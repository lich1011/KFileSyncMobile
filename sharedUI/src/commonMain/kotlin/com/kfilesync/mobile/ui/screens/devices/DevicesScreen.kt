package com.kfilesync.mobile.ui.screens.devices

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.ui.components.EmptyStateHint
import com.kfilesync.mobile.ui.components.SectionHeader
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Devices tab Composable (design doc §11.2 mock).
 *
 * Sections (top to bottom):
 * - Self header: "I am <alias>"
 * - Action row: "Enter IP" button
 * - Paired devices list
 * - Discovered (unpaired) devices list
 * - Optional pairing dialog (PIN entry / result toast / manual IP dialog)
 *
 * Phase 6 (T6.1) polish:
 * - `LazyColumn` no longer nests inside a vertically-scrolling `Column`
 * because that breaks intrinsic measurement on iOS. The whole screen is
 * a single LazyColumn now, with the static header / action rows as
 * `item { }` blocks at the top - same visual, far less recomposition on
 * scroll because cards are lazily realised.
 * - Discovered / paired lists fade in via [AnimatedVisibility] so new
 * mDNS hits don't pop in jarringly.
 * - Self header gets a semantic content description so screen readers
 * announce identity context once at the top of the screen.
 */
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val ui by viewModel.uiState.collectAsState()
    var showManualIp by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("header") {
            Spacer(Modifier.height(8.dp))
            SelfHeader(selfAlias = ui.selfAlias)
        }

        item("actions") {
            val enterIpDesc = stringResource(Res.string.devices_enter_ip_desc)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showManualIp = true },
                    modifier = Modifier.semantics {
                        contentDescription = enterIpDesc
                    }
                ) {
                    Text(stringResource(Res.string.devices_enter_ip))
                }
            }
        }

        // ---- Paired list ----
        item("paired-header") {
            SectionHeader(title = stringResource(Res.string.devices_paired_section), count = ui.paired.size)
        }
        if (ui.paired.isEmpty()) {
            item("paired-empty") {
                EmptyStateHint(stringResource(Res.string.devices_paired_empty))
            }
        } else {
            items(ui.paired, key = { "paired-" + it.device.id.value }) { row ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    DeviceListItem(
                        row = row,
                        onRevoke = { viewModel.revoke(row.device.id) }
                    )
                }
            }
        }

        // ---- Discovered List ----
        item("discovered-header") {
            SectionHeader(title = stringResource(Res.string.devices_discovered_section), count = ui.discovered.size)
        }
        if (ui.discovered.isEmpty()) {
            item("discovered-empty") {
                EmptyStateHint(stringResource(Res.string.devices_discovered_empty))
            }
        } else {
            items(ui.discovered, key = { "discovered-" + it.deviceId.value }) { peer ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    DiscoveredDeviceItem(
                        peer = peer,
                        onPair = { viewModel.beginPairing(peer) }
                    )
                }
            }
        }

        item("bottom-spacer") {
            Spacer(Modifier.height(16.dp))
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
            title = stringResource(Res.string.devices_pair_success_title),
            message = stringResource(Res.string.devices_pair_success_msg, pairing.peer.alias),
            onDismiss = { viewModel.dismissPairingResult() }
        )
        is PairingUiState.Failed -> PairingResultDialog(
            title = stringResource(Res.string.devices_pair_failed_title),
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
    val title = if (selfAlias.isNotBlank()) {
        stringResource(Res.string.devices_self_identity, selfAlias)
    } else {
        stringResource(Res.string.devices_title)
    }
    val desc = stringResource(Res.string.devices_identity_desc, title)
    Column(
        modifier = Modifier.semantics {
            contentDescription = desc
        }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = stringResource(Res.string.devices_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}