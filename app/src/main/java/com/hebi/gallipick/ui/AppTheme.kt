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

internal val LightPalette = GalliPalette(
    ink = Color(0xFF214637), muted = Color(0xFF536D5A), line = Color(0xFFD9E5D9),
    panel = Color(0xFFEDF5EC), background = Color(0xFFFAF9F5), surface = Color.White,
    mint = Color(0xFFD0EACE), peach = Color(0xFFF5D9BC),
    primary = Color(0xFF147C31), onPrimary = Color.White,
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
    val buttonCorner = 14.dp
    val cardCorner = 20.dp
    val navigationCorner = 16.dp
    val cardPadding = 16.dp
    val cardSpacing = 12.dp
    val navigationIcon = 27.dp
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
                RoundedCornerShape(10.dp), RoundedCornerShape(GalliUiMetrics.buttonCorner),
                RoundedCornerShape(16.dp), RoundedCornerShape(GalliUiMetrics.cardCorner),
                RoundedCornerShape(24.dp),
            ),
            typography = typography.copy(
                headlineLarge = typography.headlineLarge.copy(fontWeight = FontWeight.Bold, lineHeight = 38.sp),
                headlineMedium = typography.headlineMedium.copy(fontWeight = FontWeight.Bold, lineHeight = 34.sp),
                titleLarge = typography.titleLarge.copy(fontWeight = FontWeight.Bold, lineHeight = 28.sp),
                titleMedium = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
                titleSmall = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 21.sp),
                bodyLarge = typography.bodyLarge.copy(lineHeight = 24.sp),
                bodyMedium = typography.bodyMedium.copy(lineHeight = 21.sp),
                bodySmall = typography.bodySmall.copy(lineHeight = 18.sp),
                labelLarge = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                labelMedium = typography.labelMedium.copy(lineHeight = 18.sp),
                labelSmall = typography.labelSmall.copy(lineHeight = 16.sp),
            ),
            content = content,
        )
    }
}
