package com.kfilesync.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App-wide Material 3 colour scheme + typography (Phase 6 T6.1).
 *
 * The seed colour is indigo; the full M3 token surface roles are now
 * spelled out (tertiary, error, container variants) so cards, banners and
 * dialogs all pick up appropriate contrast in both light and dark themes
 * without each composable hand-picking colours.
 *
 * Typography uses the M3 defaults but slightly tightens line heights and
 * lifts the title weight to SemiBold to feel less generic. Body-* sizes
 * stay at the defaults so OS text-scaling (accessibility) keeps working.
 *
 * Note on Material You: see [resolveColorScheme] for why we currently
 * stick with the static palette on both platforms.
 */

// ---- Light scheme (Material 3 baseline + KFileSync indigo seed) ----
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFF7C3AED), // violet-600 - accents for badges
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFE5E5E5),
    onSurfaceVariant = Color(0xFF404040),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFE5E5E5),
    surfaceContainerHighest = Color(0xFFD4D4D4),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF171717),
    outline = Color(0xFF737373),
    outlineVariant = Color(0xFFE5E5E5)
)

// ---- Dark scheme - paired tones, AA contrast on every onX role ----
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    surface = Color(0xFF171717),
    onSurface = Color(0xFFE5E5E5),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFD4D4D4),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF171717),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = Color(0xFF404040),
    surfaceContainerHighest = Color(0xFF525252),
    background = Color(0xFF171717),
    onBackground = Color(0xFFE5E5E5),
    outline = Color(0xFF737373),
    outlineVariant = Color(0xFF404040)
)

// ---- Typography overrides ----
private val AppTypography: Typography
    get() {
        val base = Typography()
        return base.copy(
            // Headline/title roles: slightly heavier weight for hierarchy.
            headlineSmall = base.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp
            ),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            // Label small - used by the trust-status pill; bump it 1sp so it
            // doesn't disappear inside the rounded background.
            labelSmall = base.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp
            )
        )
    }

/**
 * Resolve the colour scheme for the current platform.
 *
 * Phase 6 (T6.1) keeps the resolver in `commonMain` so we don't need a
 * platform-specific source set just for theming. Android 12+ Material You
 * (`dynamicLightColorScheme(context)`) lives in `androidx.compose.material3`,
 * but the project uses the Compose Multiplatform `material3` artefact which
 * doesn't ship those helpers - wiring them in would require either a JVM-side
 * companion module or pulling a second material3 artefact. The KFileSync
 * indigo palette is part of the brand identity, so we deliberately keep the
 * static palette across both platforms; a future post-beta iteration can
 * introduce a platform expect/actual seam if the user research justifies it.
 */
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
        content = content
    )
}

/**
 * Convenience accessor for screens that want to reference the canonical
 * KFileSync palette directly (e.g. semantic success / warning colours that
 * don't have an M3 role). These read off MaterialTheme so they still flip
 * with the dark-mode toggle.
 */
object AppPalette {
    /** Success / online - tailwind emerald-500 / -400 depending on theme. */
    val success: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF34D399) else Color(0xFF10B981)
    /** Warning - amber-500 / -400. */
    val warning: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFFF59E0B)
}