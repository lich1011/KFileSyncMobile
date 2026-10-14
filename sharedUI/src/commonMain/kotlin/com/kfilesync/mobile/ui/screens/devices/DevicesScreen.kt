package com.kfilesync.mobile.ui.screens.devices

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.ui.components.EmptyStatePanel
import com.kfilesync.mobile.ui.components.OnlineDot
import com.kfilesync.mobile.ui.components.SectionHeader
import com.kfilesync.mobile.ui.theme.AppIcons
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Devices tab Composable adhering to Google Material You (Material 3).
 *
 * Layout:
 * - Material You Identity Hero card with local device avatar, alias, online badge & IP probe action
 * - Paired devices section with elevated cards
 * - Discovered devices section with tonal pair buttons
 * - Dialog overlays with Material 3 28dp rounded surfaces
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("hero-identity") {
            Spacer(Modifier.height(4.dp))
            SelfIdentityHero(
                selfAlias = ui.selfAlias,
                onEnterIp = { showManualIp = true }
            )
        }

        // ---- Paired list ----
        item("paired-header") {
            SectionHeader(
                title = stringResource(Res.string.devices_paired_section),
                count = ui.paired.size
            )
        }
        if (ui.paired.isEmpty()) {
            item("paired-empty") {
                EmptyStatePanel(
                    title = stringResource(Res.string.devices_paired_section),
                    body = stringResource(Res.string.devices_paired_empty),
                    icon = AppIcons.Shield
                )
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
            SectionHeader(
                title = stringResource(Res.string.devices_discovered_section),
                count = ui.discovered.size
            )
        }
        if (ui.discovered.isEmpty()) {
            item("discovered-empty") {
                EmptyStatePanel(
                    title = stringResource(Res.string.devices_discovered_section),
                    body = stringResource(Res.string.devices_discovered_empty),
                    icon = AppIcons.Refresh
                )
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

/**
 * Material You Hero card presenting the local device identity and manual IP affordance.
 */
@Composable
private fun SelfIdentityHero(
    selfAlias: String,
    onEnterIp: () -> Unit
) {
    val title = if (selfAlias.isNotBlank()) selfAlias else stringResource(Res.string.devices_title)
    val enterIpDesc = stringResource(Res.string.devices_enter_ip_desc)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Identity avatar
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "This Device",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        OnlineDot(online = true)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(Res.string.devices_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilledTonalButton(
                    onClick = onEnterIp,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.semantics {
                        contentDescription = enterIpDesc
                    }
                ) {
                    Icon(
                        imageVector = AppIcons.QrCodeOrIp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.devices_enter_ip))
                }
            }
        }
    }
}