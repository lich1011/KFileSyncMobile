package com.kfilesync.mobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.domain.service.SyncPolicyId
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings tab Composable (T5.3, design doc §11.5).
 *
 * Sections (top to bottom):
 * - **Identity**: editable alias + read-only fingerprint card.
 * - **Sync policy**: three radio options (Default / Aggressive /
 * ChargingOnly) with one-line descriptions.
 * - **Storage**: cache size + "Clear cache" button.
 *
 * Behaviour notes:
 * - The alias field is debounced via the ViewModel's `aliasDraft`; the
 * "Save" button only enables when the draft differs from the persisted
 * value. This avoids a no-op DB write storm if the user taps into the
 * field by accident.
 * - Fingerprint shown in 4-char groups for verbal verification.
 * - Policy selection writes through immediately (no Save button) since
 * the radio answers "what mode do you want" - there's no transient
 * state worth hoarding.
 * - "Clear cache" triggers a StorageMaintenanceService sweep; the spinner
 * covers the < 1s typical case.
 * - Errors surface in a dismissible OutlinedCard at the bottom.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        // ---- Identity ----
        SectionTitle(stringResource(Res.string.settings_section_identity))
        OutlinedTextField(
            value = state.aliasDraft,
            onValueChange = viewModel::onAliasChanged,
            label = { Text(stringResource(Res.string.settings_identity_alias)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            val hasUnsavedChange = state.aliasDraft != state.snapshot.alias
            val btnText = if (hasUnsavedChange) {
                stringResource(Res.string.settings_action_save)
            } else {
                stringResource(Res.string.settings_action_saved)
            }
            Button(
                onClick = viewModel::saveAlias,
                enabled = hasUnsavedChange && state.aliasDraft.isNotBlank()
            ) {
                Text(btnText)
            }
        }

        FingerprintCard(
            deviceIdShort = state.snapshot.deviceIdShort,
            fingerprint = state.snapshot.fingerprintFormatted
        )
        // ---- Sync policy ----
        SectionTitle(stringResource(Res.string.settings_section_sync_policy))
        SyncPolicyId.entries.forEach { id ->
            PolicyRow(
                id = id,
                selected = state.snapshot.syncPolicy == id,
                onClick = { viewModel.selectPolicy(id) }
            )
        }
 
        // ---- Storage ----
        SectionTitle(stringResource(Res.string.settings_section_storage))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(Res.string.settings_storage_cache, formatBytes(state.snapshot.cacheSizeBytes)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(Res.string.settings_storage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.isClearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(0.dp).padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = viewModel::clearCache,
                        enabled = !state.isClearing && state.snapshot.cacheSizeBytes > 0
                    ) {
                        Text(stringResource(Res.string.settings_action_clear_cache))
                    }
                }
            }
        }

        // ---- Error banner ----
        state.errorMessage?.let { msg ->
            ErrorBanner(message = msg, onDismiss = viewModel::dismissError)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun FingerprintCard(deviceIdShort: String, fingerprint: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(Res.string.settings_identity_device_id),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (deviceIdShort.isNotBlank()) deviceIdShort else "-",
                style = MaterialTheme.typography.bodyMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_identity_fingerprint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (fingerprint.isNotBlank()) fingerprint else "-",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(Res.string.settings_identity_verify_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PolicyRow(id: SyncPolicyId, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.height(0.dp).padding(start = 8.dp))
        Column {
            Text(text = policyLabel(id), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = policyDescription(id),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_action_dismiss))
            }
        }
    }
}

@Composable
private fun policyLabel(id: SyncPolicyId): String = when (id) {
    SyncPolicyId.Default -> stringResource(Res.string.settings_policy_default_title)
    SyncPolicyId.Aggressive -> stringResource(Res.string.settings_policy_aggressive_title)
    SyncPolicyId.ChargingOnly -> stringResource(Res.string.settings_policy_charging_title)
}

@Composable
private fun policyDescription(id: SyncPolicyId): String = when (id) {
    SyncPolicyId.Default -> stringResource(Res.string.settings_policy_default_desc)
    SyncPolicyId.Aggressive -> stringResource(Res.string.settings_policy_aggressive_desc)
    SyncPolicyId.ChargingOnly -> stringResource(Res.string.settings_policy_charging_desc)
}

/** Compact human-readable byte size. Tied to the cache widget; deliberately simple. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    if (mb < 1024.0) return formatDouble(mb) + " MB"
    val gb = mb / 1024.0
    return formatDouble(gb) + " GB"
}

private fun formatDouble(value: Double): String {
    val whole = value.toInt()
    val frac = ((value - whole) * 10).toInt().coerceIn(0, 9)
    return "$whole.$frac"
}