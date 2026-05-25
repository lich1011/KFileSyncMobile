package com.kfilesync.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kfilesync.mobile.ui.navigation.AppNavigation
import com.kfilesync.mobile.ui.theme.AppTheme
import org.koin.compose.KoinContext

/**
 * Top-level composable shared by Android & iOS.
 *
 * Phase 1 (T1.8) replaces the Phase 0 PoC screen with [AppNavigation], the
 * four-tab bottom-nav shell. The Devices tab is fully wired (T1.7/T1.8); the
 * other four tabs are clearly-labelled placeholders pointing at the phase
 * that fills them in.
 */
@Composable
fun App() {
    AppTheme {
        KoinContext {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppNavigation()
            }
        }
    }
}