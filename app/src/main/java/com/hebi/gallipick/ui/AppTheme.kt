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
    val background: Color, val surface: Color, val selectedContainer: Color, val iconContainer: Color,
    val primary: Color, val onPrimary: Color,
)

// Stage 10-A: neutral colors; role names describe UI behavior, not former hues.
internal val LightPalette = GalliPalette(
    ink = Color(0xFF1C1E21), muted = Color(0xFF5D6269), line = Color(0xFFDDE1E5),
    panel = Color(0xFFECEEF1), background = Color(0xFFF7F8FA), surface = Color.White,
    selectedContainer = Color(0xFFECEEF1), iconContainer = Color(0xFFE8EAED),
    primary = Color(0xFF23262B), onPrimary = Color.White,
)

internal val DarkPalette = GalliPalette(
    ink = Color(0xFFF5F6F7), muted = Color(0xFFB1B6BD), line = Color(0xFF3B4046),
    panel = Color(0xFF292D31), background = Color(0xFF111315), surface = Color(0xFF1C1F22),
    selectedContainer = Color(0xFF292D31), iconContainer = Color(0xFF34383D),
    primary = Color(0xFFE7E9EC), onPrimary = Color(0xFF17191C),
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
internal val SelectedContainer: Color @Composable get() = LocalGalliPalette.current.selectedContainer
internal val IconContainer: Color @Composable get() = LocalGalliPalette.current.iconContainer
internal val Primary: Color @Composable get() = LocalGalliPalette.current.primary
internal val OnPrimary: Color @Composable get() = LocalGalliPalette.current.onPrimary

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GalliPickTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = if (themeMode.isDark(isSystemInDarkTheme())) DarkPalette else LightPalette
    val colors = if (palette == DarkPalette) darkColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        secondary = palette.muted, onSecondary = palette.background,
        tertiary = palette.primary, onTertiary = palette.onPrimary,
        background = palette.background, onBackground = palette.ink,
        primaryContainer = palette.selectedContainer, onPrimaryContainer = palette.ink,
        secondaryContainer = palette.panel, onSecondaryContainer = palette.ink,
        tertiaryContainer = palette.iconContainer, onTertiaryContainer = palette.ink,
        surface = palette.surface, onSurface = palette.ink,
        surfaceVariant = palette.panel, onSurfaceVariant = palette.muted,
        inverseSurface = palette.ink, inverseOnSurface = palette.background, inversePrimary = LightPalette.primary,
        surfaceTint = Color.Transparent, surfaceBright = palette.iconContainer, surfaceDim = palette.background,
        surfaceContainer = palette.panel, surfaceContainerHigh = palette.selectedContainer,
        surfaceContainerHighest = palette.iconContainer,
        surfaceContainerLow = palette.surface, surfaceContainerLowest = palette.background,
        outline = palette.muted, outlineVariant = palette.line, scrim = Color.Black,
        error = Color(0xFFFFB6A5), onError = Color(0xFF552013),
        errorContainer = Color(0xFF64362C), onErrorContainer = Color(0xFFFFDAD0),
    ) else lightColorScheme(
        primary = palette.primary, onPrimary = palette.onPrimary,
        secondary = palette.muted, onSecondary = palette.surface,
        tertiary = palette.primary, onTertiary = palette.onPrimary,
        background = palette.background, onBackground = palette.ink,
        primaryContainer = palette.selectedContainer, onPrimaryContainer = palette.ink,
        secondaryContainer = palette.panel, onSecondaryContainer = palette.ink,
        tertiaryContainer = palette.iconContainer, onTertiaryContainer = palette.ink,
        surface = palette.surface, onSurface = palette.ink,
        surfaceVariant = palette.panel, onSurfaceVariant = palette.muted,
        inverseSurface = palette.ink, inverseOnSurface = palette.background, inversePrimary = DarkPalette.primary,
        surfaceTint = Color.Transparent, surfaceBright = palette.surface, surfaceDim = palette.line,
        surfaceContainer = palette.panel, surfaceContainerHigh = palette.selectedContainer,
        surfaceContainerHighest = palette.iconContainer,
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
