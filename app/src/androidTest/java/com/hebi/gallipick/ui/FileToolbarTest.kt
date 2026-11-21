package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*
import com.hebi.gallipick.data.cloud.GallerySourceFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileToolbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactToolbarExposesSearchAndFilterRowWithoutHidingFiles() {
        val all = (0..40).map { media(it.toLong()) }
        val searchVisible = mutableStateOf(false)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                Box(Modifier.requiredSize(320.dp, 280.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = all, filtered = all),
                        false, {}, {}, {}, actions(), showSearch = searchVisible.value)
                }
            }
        } }
        val toolbar = compose.onNodeWithContentDescription("파일 도구 모음").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("미디어 유형 선택").assertExists()
        compose.onNodeWithTag("files-kind-filter").assertIsDisplayed()
        assertTrue("Sort and advanced filter actions should stay on one compact row", toolbar.height <= 56f)
        compose.onNodeWithContentDescription("파일 검색").assertDoesNotExist()
        val input = compose.onNodeWithContentDescription("파일 이름 또는 폴더 검색")
        input.assertDoesNotExist()
        compose.onNodeWithText("파일명·폴더").assertIsDisplayed()
        compose.onNodeWithTag("files-search-scope").performClick()
        compose.onNodeWithTag("files-search-scope-files").assertIsSelected()
        compose.onNodeWithTag("files-search-scope-ocr").assertIsNotSelected()
        compose.onNodeWithTag("files-search-scope-files").performClick()
        compose.onNodeWithText("유형: 전체").assertIsDisplayed()
        compose.onNodeWithTag("files-kind-filter").performClick()
        listOf("files-kind-all", "files-kind-photo", "files-kind-video")
            .forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithTag("files-kind-all").performClick()
        compose.onNodeWithContentDescription("파일 조건 선택").assertIsDisplayed()
        compose.onNodeWithContentDescription("정렬 기준 선택").assertExists()
        compose.onNodeWithContentDescription("파일 추가 작업").performClick()
        compose.onNodeWithText("목록 보기").assertIsDisplayed()
        compose.onNodeWithText("조건 초기화").assertIsDisplayed()
        compose.onNodeWithText("목록 보기").performClick()
        compose.runOnIdle { searchVisible.value = true }
        input.performScrollTo().assertIsDisplayed()
        val grid = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode().boundsInRoot
        assertTrue("The expanded search must leave usable media space", grid.height >= 60f)
        input.performTouchInput { swipeUp() }
        compose.onNodeWithTag("files-kind-filter").assertIsDisplayed()
    }

    @Test fun compactSearchScopeShowsCurrentModeAndSelectedMenuItem() {
        val mode = mutableStateOf(FileSearchMode.FILES)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.7f, fontScale = 2f)) {
                Box(Modifier.requiredSize(320.dp, 420.dp)) {
                    FileToolbar(MediaFilter(), emptyList(), {}, {}, {}, {},
                        searchMode = mode.value, onSearchModeChange = { mode.value = it },
                        showSearch = false)
                }
            }
        } }
        compose.onNodeWithTag("files-search-scope").performClick()
        compose.onNodeWithTag("files-search-scope-ocr").performClick()
        compose.onNodeWithText("사진 속 글자").assertIsDisplayed()
        compose.onNodeWithTag("files-search-scope").performClick()
        compose.onNodeWithTag("files-search-scope-ocr").assertIsSelected()
        compose.onNodeWithTag("files-search-scope-files").assertIsNotSelected()
    }

    @Test fun openingSearchFocusesInputAndClosingKeepsAppliedConditionsVisible() {
        val searchVisible = mutableStateOf(false)
        val filter = mutableStateOf(MediaFilter(minSizeBytes = 104_857_600L))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.7f)) {
                Box(Modifier.requiredSize(360.dp, 420.dp)) {
                    FileToolbar(filter.value, emptyList(), { filter.value = it }, {}, {}, {},
                        showSearch = searchVisible.value)
                }
            }
        } }
        val input = compose.onNodeWithContentDescription("파일 이름 또는 폴더 검색")

        input.assertDoesNotExist()
        compose.runOnIdle { searchVisible.value = true }
        input.assertIsFocused().performTextInput("travel")
        compose.runOnIdle { searchVisible.value = false }
        input.assertDoesNotExist()
        compose.onNode(hasText("travel", substring = true) and hasText("100 MB 이상", substring = true))
            .assertIsDisplayed()

        compose.onNodeWithText("조건 지우기").performClick()
        compose.runOnIdle { assertEquals(MediaFilter(), filter.value) }
    }

    @Test fun moreMenuEnablesIndividualSelectionWithoutSelectingExistingFiles() {
        val all = listOf(media(1), media(2))
        var selectionActions = 0
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.7f)) {
                Box(Modifier.requiredSize(360.dp, 650.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = all, filtered = all),
                        false, {}, {}, {}, actions().copy(selectAll = { selectionActions++ },
                            toggleSelection = { selectionActions++ }))
                }
            }
        } }
        val indicators = compose.onAllNodesWithTag("grid-selection-indicator", useUnmergedTree = true)

        indicators.assertCountEquals(0)
        compose.onNodeWithContentDescription("파일 추가 작업").performClick()
        compose.onNodeWithText("파일 선택").performClick()
        indicators.assertCountEquals(all.size)
        compose.runOnIdle { assertEquals(0, selectionActions) }
    }

    @Test fun mediaTypeDropdownPreservesFolderAndSizeConditions() {
        val filter = mutableStateOf(MediaFilter(folder = "DCIM/Camera/", minSizeBytes = 1024))
        compose.setContent {
            GalliPickTheme {
                Box(Modifier.requiredSize(420.dp, 200.dp)) {
                    FileToolbar(filter.value, emptyList(), { filter.value = it }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithContentDescription("파일 이름 또는 폴더 검색").performTextInput("trip")
        compose.runOnIdle {
            assertEquals("trip", filter.value.query)
            assertEquals("DCIM/Camera/", filter.value.folder)
        }
        compose.onNodeWithTag("files-kind-filter").performClick()
        compose.onNodeWithTag("files-kind-video").performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.VIDEO, filter.value.kind)
            assertEquals(1024L, filter.value.minSizeBytes)
        }
        compose.onNodeWithTag("files-kind-filter").performClick()
        compose.onNodeWithTag("files-kind-all").performClick()
        compose.runOnIdle { assertEquals(null, filter.value.kind) }
    }

    @Test fun sourceDropdownKeepsMediaTypeAndShowsTheCurrentLocation() {
        val filter = mutableStateOf(MediaFilter(kind = MediaKind.PHOTO))
        val source = mutableStateOf(GallerySourceFilter.ALL)
        compose.setContent {
            GalliPickTheme {
                Box(Modifier.requiredSize(360.dp, 240.dp)) {
                    FileToolbar(
                        filter.value, emptyList(), { filter.value = it }, {}, {}, {},
                        sourceFilter = source.value, showSourceFilter = true,
                        onSourceFilterChange = { source.value = it },
                    )
                }
            }
        }
        compose.onNodeWithTag("files-source-filter").performScrollTo().performClick()
        compose.onNodeWithTag("files-source-cloud").performClick()
        compose.runOnIdle {
            assertEquals(GallerySourceFilter.CLOUD, source.value)
            assertEquals(MediaKind.PHOTO, filter.value.kind)
        }
        compose.onNodeWithText("위치: 클라우드").assertExists()
        compose.onNodeWithTag("files-source-filter").performScrollTo().performClick()
        compose.onNodeWithTag("files-source-all").performClick()
        compose.runOnIdle { assertEquals(GallerySourceFilter.ALL, source.value) }
    }

    @Test fun sortDropdownChangesRealOrderAndPreservesCompatibleConditions() {
        val small = media(1).copy(displayName = "trip-small.mp4", sizeBytes = 2_000, durationMs = 30_000)
        val large = media(2).copy(displayName = "trip-large.mp4", sizeBytes = 8_000, durationMs = 600_000)
        val all = listOf(small, large, media(3).copy(displayName = "excluded.mp4"))
        val filter = MediaFilter(query = "trip", folder = small.relativePath, minSizeBytes = 1024)
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = all, filter = filter, filtered = MediaQuery.apply(all, filter)))
        compose.setContent { GalliPickTheme {
            FilesScreenContent(state.value, false, {}, {}, {}, actions { next ->
                state.value = state.value.copy(filter = next, filtered = MediaQuery.apply(all, next))
            })
        } }
        compose.onNodeWithContentDescription("정렬 기준 선택").performScrollTo().performClick()
        compose.onNodeWithText("용량 큰 순").performClick()
        compose.runOnIdle {
            assertEquals(filter.copy(sortField = SortField.SIZE), state.value.filter)
            assertEquals(listOf(large, small), state.value.filtered)
        }
        compose.onNodeWithContentDescription("정렬 기준 선택").performScrollTo().performClick()
        compose.onNodeWithText("영상 짧은 순").performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.VIDEO, state.value.filter.kind)
            assertEquals("trip", state.value.filter.query)
            assertEquals(listOf(small, large), state.value.filtered)
        }
        compose.onNodeWithText(small.displayName).assertIsDisplayed()
        compose.onNodeWithText(large.displayName).assertIsDisplayed()
    }

    private fun actions(change: (MediaFilter) -> Unit = {}) = FilesScreenActions(change, {}, {}, {}, {}, {}, { _, _ -> }, {})
    private fun media(id: Long) = MediaItem("content://fixture/$id", id, "external_primary", "file-$id.mp4", MediaKind.VIDEO,
        "video/mp4", "DCIM/Camera/", 1024, 1920, 1080, 60_000, 100, 100, 100)
}
