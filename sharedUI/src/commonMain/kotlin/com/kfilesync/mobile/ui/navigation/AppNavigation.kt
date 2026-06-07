package com.kfilesync.mobile.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kfilesyncmobile.sharedui.generated.resources.Res
import kfilesyncmobile.sharedui.generated.resources.tab_devices
import kfilesyncmobile.sharedui.generated.resources.tab_devices_glyph
import kfilesyncmobile.sharedui.generated.resources.tab_transfers
import kfilesyncmobile.sharedui.generated.resources.tab_transfers_glyph
import kfilesyncmobile.sharedui.generated.resources.tab_shares
import kfilesyncmobile.sharedui.generated.resources.tab_shares_glyph
import kfilesyncmobile.sharedui.generated.resources.tab_sync
import kfilesyncmobile.sharedui.generated.resources.tab_sync_glyph
import kfilesyncmobile.sharedui.generated.resources.tab_settings
import kfilesyncmobile.sharedui.generated.resources.tab_settings_glyph
import kfilesyncmobile.sharedui.generated.resources.tab_selected_desc
import org.jetbrains.compose.resources.stringResource
import com.kfilesync.mobile.ui.screens.devices.DevicesScreen
import com.kfilesync.mobile.ui.screens.settings.SettingsScreen
import com.kfilesync.mobile.ui.screens.shares.SharesScreen
import com.kfilesync.mobile.ui.screens.sync.SyncScreen
import com.kfilesync.mobile.ui.screens.transfer.TransfersScreen

/**
 * Five-tab bottom-bar navigation shell (design doc §11.1).
 *
 * Tabs:
 * - Devices   (Phase 1, fully wired)
 * - Transfers (Phase 2)
 * - Shares    (Phase 3)
 * - Sync      (Phase 4)
 * - Settings  (Phase 5 - alias + fingerprint + policy + cache)
 *
 * Phase 6 (T6.1) polish:
 * - Tab content is wrapped in [AnimatedContent] with a 200ms cross-fade so
 * switching tabs no longer feels like a slamming refresh. The transition
 * is deliberately short (Material guidance is 150-250ms for tab change)
 * and uses tween rather than spring - tabs aren't a physical motion.
 * - Selection state moves from `remember` to `rememberSaveable` so a
 * process-recreation (Android dark-mode flip, configuration change)
 * doesn't bounce the user back to Devices.
 * - Each NavigationBarItem gets a content description that includes the
 * selection state, so screen readers announce "Devices, selected".
 *
 * Tab state is the single source of truth here. Per-screen state is held by
 * Koin/ViewModels so switching tabs doesn't tear down a syncing process.
 *
 * Icons use emoji-as-text because `material-icons-extended` isn't on the
 * Compose Multiplatform classpath and a 5 MB font for five glyphs isn't
 * worth it.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    var selected by rememberSaveable { mutableStateOf(TabId.Devices) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                tonalElevation = 2.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                TabId.entries.forEach { tab ->
                    val isSelected = selected == tab
                    val label = tab.label()
                    val glyph = tab.glyph()
                    val desc = if (isSelected) stringResource(Res.string.tab_selected_desc, label) else label
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selected = tab },
                        icon = {
                            Text(
                                glyph,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        label = { Text(label) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = desc
                        }
                    )
                }
            }
        }
    ) { inner ->
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(animationSpec = tween(200)) togetherWith
                        fadeOut(animationSpec = tween(150)))
            },
            label = "tab-transition"
        ) { tab ->
            when (tab) {
                TabId.Devices -> DevicesScreen(contentPadding = inner)
                TabId.Transfers -> TransfersScreen(contentPadding = inner)
                TabId.Shares -> SharesScreen(contentPadding = inner)
                TabId.Sync -> SyncScreen(contentPadding = inner)
                TabId.Settings -> SettingsScreen(contentPadding = inner)
            }
        }
    }
}

@Immutable
enum class TabId {
    Devices,
    Transfers,
    Shares,
    Sync,
    Settings,
}

@Composable
fun TabId.label(): String = when (this) {
    TabId.Devices -> stringResource(Res.string.tab_devices)
    TabId.Transfers -> stringResource(Res.string.tab_transfers)
    TabId.Shares -> stringResource(Res.string.tab_shares)
    TabId.Sync -> stringResource(Res.string.tab_sync)
    TabId.Settings -> stringResource(Res.string.tab_settings)
}

@Composable
fun TabId.glyph(): String = when (this) {
    TabId.Devices -> stringResource(Res.string.tab_devices_glyph)
    TabId.Transfers -> stringResource(Res.string.tab_transfers_glyph)
    TabId.Shares -> stringResource(Res.string.tab_shares_glyph)
    TabId.Sync -> stringResource(Res.string.tab_sync_glyph)
    TabId.Settings -> stringResource(Res.string.tab_settings_glyph)
}