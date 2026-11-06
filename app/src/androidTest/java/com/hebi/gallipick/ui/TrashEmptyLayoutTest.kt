package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashEmptyLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortLandscapeShowsEmptyTrashTitleWithoutHidingBack() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                    Box(Modifier.requiredSize(600.dp, 300.dp)) {
                        TrashScreen(emptyList(), {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("휴지통이 비어 있습니다").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").assertIsDisplayed()
    }

    @Test fun largeTextEmptyTrashHasVisibleTextAndBackAction() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                    Box(Modifier.requiredSize(320.dp, 460.dp)) {
                        TrashScreen(emptyList(), {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("휴지통이 비어 있습니다").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").assertIsDisplayed()
    }
}
