package com.kfilesync.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App-wide Material 3 colour scheme.
 *
 * Phase 0 - a tasteful indigo seed; Phase 6 (T6.1) refines the full set with
 * Material You / Cupertino adaptations. The platform-specific dynamic colour
 * sources (Android 12 `dynamicColor`, iOS accent colour) plug in at the
 * platform `androidMain` / `iosMain` layer once we need them.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0F766D),
    onSecondary = Color.White,
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF2FF),
    onSurfaceVariant = Color(0xFF4338CA),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111827)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF67E8F9),
    onSecondary = Color(0xFF0F7172),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF374151),
    onSurfaceVariant = Color(0xFFD1D5DB),
    background = Color(0xFF111827),
    onBackground = Color(0xFFE5E7EB)
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}