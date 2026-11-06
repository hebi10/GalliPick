package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shortHomeWindowShowsPrimaryCtaAndKeepsShortcutsReachable() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 420.dp)) {
                        HomeScreen(homeState(), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("정리 시작하기").assertIsDisplayed()
        compose.onNodeWithText("중복 파일 찾기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("용량 큰 파일").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("오래된 파일").performScrollTo().assertIsDisplayed()
    }

    @Test fun homeReflowsWithTheWindowRatherThanDeviceConfiguration() {
        val width = mutableStateOf(1000.dp)
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(width.value, 760.dp)) {
                        HomeScreen(homeState(), {}, {}, {}, {})
                    }
                }
            }
        }
        val wideAction = compose.onNodeWithText("중복 파일 찾기").fetchSemanticsNode().boundsInRoot
        val wideStorage = compose.onNodeWithText("저장공간 사용량").fetchSemanticsNode().boundsInRoot
        assertTrue("A wide home should place storage beside the quick grid", wideStorage.right <= wideAction.left)

        compose.runOnIdle { width.value = 360.dp }
        val narrowAction = compose.onNodeWithText("중복 파일 찾기").fetchSemanticsNode().boundsInRoot
        val narrowStorage = compose.onNodeWithText("저장공간 사용량").fetchSemanticsNode().boundsInRoot
        assertTrue("A narrow home should show storage before the shortcut grid", narrowStorage.bottom < narrowAction.top)
    }

    @Test fun cleanupPrioritizesResultsAndKeepsEmptyCategoriesCompactAndNavigable() {
        val oldScreenshot = media(1, "Screenshot-old.jpg", MediaKind.PHOTO, "Pictures/Screenshots/", 1024)
        val recording = media(2, "Screen-recording.mp4", MediaKind.VIDEO, "Movies/ScreenRecords/", 2048)
        var opened: MediaFilter? = null
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(1000.dp, 760.dp)) {
                        CleanupScreen(AppUiState(access = MediaAccess.FULL, items = listOf(oldScreenshot, recording))) { opened = it }
                    }
                }
            }
        }
        val screenshot = compose.onNodeWithText("오래된 스크린샷 후보").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screenRecording = compose.onNodeWithText("화면 녹화 후보").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Reference categories are presented in a consistent vertical list", screenshot.top < screenRecording.top)
        val emptyCategory = compose.onNode(hasText("큰 동영상") and hasClickAction())
        val emptyBounds = emptyCategory.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The first category should remain at the beginning of the list", emptyBounds.top < screenshot.top)
        assertTrue("Even empty categories must remain compact", emptyBounds.height <= 100 * .6f)
        compose.onNodeWithText("0개 · 0 B").assertDoesNotExist()
        compose.onNodeWithText("즐겨찾기·보호 파일 제외 시", substring = true).assertDoesNotExist()
        emptyCategory.performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.VIDEO, opened?.kind)
            assertEquals(524_288_000L, opened?.minSizeBytes)
        }
    }

    @Test fun emptyCleanupAtLargeFontKeepsEveryCategoryReachableWithoutLargeActionCards() {
        var opened: MediaFilter? = null
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = 1.6f)) {
                    Surface(Modifier.requiredSize(320.dp, 400.dp)) {
                        CleanupScreen(AppUiState(access = MediaAccess.FULL)) { opened = it }
                    }
                }
            }
        }
        compose.onAllNodesWithText("파일 살펴보기").assertCountEquals(0)
        compose.onNode(hasText("화면 녹화 후보") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.VIDEO, opened?.kind)
            assertEquals("Screen", opened?.query)
        }
    }

    @Test fun settingsKeepReadableWidthInWideWindows() {
        val vm = AppViewModel(compose.activity.application)
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(1100.dp, 760.dp)) {
                        SettingsScreen(AppUiState(access = MediaAccess.FULL), vm, {}, {}, {})
                    }
                }
            }
        }
        val themeChoice = compose.onNodeWithText("기기 설정 따르기").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Settings controls should keep a readable line length", themeChoice.width <= 640 * .6f + 1f)
        compose.onNodeWithText("개인정보처리방침").performScrollTo().assertIsDisplayed()
    }

    private fun homeState() = AppUiState(access = MediaAccess.FULL, totalStorageBytes = 128L * 1024 * 1024 * 1024, freeStorageBytes = 32L * 1024 * 1024 * 1024)

    private fun media(id: Long, name: String, kind: MediaKind, folder: String, size: Long) = MediaItem(
        uri = "content://media/external/images/media/${990_000 + id}", id = id, volume = "external_primary",
        displayName = name, kind = kind, mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        relativePath = folder, sizeBytes = size, width = 100, height = 100, durationMs = 0,
        dateTakenMs = 1L, dateAddedMs = 1L, dateModifiedMs = 1L,
    )
}
