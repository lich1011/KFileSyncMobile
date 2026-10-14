package com.kfilesync.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Google Material You (Material 3) Design Language Theme.
 *
 * Implements a modern, airy light color scheme featuring:
 * - Dynamic pastel tonal palette (Material You Core Blue & Slate tones)
 * - Tonal elevation hierarchy (surfaceContainerLowest, Low, Container, High)
 * - Expressive typography scale with clear hierarchy and comfortable reading rhythm
 * - Standard Material 3 squircle & pill shape geometry
 */

// ---- Light scheme (Material You / M3 Light Tonal Palette) ----
private val LightColors = lightColorScheme(
    primary = Color(0xFF285EA7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    inversePrimary = Color(0xFFA8C8FF),

    secondary = Color(0xFF555F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),

    tertiary = Color(0xFF6F5675),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9D8FD),
    onTertiaryContainer = Color(0xFF28132E),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    surface = Color(0xFFFBF8FD),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F3F7),
    surfaceContainer = Color(0xFFEFECEF),
    surfaceContainerHigh = Color(0xFFE9E6EB),
    surfaceContainerHighest = Color(0xFFE3E1E6),

    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF191C20),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0)
)

// ---- Dark scheme (M3 paired tones with AA contrast) ----
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF004689),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF285EA7),

    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD9E3F8),

    tertiary = Color(0xFFDCBCE1),
    onTertiary = Color(0xFF3F2845),
    tertiaryContainer = Color(0xFF563E5C),
    onTertiaryContainer = Color(0xFFF9D8FD),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),

    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),

    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F)
)

// ---- Material You Shape Hierarchy ----
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// ---- Material 3 Typography Scale ----
private val AppTypography: Typography
    get() {
        val base = Typography()
        return base.copy(
            headlineMedium = base.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.2).sp
            ),
            headlineSmall = base.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp
            ),
            titleLarge = base.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp
            ),
            titleMedium = base.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            titleSmall = base.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            bodyLarge = base.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp
            ),
            bodyMedium = base.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            bodySmall = base.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            labelLarge = base.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            labelMedium = base.labelMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            labelSmall = base.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            )
        )
    }

private fun resolveColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkColors else LightColors

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = resolveColorScheme(darkTheme),
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * Material You extended semantic palette for statuses and accent highlights.
 */
object AppPalette {
    val success: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6DD58C) else Color(0xFF198754)

    val successContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF005322) else Color(0xFFD1E7DD)

    val onSuccessContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8CF8A8) else Color(0xFF0F5132)

    val warning: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFB951) else Color(0xFFD97706)

    val warningContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF5F3A00) else Color(0xFFFEF3C7)

    val onWarningContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFDDB3) else Color(0xFF92400E)

    val info: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF82B1FF) else Color(0xFF0D6EFD)

    val infoContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF003F8A) else Color(0xFFCFE2FF)

    val cardBorder: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF33363D) else Color(0xFFE5E7EB)
}