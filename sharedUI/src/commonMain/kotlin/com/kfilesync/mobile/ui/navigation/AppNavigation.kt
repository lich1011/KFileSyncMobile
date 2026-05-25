package com.kfilesync.mobile.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.ui.screens.devices.DevicesScreen

/**
 * Four/five-tab bottom-bar navigation shell (design doc §11.1).
 *
 * Tabs:
 * - Devices   (Phase 1, fully wired this phase)
 * - Transfers (Phase 2 placeholder)
 * - Shares    (Phase 3 placeholder)
 * - Sync      (Phase 4 placeholder)
 * - Settings  (Phase 1 placeholder; SettingsScreen lands in Phase 5)
 *
 * Tab state is a plain `remember { mutableStateOf(TabId) }`. Per-screen state
 * (ViewModels) is held by Koin so we don't pay for save-state-across-tabs
 * machinery in Phase 1. Voyager's TabNavigator can drop in later if we
 * decide we want sub-route stacks per tab.
 *
 * Icons: we use emoji-as-text instead of material.icons.* because the
 * `androidx.compose.material:material-icons-extended` artifact isn't on
 * the Compose Multiplatform classpath out of the box and we don't want to
 * pull a 5 MB icon font for five glyphs.
 */
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf(TabId.Devices) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TabId.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Text(tab.glyph, style = MaterialTheme.typography.titleMedium) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { inner ->
        when (selected) {
            TabId.Devices -> DevicesScreen(contentPadding = inner)
            TabId.Transfers -> PlaceholderScreen(title = "Transfers", phase = "Phase 2 (T2.5)", inner = inner)
            TabId.Shares -> PlaceholderScreen(title = "Shares", phase = "Phase 3 (T3.4)", inner = inner)
            TabId.Sync -> PlaceholderScreen(title = "Sync", phase = "Phase 4 (T4.7)", inner = inner)
            TabId.Settings -> PlaceholderScreen(title = "Settings", phase = "Phase 5 (T5.3)", inner = inner)
        }
    }
}

enum class TabId(val label: String, val glyph: String) {
    Devices("Devices", "\uD83D\uDCF1"),    // 📱
    Transfers("Transfers", "\u2191\u2193"),  // ↑↓
    Shares("Shares", "\uD83D\uDCC1"),       // 📁
    Sync("Sync", "\uD83D\uDD04"),           // 🔄
    Settings("Settings", "\u2699"),         // ⚙
}

@Composable
private fun PlaceholderScreen(title: String, phase: String, inner: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Lands in $phase. See mobile-design/14-detailed-tasks.md.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}