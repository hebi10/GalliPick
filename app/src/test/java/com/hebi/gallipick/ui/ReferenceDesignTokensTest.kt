package com.hebi.gallipick.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceDesignTokensTest {
    @Test fun lightPaletteMatchesApprovedDesignGuide() {
        assertEquals(Color(0xFF1F6F54), LightPalette.primary)
        assertEquals(Color(0xFFE6F4ED), LightPalette.panel)
        assertEquals(Color(0xFFA4D4B4), LightPalette.mint)
        assertEquals(Color(0xFFFFE7A3), LightPalette.peach)
        assertEquals(Color(0xFFFFF9EF), LightPalette.background)
        assertEquals(Color(0xFFFFF3E2), LightPalette.surface)
        assertEquals(Color(0xFF1F2937), LightPalette.ink)
        assertEquals(Color.White, LightPalette.onPrimary)
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
