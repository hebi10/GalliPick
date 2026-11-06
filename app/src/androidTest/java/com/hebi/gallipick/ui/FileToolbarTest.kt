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
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                Box(Modifier.requiredSize(320.dp, 280.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = all, filtered = all), false, {}, {}, {}, actions())
                }
            }
        } }
        val toolbar = compose.onNodeWithContentDescription("파일 도구 모음").fetchSemanticsNode().boundsInRoot
        assertTrue("Type chips and filters must remain on one scrollable row", toolbar.height <= 56f)
        val grid = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode().boundsInRoot
        assertTrue("The search and chip header must leave usable media space", grid.height >= 60f)
        compose.onNodeWithContentDescription("파일 검색").assertIsDisplayed()
        compose.onNodeWithContentDescription("파일 이름 또는 폴더 검색").assertIsDisplayed()
        listOf("파일 유형: 전체", "파일 유형: 사진", "파일 유형: 동영상", "파일 조건 선택", "정렬 기준 선택")
            .forEach { compose.onNodeWithContentDescription(it).assertExists() }
        // The constrained header can scroll away when the 280dp viewport's body moves.
        // Verify that the control remains in the tree. The sort workflow is checked
        // by sortDropdownChangesRealOrderAndPreservesCompatibleConditions below.
        compose.onNodeWithContentDescription("정렬 기준 선택").assertExists()
    }

    @Test fun visibleSearchAndTypeChipsKeepTheFolderAndSizeConditions() {
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
        compose.onNodeWithContentDescription("파일 유형: 동영상").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.VIDEO, filter.value.kind)
            assertEquals(1024L, filter.value.minSizeBytes)
        }
        compose.onNodeWithContentDescription("파일 유형: 전체").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, filter.value.kind) }
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
        compose.onNodeWithContentDescription("정렬 기준 선택").performClick()
        compose.onNodeWithText("용량 큰 순").performClick()
        compose.runOnIdle {
            assertEquals(filter.copy(sortField = SortField.SIZE), state.value.filter)
            assertEquals(listOf(large, small), state.value.filtered)
        }
        compose.onNodeWithContentDescription("정렬 기준 선택").performClick()
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
