package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneLandscapeShowsEveryDestinationWithFullTouchTargets() {
        var opened: Destination? = null
        showRail(height = 280.dp, onNavigate = { opened = it })

        val rail = compose.onNodeWithTag("destination-rail").fetchSemanticsNode().boundsInRoot
        Destination.entries.forEach { destination ->
            val tab = tab(destination).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val bounds = tab.fetchSemanticsNode().boundsInRoot
            assertTrue("${destination.title} must fit completely inside the short rail", bounds.top >= rail.top && bounds.bottom <= rail.bottom)
        }
        tab(Destination.SETTINGS).performClick()
        compose.runOnIdle { assertEquals(Destination.SETTINGS, opened) }
    }

    @Test fun veryShortRailAtLargeFontLetsSettingsScrollIntoViewAndOpen() {
        var opened: Destination? = null
        showRail(height = 180.dp, fontScale = 1.6f, onNavigate = { opened = it })

        tab(Destination.SETTINGS).assertIsNotDisplayed().performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(Destination.SETTINGS, opened) }
        tab(Destination.HOME).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun tallRailRetainsComfortableFullSizeDestinations() {
        var opened: Destination? = null
        showRail(height = 700.dp, onNavigate = { opened = it })

        Destination.entries.forEach { destination ->
            tab(destination).assertIsDisplayed().assertHeightIsAtLeast(60.dp)
        }
        tab(Destination.HOME).assertIsSelected()
        tab(Destination.SETTINGS).performClick()
        compose.runOnIdle { assertEquals(Destination.SETTINGS, opened) }
    }

    private fun tab(destination: Destination) = compose.onNode(hasText(destination.title) and hasClickAction())

    private fun showRail(height: Dp, fontScale: Float = 1f, onNavigate: (Destination) -> Unit) {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale)) {
                    AppDestinationRail(
                        current = Destination.HOME,
                        onNavigate = onNavigate,
                        modifier = Modifier.requiredSize(100.dp, height).testTag("destination-rail"),
                    )
                }
            }
        }
    }
}
