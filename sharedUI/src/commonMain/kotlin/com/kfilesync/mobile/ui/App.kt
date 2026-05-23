package com.kfilesync.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.identity.LocalIdentityProvider
import com.kfilesync.mobile.application.service.DeviceAppService
import com.kfilesync.mobile.infrastructure.network.HttpServer
import com.kfilesync.mobile.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.compose.KoinContext
import org.koin.compose.koinInject

/**
 * Top-level composable shared by Android & iOS.
 *
 * Phase 0 scaffold renders the PoC screen [Phase0PocScreen] that exercises:
 * - The Koin shared graph (identity provider + device service + HttpServer)
 * - Co-routines hopping through application services
 * - Compose Material3 rendering on both platforms
 *
 * Phase 1 (T1.8) replaces the body with `AppNavigation` and the four-tab
 * navigation skeleton.
 */
@Composable
fun App() {
    AppTheme {
        KoinContext {
            Phase0PocScreen()
        }
    }
}

/**
 * Phase 0 PoC screen - proves the runtime stack
 * (Compose UI -> Koin -> Application service -> Repository -> SQLDelight) works
 * end-to-end on both platforms.
 */
@Composable
private fun Phase0PocScreen() {
    val identityProvider: LocalIdentityProvider = koinInject()
    val deviceService: DeviceAppService = koinInject()
    val httpServer: HttpServer = koinInject()
    val scope = rememberCoroutineScope()

    // Cache the immutable identity for display - pulling it on every recomposition
    // is fine (InMemoryLocalIdentityProvider is just a field read), but `remember`
    // makes the intent clear: this value never changes during the screen's life.
    val identity = remember { identityProvider.current() }

    val pairedDevices by deviceService.observeDevices().collectAsState(initial = emptyList())

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("KFileSync - Phase 0 PoC") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .safeContentPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IdentityCard(
                    alias = identity.alias,
                    deviceId = identity.deviceId.value,
                    fingerprint = identity.fingerprint.hex,
                    platform = identity.platform.name
                )

                ActionsCard(
                    httpServerRunning = httpServer.isRunning,
                    onStartServer = { httpServer.start() },
                    onStopServer = { httpServer.stop() },
                    onRefreshDevices = { scope.launch { deviceService.refreshPaired() } }
                )

                PairedDevicesCard(devices = pairedDevices)

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "This is a Phase 0 acceptance demo. Pairing, transfer and sync " +
                            "land in later phases - see mobile-design/14-detailed-tasks.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(
    alias: String,
    deviceId: String,
    fingerprint: String,
    platform: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Local identity", style = MaterialTheme.typography.titleMedium)
            LabelValue("Alias", alias)
            LabelValue("Platform", platform)
            LabelValue("Device id", deviceId)
            LabelValue("Fingerprint", fingerprint.take(16) + "...")
        }
    }
}

@Composable
private fun ActionsCard(
    httpServerRunning: Boolean,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onRefreshDevices: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Quick actions", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Lansync HTTP server: " + if (httpServerRunning) "running on :53317" else "stopped",
                style = MaterialTheme.typography.bodyMedium
            )
            if (httpServerRunning) {
                Button(onClick = onStopServer) { Text("Stop HTTP server") }
            } else {
                Button(onClick = onStartServer) { Text("Start HTTP server") }
            }
            Button(onClick = onRefreshDevices) { Text("Refresh paired devices") }
        }
    }
}

@Composable
private fun PairedDevicesCard(devices: List<com.kfilesync.mobile.domain.model.Device>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Paired devices (${devices.size})", style = MaterialTheme.typography.titleMedium)
            if (devices.isEmpty()) {
                Text(
                    text = "No paired devices yet. Phase 1 T1.3 brings up the pairing flow.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                devices.forEach { device ->
                    Text("- ${device.alias} (${device.platform.name})")
                }
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}