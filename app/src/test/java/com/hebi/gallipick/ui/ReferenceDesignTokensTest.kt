package com.hebi.gallipick.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceDesignTokensTest {
    @Test fun lightPaletteMatchesMonoCatNeutralDesign() {
        assertEquals(Color(0xFF23262B), LightPalette.primary)
        assertEquals(Color.White, LightPalette.onPrimary)
        assertEquals(Color(0xFF1C1E21), LightPalette.ink)
        assertEquals(Color(0xFF5D6269), LightPalette.muted)
        assertEquals(Color(0xFFDDE1E5), LightPalette.line)
        assertEquals(Color(0xFFF7F8FA), LightPalette.background)
        assertEquals(Color.White, LightPalette.surface)
        assertEquals(Color(0xFFECEEF1), LightPalette.panel)
        assertEquals(Color(0xFFECEEF1), LightPalette.selectedContainer)
        assertEquals(Color(0xFFE8EAED), LightPalette.iconContainer)
    }

    @Test fun darkPaletteMatchesMonoCatNeutralDesign() {
        assertEquals(Color(0xFFE7E9EC), DarkPalette.primary)
        assertEquals(Color(0xFF17191C), DarkPalette.onPrimary)
        assertEquals(Color(0xFFF5F6F7), DarkPalette.ink)
        assertEquals(Color(0xFFB1B6BD), DarkPalette.muted)
        assertEquals(Color(0xFF3B4046), DarkPalette.line)
        assertEquals(Color(0xFF111315), DarkPalette.background)
        assertEquals(Color(0xFF1C1F22), DarkPalette.surface)
        assertEquals(Color(0xFF292D31), DarkPalette.panel)
        assertEquals(Color(0xFF292D31), DarkPalette.selectedContainer)
        assertEquals(Color(0xFF34383D), DarkPalette.iconContainer)
    }

    @Test fun commonDimensionsKeepReferenceSpacingAndAccessibleTargets() {
        assertEquals(48.dp, GalliUiMetrics.touchTarget)
        assertEquals(56.dp, GalliUiMetrics.headerHeight)
        assertEquals(12.dp, GalliUiMetrics.buttonCorner)
        assertEquals(16.dp, GalliUiMetrics.cardCorner)
        assertEquals(14.dp, GalliUiMetrics.navigationCorner)
        assertEquals(14.dp, GalliUiMetrics.cardPadding)
        assertEquals(10.dp, GalliUiMetrics.cardSpacing)
        assertEquals(24.dp, GalliUiMetrics.navigationIcon)
    }
}
