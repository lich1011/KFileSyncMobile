package com.kfilesync.mobile.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.application.service.PairingSessionDescriptor
import com.kfilesync.mobile.domain.port.DiscoveredDevice

/**
 * Pairing PIN-entry dialog (design doc §11.3 mock).
 *
 * Layout:
 * - Top: target alias + fingerprint prefix
 * - Middle: our local 6-digit PIN in big monospaced font
 * - Below: text field for the peer's PIN
 * - Bottom: Confirm + Cancel
 */
@Composable
fun PairDialog(
    target: DiscoveredDevice,
    descriptor: PairingSessionDescriptor,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with ${target.alias}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Verify the fingerprint matches on both devices, then enter the PIN the other device is showing.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Fingerprint: " + target.fingerprint.hex.take(16) + "…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = descriptor.pin,
                        style = MaterialTheme.typography.displaySmall
                    )
                }
                Text("Your PIN - share this with the other device.", style = MaterialTheme.typography.labelSmall)

                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { v -> enteredPin = v.filter { it.isDigit() }.take(6) },
                    label = { Text("PIN from the other device") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(enteredPin) },
                enabled = enteredPin.length == 6
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

/** Simple toast-style dialog rendered after a pairing succeeds/fails. */
@Composable
fun PairingResultDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

/** Manual-IP entry dialog launched from the Devices screen. */
@Composable
fun ManualIpDialog(
    onSubmit: (String, Int) -> Unit,
    onCancel: () -> Unit
) {
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("53317") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enter IP address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    placeholder = { Text("192.168.1.42") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(host.trim(), portText.toIntOrNull() ?: 53317) },
                enabled = host.isNotBlank()
            ) { Text("Probe") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}