package com.hebi.gallipick.ui

import android.graphics.drawable.VectorDrawable
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
import com.hebi.gallipick.R
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

    @Test fun homeShortcutAssetsAreVectorDrawablesAndOldFilesFilterIsPreserved() {
        val assets = listOf(
            R.drawable.gp_home_photo,
            R.drawable.gp_home_duplicates,
            R.drawable.gp_home_large_file,
            R.drawable.gp_home_old_files,
            R.drawable.gp_brand_cat,
        )
        assertEquals(assets.size, assets.distinct().size)
        assets.forEach { id ->
            val drawable = requireNotNull(compose.activity.resources.getDrawable(id, compose.activity.theme))
            assertTrue("10-B icons must remain resolution-independent vectors", drawable is VectorDrawable)
            assertTrue("Icon must have intrinsic dimensions", drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        }

        var applied: MediaFilter? = null
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                    HomeScreen(homeState(), {}, { filter -> applied = filter }, {}, {})
                }
            }
        } }
        compose.onNode(hasText("오래된 파일") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle {
            val filter = requireNotNull(applied)
            assertEquals(1L, filter.fromDateMs)
            assertTrue(filter.toDateMs != null && filter.toDateMs!! > 0L)
        }
    }

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

    @Test fun compactHomeAtLargeFontKeepsHeroAndFourShortcutsOperable() {
        var cleanupOpens = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = 1.6f)) {
                    Surface(Modifier.requiredSize(320.dp, 540.dp)) {
                        HomeScreen(homeState(), {}, {}, {}, { cleanupOpens++ })
                    }
                }
            }
        }
        compose.onNodeWithText("사진과 영상을", substring = true).assertIsDisplayed()
        compose.onNode(hasText("정리 시작하기") and hasClickAction())
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        for (title in listOf("사진·영상 정리", "중복 파일 찾기", "용량 큰 파일", "오래된 파일")) {
            compose.onNode(hasText(title) and hasClickAction())
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.runOnIdle { assertEquals(1, cleanupOpens) }
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

    @Test fun cleanupOpensWithOnlyRealActionCardsAboveCategories() {
        var duplicateOpens = 0
        var opened: MediaFilter? = null
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = 1.6f)) {
                    Surface(Modifier.requiredSize(320.dp, 540.dp)) {
                        CleanupScreen(
                            state = AppUiState(access = MediaAccess.FULL),
                            onOpenDuplicates = { duplicateOpens++ },
                            browse = { opened = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("정리할 파일을 확인해 보세요").assertDoesNotExist()
        compose.onNodeWithText("직접 선택 가능한 후보", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("중복 파일 검사 열기")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, duplicateOpens) }
        compose.onNode(hasText("큰 동영상") and hasClickAction())
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(MediaKind.VIDEO, opened?.kind) }
    }

    @Test fun cleanupStage9aUsesRealNavigationOnlyAndPreservesExistingActions() {
        var duplicateOpens = 0
        var folderOpens = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = 1.6f)) {
                    Surface(Modifier.requiredSize(320.dp, 540.dp)) {
                        CleanupScreen(
                            state = AppUiState(access = MediaAccess.FULL),
                            onOpenDuplicates = { duplicateOpens++ },
                            onOpenFolderStorage = { folderOpens++ },
                            browse = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("정리").assertExists()
        compose.onNodeWithText("사진 비교").assertExists()
        compose.onNodeWithText("종류별 정리").assertExists()
        compose.onNodeWithText("비슷한 사진 찾기").assertDoesNotExist()
        compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("폴더별 용량 분석 열기").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, duplicateOpens)
            assertEquals(1, folderOpens)
        }
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
        val screenshot = compose.onNodeWithText("오래된 스크린샷 후보").fetchSemanticsNode().boundsInRoot
        val screenRecording = compose.onNodeWithText("화면 녹화 후보").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference categories are presented in a consistent vertical list", screenshot.top < screenRecording.top)
        val emptyCategory = compose.onNode(hasText("큰 동영상") and hasClickAction())
        val emptyBounds = emptyCategory.fetchSemanticsNode().boundsInRoot
        assertTrue("The first category should remain at the beginning of the list", emptyBounds.top < screenshot.top)
        assertTrue("Even empty categories must remain compact", emptyBounds.height <= 100 * .6f)
        compose.onNodeWithText("0개 · 0 B").assertDoesNotExist()
        compose.onNodeWithText("즐겨찾기·보호 파일 제외 시", substring = true).assertDoesNotExist()
        emptyCategory.performScrollTo().performClick()
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

    @Test fun cleanupOldDownloadsAndLargePhotosUseIndependentFilters() {
        val oldDownload = media(3, "photo.jpg", MediaKind.PHOTO, "Download/", 12L * 1024 * 1024)
            .copy(dateTakenMs = System.currentTimeMillis(), dateAddedMs = 1L)
        var opened: MediaFilter? = null
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 700.dp)) {
                        CleanupScreen(AppUiState(access = MediaAccess.FULL, items = listOf(oldDownload))) { opened = it }
                    }
                }
            }
        }
        compose.onNode(hasText("오래된 다운로드 파일") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(true, opened?.downloadsOnly)
            assertEquals(true, opened?.groupByDate)
            assertEquals(com.hebi.gallipick.domain.DateField.ADDED, opened?.dateField)
            assertEquals(1L, opened?.fromDateMs)
        }
        compose.onNode(hasText("용량 큰 사진") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.PHOTO, opened?.kind)
            assertEquals(10L * 1024 * 1024, opened?.minSizeBytes)
            assertEquals(com.hebi.gallipick.domain.SortField.SIZE, opened?.sortField)
            assertEquals(false, opened?.downloadsOnly)
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
