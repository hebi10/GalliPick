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
    ink = Color(0xFF25483F), muted = Color(0xFF597267), line = Color(0xFFDCE6DF),
    panel = Color(0xFFEEF4EE), background = Color(0xFFFAF9F5), surface = Color.White,
    mint = Color(0xFFCDE6D8), peach = Color(0xFFF5D6BD),
    primary = Color(0xFF25483F), onPrimary = Color.White,
)

internal val DarkPalette = GalliPalette(
    ink = Color(0xFFE4F0E8), muted = Color(0xFFB1C7BB), line = Color(0xFF3B5147),
    panel = Color(0xFF22372F), background = Color(0xFF101A17), surface = Color(0xFF192820),
    mint = Color(0xFF315E49), peach = Color(0xFF654936),
    primary = Color(0xFFB2DAC4), onPrimary = Color(0xFF173C2E),
)

private val LocalGalliPalette = staticCompositionLocalOf { LightPalette }
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
    CompositionLocalProvider(LocalGalliPalette provides palette, LocalRippleConfiguration provides null) {
        MaterialTheme(
            colorScheme = colors,
            shapes = Shapes(RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(18.dp), RoundedCornerShape(24.dp), RoundedCornerShape(28.dp)),
            typography = typography.copy(
                headlineLarge = typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                headlineMedium = typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                titleLarge = typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                titleMedium = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                bodyLarge = typography.bodyLarge.copy(lineHeight = 25.sp),
                bodyMedium = typography.bodyMedium.copy(lineHeight = 22.sp),
            ),
            content = content,
        )
    }
}
