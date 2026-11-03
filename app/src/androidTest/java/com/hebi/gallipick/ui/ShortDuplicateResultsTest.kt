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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShortDuplicateResultsTest {
    @get:Rule val compose = createComposeRule()

    // The previous stacked intro, scan and scope controls hide both files in this viewport.
    @Test fun shortWideResultsShowBothCompleteFilesAndTheirUsableDecisionsImmediately() {
        val keeper = media(1, "keep.mp4")
        val candidate = media(2, "copy.mp4")
        val group = DuplicateGroup("short-results", listOf(keeper, candidate))
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = group.items, duplicates = listOf(group),
            duplicateKeepers = mapOf(group.hash to keeper.uri), scan = ScanState(ScanPhase.COMPLETE, 2, 2)))
        var toggledUri: String? = null
        var selectedGroup: String? = null
        var selectedAll = false
        var rescanned = false
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Box(Modifier.requiredSize(1000.dp, 280.dp)) {
                    DuplicatesScreenContent(state.value, {}, {}, {}, DuplicatesScreenActions(
                        { rescanned = true }, {}, {}, { selectedAll = true }, { uri ->
                            toggledUri = uri
                            state.value = state.value.copy(selected = state.value.selected + uri)
                        }, {}, { _, _ -> }, { _, _ -> }, {}, { selectedGroup = it.hash },
                    ))
                }
            }
        } }
        val list = compose.onNodeWithContentDescription("중복 파일 목록")
        val viewport = list.getUnclippedBoundsInRoot()
        val keeperRow = compose.onNodeWithContentDescription("keep.mp4, Download/, 남길 파일 · 직접 지정")
        val candidateRow = compose.onNodeWithContentDescription("copy.mp4, Download/, 정리 후보")
        listOf(keeperRow, candidateRow).forEach { row ->
            row.assertIsDisplayed()
            val bounds = row.getUnclippedBoundsInRoot()
            assertTrue("Every file must fit in the first viewport", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom &&
                bounds.left >= viewport.left && bounds.right <= viewport.right)
        }
        compose.onNodeWithText("keep.mp4").assertIsDisplayed()
        compose.onNodeWithText("copy.mp4").assertIsDisplayed()
        compose.onNodeWithContentDescription("keep.mp4 남김").assertIsDisplayed().assertHasNoClickAction()
        val candidateControl = compose.onNodeWithContentDescription("copy.mp4, Download/ 정리 후보 선택")
        candidateControl.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("중복 정리 후보 선택").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("중복 묶음 1 후보 선택").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("다시 검사").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertTrue(selectedAll); assertEquals(group.hash, selectedGroup); assertTrue(rescanned) }
        candidateControl.performClick()
        compose.runOnIdle { assertEquals(candidate.uri, toggledUri); assertEquals(setOf(candidate.uri), state.value.selected) }
        list.performScrollToNode(hasContentDescription("copy.mp4, Download/ 정리 후보 선택"))
        compose.onNodeWithContentDescription("copy.mp4, Download/ 정리 후보 선택").assertIsDisplayed().assertIsOn()
        list.performScrollToNode(hasContentDescription("중복 묶음 1 비교"))
        compose.onNodeWithContentDescription("중복 묶음 1 비교").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("비교 닫기").assertIsDisplayed()
    }

    private fun media(id: Long, name: String) = MediaItem("file:///missing-short-duplicate-$id.mp4", id, "test", name,
        MediaKind.VIDEO, "video/mp4", "Download/", 1024, 1920, 1080, 60_000, id, id, id)
}
