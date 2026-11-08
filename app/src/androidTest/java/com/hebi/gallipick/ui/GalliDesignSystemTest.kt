package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalliDesignSystemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun primaryButtonsAndSupportingTextMeetReadableContrastInBothPalettes() {
        for ((name, palette) in listOf("light" to LightPalette, "dark" to DarkPalette)) {
            val pairs = listOf(
                "primary button" to (palette.primary to palette.onPrimary),
                "surface text" to (palette.ink to palette.surface),
                "background text" to (palette.ink to palette.background),
                "supporting text" to (palette.muted to palette.surface),
            )
            for ((role, colors) in pairs) {
                assertTrue("$name $role contrast is below 4.5:1", contrast(colors.first, colors.second) >= 4.5f)
            }
        }
    }

    @Test fun sharedActionsRetainTouchTargetsAndSelectedNavigationAtLargeFont() {
        var primaryClicks = 0
        var refreshClicks = 0
        compose.setContent {
            GalliPickTheme(AppThemeMode.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                    Column(Modifier.requiredSize(360.dp, 440.dp)) {
                        GalliAppHeader(false) { refreshClicks++ }
                        GalliCard {
                            Text("공통 카드")
                            ActionButton("주요 작업", { primaryClicks++ }, filled = true)
                            ActionButton("비활성 작업", {}, enabled = false)
                            TextAction("취소", {})
                            IconAction("테스트 새로고침", Icons.Rounded.Refresh, onClick = { refreshClicks++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("공통 카드").assertIsDisplayed()
        compose.onNode(hasText("주요 작업") and hasClickAction())
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNode(hasText("비활성 작업") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("취소") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("테스트 새로고침")
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, primaryClicks)
            assertEquals(1, refreshClicks)
        }
    }

    @Test fun darkNavigationRetainsFourReferenceTabSemantics() {
        compose.setContent {
            GalliPickTheme(AppThemeMode.DARK) {
                AppDestinationRail(Destination.FILES, {}, Modifier.requiredSize(100.dp, 280.dp))
            }
        }
        assertEquals(listOf("홈", "파일", "정리", "설정"), Destination.entries.map { it.title })
        compose.onNode(hasText("중복") and hasClickAction()).assertDoesNotExist()
        Destination.entries.forEach { destination ->
            compose.onNode(hasText(destination.title) and hasClickAction())
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNode(hasText(Destination.FILES.title) and hasClickAction())
            .performScrollTo().assertIsSelected()
        compose.onNode(hasText(Destination.HOME.title) and hasClickAction())
            .performScrollTo().assertIsNotSelected()
    }

    @Test fun darkNavigationAndUtilityIconsAreActualGlyphsNotFlatBitmaps() {
        compose.setContent {
            GalliPickTheme(AppThemeMode.DARK) {
                Column {
                    Row {
                        Destination.entries.forEach { destination ->
                            GalliNavigationIcon(
                                destination.icon,
                                modifier = Modifier.size(36.dp).testTag("dark-nav-${destination.title}"),
                                selected = destination == Destination.SETTINGS,
                            )
                        }
                    }
                    GalliIcon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp).testTag("dark-utility-shield"),
                    )
                }
            }
        }
        val activeGlyph = 0x00E7E9EC
        val inactiveGlyph = 0x00B1B6BD
        for (destination in Destination.entries) {
            val node = compose.onNodeWithTag("dark-nav-${destination.title}").assertIsDisplayed()
            val bitmap = node.captureToImage().asAndroidBitmap()
            try {
                val expected = if (destination == Destination.SETTINGS) activeGlyph else inactiveGlyph
                val colored = countExactPixels(bitmap, expected)
                val total = bitmap.width * bitmap.height
                assertTrue("${destination.title} dark nav glyph is invisible", colored > 12)
                assertTrue("${destination.title} dark nav glyph became an opaque tile", colored < total * .72)
            } finally {
                bitmap.recycle()
            }
        }
        val shield = compose.onNodeWithTag("dark-utility-shield").captureToImage().asAndroidBitmap()
        try {
            assertTrue("Dark utility icon is invisible", countExactPixels(shield, 0x00F5F6F7) > 12)
            assertTrue("Dark utility icon became an opaque tile", countExactPixels(shield, 0x00F5F6F7) < shield.width * shield.height * .72)
        } finally {
            shield.recycle()
        }
    }

    @Test fun monoCatBrandMarkUsesThemeInkInBothPalettesWithoutAnOpaqueTile() {
        compose.setContent {
            Row {
                GalliPickTheme(AppThemeMode.LIGHT) {
                    GalliBrandMark(Modifier.size(48.dp).testTag("mono-cat-light"))
                }
                GalliPickTheme(AppThemeMode.DARK) {
                    GalliBrandMark(Modifier.size(48.dp).testTag("mono-cat-dark"))
                }
            }
        }
        for ((tag, expected) in listOf(
            "mono-cat-light" to 0x001C1E21,
            "mono-cat-dark" to 0x00F5F6F7,
        )) {
            val image = compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().asAndroidBitmap()
            try {
                val colored = countExactPixels(image, expected)
                assertTrue("$tag brand glyph must be visible", colored > 12)
                assertTrue("$tag brand glyph must remain an outline", colored < image.width * image.height * .72)
            } finally {
                image.recycle()
            }
        }
    }

    @Test fun referenceTypographyBrandMarkAndOutlineTabIconsRender() {
        lateinit var type: Typography
        compose.setContent {
            GalliPickTheme(AppThemeMode.LIGHT) {
                type = MaterialTheme.typography
                Column {
                    GalliBrandMark(Modifier.size(40.dp).testTag("reference-brand-mark"))
                    Row {
                        Destination.entries.forEach { destination ->
                            GalliNavigationIcon(
                                destination.icon,
                                Modifier.size(36.dp).testTag("reference-tab-${destination.title}"),
                                selected = destination == Destination.HOME,
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("reference-brand-mark").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(24.sp, type.titleLarge.fontSize)
            assertEquals(20.sp, type.titleMedium.fontSize)
            assertEquals(16.sp, type.bodyLarge.fontSize)
            assertEquals(14.sp, type.bodyMedium.fontSize)
            assertEquals(12.sp, type.labelMedium.fontSize)
        }
        for (destination in Destination.entries) {
            val bitmap = compose.onNodeWithTag("reference-tab-${destination.title}")
                .assertIsDisplayed().captureToImage().asAndroidBitmap()
            try {
                val expected = if (destination == Destination.HOME) 0x0023262B else 0x005D6269
                val painted = countExactPixels(bitmap, expected)
                assertTrue("${destination.title} light icon not visible", painted > 12)
                assertTrue("${destination.title} light icon is a flat tile", painted < bitmap.width * bitmap.height * .72)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun countExactPixels(bitmap: android.graphics.Bitmap, colorRgb: Int): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if ((bitmap.getPixel(x, y) and 0x00FFFFFF) == colorRgb) count++
            }
        }
        return count
    }

    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + .05f) / (dark + .05f)
    }
}
