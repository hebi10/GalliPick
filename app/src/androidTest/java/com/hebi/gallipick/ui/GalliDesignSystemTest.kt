package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun darkNavigationRetainsFiveReachableTabSemantics() {
        compose.setContent {
            GalliPickTheme(AppThemeMode.DARK) {
                AppDestinationRail(Destination.FILES, {}, Modifier.requiredSize(100.dp, 700.dp))
            }
        }
        Destination.entries.forEach { destination ->
            compose.onNode(hasText(destination.title) and hasClickAction())
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNode(hasText(Destination.FILES.title) and hasClickAction()).assertIsSelected()
        compose.onNode(hasText(Destination.HOME.title) and hasClickAction()).assertIsNotSelected()
    }

    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + .05f) / (dark + .05f)
    }
}
