package com.hebi.gallipick.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.gallipick.AppThemeMode

@Immutable
internal data class GalliPalette(
    val ink: Color, val muted: Color, val line: Color, val panel: Color,
    val background: Color, val surface: Color, val mint: Color, val peach: Color,
    val primary: Color, val onPrimary: Color,
)

// Source: the "화면별 디자인 가이드" in the project design-assets folder.
// The reference muted #6B7280 is slightly adjusted to #616874 for WCAG AA
// contrast on the reference's cream surface and pale-mint panel.
internal val LightPalette = GalliPalette(
    ink = Color(0xFF1F2937), muted = Color(0xFF616874), line = Color(0xFFE0E4D8),
    panel = Color(0xFFE6F4ED), background = Color(0xFFFFF9EF), surface = Color(0xFFFFF3E2),
    mint = Color(0xFFA4D4B4), peach = Color(0xFFFFE7A3),
    primary = Color(0xFF1F6F54), onPrimary = Color.White,
)

internal val DarkPalette = GalliPalette(
    ink = Color(0xFFE6F2E8), muted = Color(0xFFB6CDB9), line = Color(0xFF405949),
    panel = Color(0xFF273E30), background = Color(0xFF101A17), surface = Color(0xFF1A2A20),
    mint = Color(0xFF345C3E), peach = Color(0xFF654936),
    primary = Color(0xFFB0E2B7), onPrimary = Color(0xFF173B21),
)

internal object GalliUiMetrics {
    val touchTarget = 48.dp
    val headerHeight = 56.dp
    val buttonCorner = 12.dp
    val cardCorner = 16.dp
    val navigationCorner = 14.dp
    val cardPadding = 14.dp
    val cardSpacing = 10.dp
    val navigationIcon = 24.dp
}

private val LocalGalliPalette = staticCompositionLocalOf { LightPalette }
internal val IsGalliDark: Boolean @Composable get() = LocalGalliPalette.current == DarkPalette
internal val Ink: Color @Composable get() = LocalGalliPalette.current.ink
internal val Muted: Color @Composable get() = LocalGalliPalette.current.muted
internal val Line: Color @Composable get() = LocalGalliPalette.current.line
internal val Panel: Color @Composable get() = LocalGalliPalette.current.panel
internal val AppBackground: Color @Composable get() = LocalGalliPalette.current.background
internal val AppSurface: Color @Composable get() = LocalGalliPalette.current.surface
internal val Mint: Color @Composable get() = LocalGalliPalette.current.mint
internal val Peach: Color @Composable get() = LocalGalliPalette.current.peach
internal val Primary: Color @Composable get() = LocalGalliPalette.current.primary
internal val OnPrimary: Color @Composable get() = LocalGalliPalette.current.onPrimary

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GalliPickTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = if (themeMode.isDark(isSystemInDarkTheme())) DarkPalette else LightPalette
    val colors = if (palette == DarkPalette) darkColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        secondary = palette.muted, onSecondary = palette.background,
        tertiary = Color(0xFFECC5A8), onTertiary = Color(0xFF422C1D),
        background = palette.background, onBackground = palette.ink,
        primaryContainer = palette.mint, onPrimaryContainer = palette.ink,
        secondaryContainer = palette.panel, onSecondaryContainer = palette.ink,
        tertiaryContainer = palette.peach, onTertiaryContainer = palette.ink,
        surface = palette.surface, onSurface = palette.ink,
        surfaceVariant = palette.panel, onSurfaceVariant = palette.muted,
        inverseSurface = palette.ink, inverseOnSurface = palette.background, inversePrimary = LightPalette.primary,
        surfaceTint = Color.Transparent, surfaceBright = Color(0xFF30443A), surfaceDim = palette.background,
        surfaceContainer = palette.panel, surfaceContainerHigh = Color(0xFF293E34), surfaceContainerHighest = Color(0xFF30463B),
        surfaceContainerLow = palette.surface, surfaceContainerLowest = palette.background,
        outline = palette.muted, outlineVariant = palette.line, scrim = Color.Black,
        error = Color(0xFFFFB6A5), onError = Color(0xFF552013),
        errorContainer = Color(0xFF64362C), onErrorContainer = Color(0xFFFFDAD0),
    ) else lightColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        secondary = palette.muted, onSecondary = Color.White,
        tertiary = palette.primary, onTertiary = Color.White,
        background = palette.background, onBackground = palette.ink,
        primaryContainer = palette.mint, onPrimaryContainer = palette.ink, inversePrimary = palette.mint,
        secondaryContainer = palette.panel, onSecondaryContainer = palette.ink,
        tertiaryContainer = palette.peach, onTertiaryContainer = palette.ink,
        surface = palette.surface, onSurface = palette.ink, surfaceVariant = palette.panel, onSurfaceVariant = palette.muted,
        inverseSurface = palette.ink, inverseOnSurface = Color.White, surfaceTint = Color.Transparent,
        surfaceBright = palette.surface, surfaceDim = palette.line,
        surfaceContainer = palette.panel, surfaceContainerHigh = palette.panel, surfaceContainerHighest = palette.line,
        surfaceContainerLow = palette.background, surfaceContainerLowest = palette.surface,
        outline = palette.muted, outlineVariant = palette.line, scrim = palette.ink,
        error = Color(0xFF944837), onError = Color.White,
        errorContainer = Color(0xFFF9E7DC), onErrorContainer = Color(0xFF753B2D),
    )
    val typography = Typography()
    CompositionLocalProvider(LocalGalliPalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            shapes = Shapes(
                RoundedCornerShape(8.dp), RoundedCornerShape(GalliUiMetrics.buttonCorner),
                RoundedCornerShape(14.dp), RoundedCornerShape(GalliUiMetrics.cardCorner),
                RoundedCornerShape(20.dp),
            ),
            typography = typography.copy(
                headlineLarge = typography.headlineLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
                headlineMedium = typography.headlineMedium.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp),
                headlineSmall = typography.headlineSmall.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp),
                titleLarge = typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
                titleMedium = typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
                titleSmall = typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                bodyLarge = typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
                bodyMedium = typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
                bodySmall = typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
                labelLarge = typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                labelMedium = typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp),
                labelSmall = typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
            ),
            content = content,
        )
    }
}
