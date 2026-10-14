package com.kfilesync.mobile.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kfilesync.mobile.ui.screens.devices.DevicesScreen
import com.kfilesync.mobile.ui.screens.settings.SettingsScreen
import com.kfilesync.mobile.ui.screens.shares.SharesScreen
import com.kfilesync.mobile.ui.screens.sync.SyncScreen
import com.kfilesync.mobile.ui.screens.transfer.TransfersScreen
import com.kfilesync.mobile.ui.theme.AppIcons
import kfilesyncmobile.sharedui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Five-tab bottom-bar navigation shell adhering to Google Material You (Material 3).
 *
 * Features:
 * - Material 3 NavigationBar with tonal elevation and pill indicator
 * - Vector Material You iconography
 * - Responsive selection feedback with animated transitions
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    var selected by rememberSaveable { mutableStateOf(TabId.Devices) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                tonalElevation = 3.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                TabId.entries.forEach { tab ->
                    val isSelected = selected == tab
                    val label = tab.label()
                    val desc = if (isSelected) stringResource(Res.string.tab_selected_desc, label) else label

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selected = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
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
                (fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(140)))
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

fun TabId.icon(): ImageVector = when (this) {
    TabId.Devices -> AppIcons.Devices
    TabId.Transfers -> AppIcons.Transfers
    TabId.Shares -> AppIcons.Shares
    TabId.Sync -> AppIcons.Sync
    TabId.Settings -> AppIcons.Settings
}