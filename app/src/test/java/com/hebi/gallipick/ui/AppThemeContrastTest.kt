package com.hebi.gallipick.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeContrastTest {
    @Test fun textAndActionColorsRemainReadableInBothThemes() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            for (surface in listOf(palette.background, palette.surface, palette.panel)) {
                assertContrast(palette.ink, surface)
                assertContrast(palette.muted, surface)
            }
            assertContrast(palette.ink, palette.mint)
            assertContrast(palette.ink, palette.peach)
            assertContrast(palette.onPrimary, palette.primary)
        }
    }

    private fun assertContrast(foreground: Color, background: Color) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        assertTrue("Text contrast is below 4.5:1", (lighter + .05f) / (darker + .05f) >= 4.5f)
    }
}
