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
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveFilesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wideFilesUseFullListUntilOpenedAndAllowClosingDetails() {
        val items = (1L..8L).map(::media)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = items, filtered = items), true, {}, {}, {}, actions())
                }
            }
        } }
        compose.onNodeWithContentDescription("파일 상세 패널").assertDoesNotExist()
        val original = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(items.first().displayName).performClick()
        compose.onNodeWithContentDescription("파일 상세 패널").assertIsDisplayed()
        val opened = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode().boundsInRoot
        assertTrue("Opening details should share the list width", opened.width < original.width)
        compose.onNodeWithContentDescription("파일 상세 닫기").performClick()
        compose.onNodeWithContentDescription("파일 상세 패널").assertDoesNotExist()
        val closed = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode().boundsInRoot
        assertTrue("Closing details restores the list", closed.width >= original.width - 1f)
    }

    @Test fun tabletGridShowsFiveReadableColumnsAndReflowsWhenDetailsOpen() {
        assertEquals(5, tabletMediaGridColumns(1600.dp, 1f))
        assertEquals(3, tabletMediaGridColumns(750.dp, 1f))
        assertEquals(2, tabletMediaGridColumns(800.dp, 1.6f))

        val items = (1L..12L).map(::media)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = items, filtered = items),
                        true, {}, {}, {}, actions())
                }
            }
        } }
        val first = compose.onNodeWithTag("tablet-media-1").fetchSemanticsNode().boundsInRoot
        val fifth = compose.onNodeWithTag("tablet-media-5").fetchSemanticsNode().boundsInRoot
        val sixth = compose.onNodeWithTag("tablet-media-6").fetchSemanticsNode().boundsInRoot
        assertTrue("Tablet gallery should show up to five cards per row",
            kotlin.math.abs(first.top - fifth.top) < 4f)
        assertTrue("Sixth card should wrap to the next row", sixth.top > first.top + 20f)

        compose.onNodeWithText("file-1.jpg").performClick()
        compose.onNodeWithContentDescription("파일 상세 패널").assertIsDisplayed()
        val reopenedFirst = compose.onNodeWithTag("tablet-media-1")
            .fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("tablet-media-4").fetchSemanticsNode().boundsInRoot
        assertTrue("Narrower gallery must reduce columns when detail panel is visible",
            fourth.top > reopenedFirst.top + 20f)
    }

    @Test fun selectAllStaysNextToCountRatherThanFarAcrossWideWindow() {
        val items = listOf(media(1))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Box(Modifier.requiredSize(1100.dp, 700.dp)) {
                    FilesScreenContent(AppUiState(access = MediaAccess.FULL, items = items, filtered = items), true, {}, {}, {}, actions())
                }
            }
        } }
        val count = compose.onNodeWithContentDescription("파일 목록 요약").fetchSemanticsNode().boundsInRoot
        val select = compose.onNodeWithText("모두 선택").fetchSemanticsNode().boundsInRoot
        assertTrue("Selection action belongs beside its list count", select.left - count.right <= 24f)
        assertTrue("Selection action should not sit on the far edge", select.right - count.left < 350f)
    }

    @Test fun largeTextSelectionSummaryAndTrashActionRemainVisible() {
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = 1.6f)) {
                Box(Modifier.requiredSize(320.dp, 260.dp)) {
                    SelectionBar(listOf(media(1)), {}, {})
                }
            }
        } }
        compose.onNodeWithText("1개 선택").assertIsDisplayed()
        compose.onNodeWithText("해제").assertIsDisplayed()
        compose.onNodeWithText("휴지통으로").assertIsDisplayed().assertIsEnabled()
    }

    private fun actions() = FilesScreenActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {})
    private fun media(id: Long) = MediaItem("content://fixture/$id", id, "external_primary", "file-$id.jpg", MediaKind.PHOTO,
        "image/jpeg", "Pictures/", 1024, 800, 600, 0, 100, 100, 100)
}
