package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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

    @Test fun monoCatBrandMarkShowsTheApprovedSmilingFaceInLightAndDark() {
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
        for (tag in listOf("mono-cat-light", "mono-cat-dark")) {
            val bitmap = compose.onNodeWithTag(tag).assertIsDisplayed()
                .captureToImage().asAndroidBitmap()
            try {
                var darkStrokes = 0
                var lightFace = 0
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        val red = android.graphics.Color.red(pixel)
                        val green = android.graphics.Color.green(pixel)
                        val blue = android.graphics.Color.blue(pixel)
                        if (red < 90 && green < 90 && blue < 90) darkStrokes++
                        if (red > 240 && green > 235 && blue > 220) lightFace++
                    }
                }
                assertTrue("$tag cat face must retain dark outline and details", darkStrokes > 24)
                assertTrue("$tag cat face must retain a readable pale center", lightFace > 24)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun brandFaceAssetPreservesTransparentCornersAndSmallIconClarity() {
        val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.resources
        val bitmap = android.graphics.BitmapFactory.decodeResource(
            resources, com.hebi.gallipick.R.drawable.gp_brand_cat_face,
        )
        try {
            assertEquals(192, bitmap.width)
            assertEquals(192, bitmap.height)
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
            assertTrue(
                "Cat face center must be opaque",
                android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun actionAndNavigationIconsShareTheOutlinedFamily() {
        val actions = listOf(
            androidx.compose.material.icons.Icons.Outlined.Search,
            androidx.compose.material.icons.Icons.Outlined.Tune,
            androidx.compose.material.icons.Icons.Outlined.Check,
            androidx.compose.material.icons.Icons.Outlined.Star,
            androidx.compose.material.icons.Icons.Outlined.Lock,
            androidx.compose.material.icons.Icons.Outlined.Shield,
            androidx.compose.material.icons.Icons.Outlined.DeleteOutline,
            androidx.compose.material.icons.Icons.Outlined.RestoreFromTrash,
            androidx.compose.material.icons.Icons.Outlined.Folder,
            androidx.compose.material.icons.Icons.Outlined.CalendarMonth,
            androidx.compose.material.icons.Icons.Outlined.PlayArrow,
            androidx.compose.material.icons.Icons.Outlined.Pause,
            androidx.compose.material.icons.Icons.Outlined.Close,
        )
        val destinations = Destination.entries.map { it.icon }
        (actions + destinations).forEach { icon ->
            assertTrue("${icon.name} must be an Outlined material icon", icon.name.startsWith("Outlined."))
        }
        assertEquals(4, destinations.distinct().size)
        assertEquals(listOf("홈", "파일", "정리", "설정"), Destination.entries.map { it.title })
    }

    @Test fun highRiskIconsStayVisibleAndDistinctInDarkMode() {
        compose.setContent {
            GalliPickTheme(AppThemeMode.DARK) {
                Row {
                    GalliIcon(
                        androidx.compose.material.icons.Icons.Outlined.Lock, null,
                        Modifier.size(36.dp).testTag("stage10c-lock"),
                    )
                    GalliIcon(
                        androidx.compose.material.icons.Icons.Outlined.DeleteOutline, null,
                        Modifier.size(36.dp).testTag("stage10c-trash"),
                    )
                    GalliIcon(
                        androidx.compose.material.icons.Icons.Outlined.RestoreFromTrash, null,
                        Modifier.size(36.dp).testTag("stage10c-restore"),
                    )
                }
            }
        }
        val masks = mutableListOf<Set<Int>>()
        for (tag in listOf("stage10c-lock", "stage10c-trash", "stage10c-restore")) {
            val bitmap = compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().asAndroidBitmap()
            try {
                val active = mutableSetOf<Int>()
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        if ((bitmap.getPixel(x, y) and 0x00FFFFFF) == 0x00F5F6F7) {
                            active.add(y * bitmap.width + x)
                        }
                    }
                }
                assertTrue("$tag must remain visible", active.size > 12)
                assertTrue("$tag must not become an opaque tile", active.size < bitmap.width * bitmap.height * .72)
                masks.add(active)
            } finally {
                bitmap.recycle()
            }
        }
        assertTrue("Lock and trash must have distinct glyphs", masks[0] != masks[1])
        assertTrue("Trash and restore must have distinct glyphs", masks[1] != masks[2])
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
