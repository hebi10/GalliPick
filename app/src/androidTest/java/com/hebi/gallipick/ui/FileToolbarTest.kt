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

    @Test fun narrowLargeTextToolbarRemainsOneRowAndLeavesRoomForFiles() {
        val all = (0..40).map { media(it.toLong()) }
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                Box(Modifier.requiredSize(320.dp, 280.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = all, filtered = all), false, {}, {}, {}, actions())
                }
            }
        } }
        val toolbar = compose.onNodeWithContentDescription("파일 도구 모음").fetchSemanticsNode().boundsInRoot
        assertTrue("The toolbar must fit in a single row", toolbar.height <= 56f)
        val grid = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode().boundsInRoot
        assertTrue("The compact header must leave usable media space", grid.height >= 100f)
        val controls = listOf("파일 검색", "파일 유형 선택", "파일 조건 선택", "정렬 기준 선택", "파일 추가 작업")
        val tops = controls.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue(tops.max() - tops.min() <= 1f)
        compose.onNodeWithContentDescription("정렬 기준 선택").performScrollTo().performClick()
        compose.onNodeWithText("용량 큰 순").assertIsDisplayed()
        compose.onNodeWithText("영상 짧은 순").performScrollTo().assertIsDisplayed()
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
        val a = compose.onNodeWithText(small.displayName).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText(large.displayName).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(a.top < b.top || (a.top == b.top && a.left < b.left))
    }

    private fun actions(change: (MediaFilter) -> Unit = {}) = FilesScreenActions(change, {}, {}, {}, {}, {}, { _, _ -> }, {})
    private fun media(id: Long) = MediaItem("content://fixture/$id", id, "external_primary", "file-$id.mp4", MediaKind.VIDEO,
        "video/mp4", "DCIM/Camera/", 1024, 1920, 1080, 60_000, 100, 100, 100)
}
