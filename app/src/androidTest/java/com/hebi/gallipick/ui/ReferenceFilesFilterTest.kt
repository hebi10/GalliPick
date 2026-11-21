package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppSettings
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class ReferenceFilesFilterTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun media(id: Long) = MediaItem(
        uri = "content://fixture/$id", id = id, volume = "external_primary",
        displayName = "sample-$id.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = "DCIM/", sizeBytes = 2048L * id, width = 600, height = 600,
        durationMs = 0L, dateTakenMs = 1L, dateAddedMs = 1L, dateModifiedMs = 1L,
    )

    @Test fun returningFromCloudOpensOnlyTheSameVerifiedFileVersion() {
        val original = media(7L).copy(volumeVersion = "original-volume")
        val replacement = original.copy(volumeVersion = "new-volume")
        val request = mutableStateOf(original.detailVersion())
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL))
        var resumed = 0
        val actions = FilesScreenActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {})
        compose.setContent {
            GalliPickTheme {
                FilesScreenContent(state.value, false, {}, {}, {}, actions,
                    resumeDetailVersion = request.value, onDetailResumed = { resumed++ })
            }
        }
        compose.onNodeWithText("파일 상세").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, resumed)
            state.value = state.value.copy(items = listOf(replacement), filtered = listOf(replacement), catalogReady = true)
        }
        compose.onNodeWithText("파일 상세").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, resumed)
            request.value = replacement.detailVersion()
        }
        compose.onNodeWithText("파일 상세").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, resumed) }
    }

    @Test fun narrowFilesGridShowsThreePhotoColumnsAndCanSwitchToReadableList() {
        val items = (1L..7L).map(::media)
        val listView = mutableStateOf(false)
        var bulkSelected = emptyList<MediaItem>()
        val actions = FilesScreenActions({}, { bulkSelected = it }, {}, {}, {}, {}, { _, _ -> }, {},
            setFileListView = { listView.value = it })
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Box(Modifier.requiredSize(420.dp, 760.dp)) {
                        FilesScreenContent(
                            AppUiState(access = MediaAccess.FULL, items = items, filtered = items,
                                fileListView = listView.value),
                            false, {}, {}, {}, actions,
                        )
                    }
                }
            }
        }

        val a = compose.onNodeWithText("sample-1.jpg").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithText("sample-2.jpg").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithText("sample-3.jpg").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference media grid should show 3 photos per row", a.left < b.left && b.left < c.left)
        assertTrue("First three selections should share a grid row", kotlin.math.abs(a.top - c.top) < 1f)
        compose.onNodeWithContentDescription("파일 목록 요약").assertExists()
        compose.onNodeWithText("현재 결과 7개 선택").performClick()
        compose.runOnIdle { assertEquals(items, bulkSelected) }
        compose.onNodeWithContentDescription("파일 추가 작업").performClick()
        compose.onNodeWithText("목록 보기").performClick()
        compose.onNodeWithText("sample-1.jpg").assertIsDisplayed()
        compose.onNodeWithText("2 KB · 1970.01.01").assertExists()
        compose.onNodeWithContentDescription("파일 추가 작업").performClick()
        compose.onNodeWithText("격자 보기").performClick()
        compose.onNodeWithText("sample-1.jpg").assertExists()
    }

    @Test fun fileSearchStartsClosedAndUsesTheHeaderButton() {
        val vm = AppViewModel(compose.activity.application)
        compose.setContent { GalliPickTheme { GalliPickApp(vm, {}, {}, {}, {}) } }
        openTab("파일")
        val input = compose.onNodeWithContentDescription("파일 이름 또는 폴더 검색")
        input.assertDoesNotExist()
        compose.onNodeWithContentDescription("새로고침").assertIsDisplayed()
        compose.onNodeWithContentDescription("파일 검색").performClick()
        input.assertIsDisplayed()
        compose.onNodeWithContentDescription("파일 검색 닫기").performClick()
        input.assertDoesNotExist()
        compose.onNodeWithContentDescription("파일 조건 선택").assertIsDisplayed()
    }

    @Test fun settingsAndFilesMenuShareTheSavedViewMode() {
        val application = compose.activity.application
        val vm = AppViewModel(application)
        val settings = AppSettings(application)
        val previous = settings.loadFileListView()
        try {
            compose.setContent { GalliPickTheme { GalliPickApp(vm, {}, {}, {}, {}) } }
            openTab("설정")
            compose.onNodeWithText("목록", substring = false).performScrollTo().performClick()
            compose.runOnIdle { assertTrue(settings.loadFileListView()) }
            openTab("파일")
            compose.onNodeWithContentDescription("파일 추가 작업").performClick()
            compose.onNodeWithText("격자 보기").performClick()
            compose.runOnIdle { assertEquals(false, settings.loadFileListView()) }
            openTab("설정")
            compose.onNodeWithText("격자", substring = false).performScrollTo().assertIsSelected()
        } finally {
            compose.runOnIdle { vm.setFileListView(previous) }
        }
    }

    private fun openTab(title: String) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(hasText(title) and tab).performClick()
    }

    @Test fun gridLongPressEntersSelectionAndKeepsTheCornerIndicatorAccessible() {
        val item = media(1)
        val another = media(2)
        val protected = media(3).copy(isProtected = true)
        val items = listOf(item, another, protected)
        val selected = mutableStateOf(emptySet<String>())
        val actions = FilesScreenActions({}, {}, { uri ->
            selected.value = if (uri in selected.value) selected.value - uri else selected.value + uri
        }, { selected.value = emptySet() }, {}, {}, { _, _ -> }, {})
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Box(Modifier.requiredSize(360.dp, 700.dp)) {
                        FilesScreenContent(
                            AppUiState(access = MediaAccess.FULL, items = items,
                                filtered = items, selected = selected.value, fileGridColumns = 6),
                            false, {}, {}, {}, actions,
                        )
                    }
                }
            }
        }

        val firstTile = compose.onNode(hasText(item.displayName) and hasClickAction())
        val secondTile = compose.onNode(hasText(another.displayName) and hasClickAction())
        val checkbox = compose.onNodeWithContentDescription("sample-1.jpg 선택")
        checkbox.assertDoesNotExist()
        compose.onNodeWithText("한 줄 3개 표시 · 설정 6개").assertIsDisplayed()
        firstTile.performTouchInput {
            down(center)
            advanceEventTime(400)
            up()
        }
        compose.onNodeWithText("파일 상세").assertIsDisplayed()
        compose.onNodeWithContentDescription("닫기").performClick()
        checkbox.assertDoesNotExist()
        compose.onNode(hasText(protected.displayName) and hasClickAction())
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
        firstTile.performTouchInput {
            down(center)
            move(delayMillis = 600)
            up()
        }
        checkbox.assertIsOn().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("sample-2.jpg 선택").assertIsOff()
        compose.onNodeWithContentDescription("sample-3.jpg 선택").assertDoesNotExist()
        val hit = checkbox.fetchSemanticsNode().boundsInRoot
        val glyph = compose.onAllNodesWithTag("grid-selection-indicator", useUnmergedTree = true)[0]
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Selection circle must stay inside the 48dp touch target",
            glyph.left >= hit.left && glyph.top >= hit.top && glyph.right <= hit.right && glyph.bottom <= hit.bottom)
        assertTrue("Visible circle should be within 8dp of the thumbnail top-left",
            glyph.left - hit.left < hit.width / 6f && glyph.top - hit.top < hit.height / 6f)
        secondTile.performTouchInput { click() }
        compose.onNodeWithText("2개 선택").assertIsDisplayed()
        compose.onNodeWithText("파일 상세").assertDoesNotExist()
        firstTile.performClick()
        secondTile.performClick()
        checkbox.assertDoesNotExist()
        firstTile.performTouchInput { longClick(durationMillis = 2_100) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        checkbox.assertDoesNotExist()
        firstTile.performTouchInput { click() }
        compose.onNodeWithText("파일 상세").assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected.value.isEmpty()) }
    }

    @Test fun oneGridTouchCanSelectFilesWhileStillDown() = verifyContinuousFileSelection(listView = false)

    @Test fun oneListTouchCanSelectFilesWhileStillDown() = verifyContinuousFileSelection(listView = true)

    @Test fun swipingBeforeTheHoldThresholdScrollsWithoutSelecting() {
        val items = (1L..60L).map(::media)
        val selected = showFileDragFixture(items)
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val list = compose.onNodeWithContentDescription("미디어 파일 목록").fetchSemanticsNode()
        val scroll = list.config[SemanticsProperties.VerticalScrollAxisRange]
        val before = compose.runOnIdle { scroll.value() }
        val start = compose.onAllNodes(hasText("sample-", substring = true) and hasClickAction())
            .fetchSemanticsNodes().map { it.boundsInRoot.intersect(list.boundsInRoot) }
            .filterNot { it.isEmpty }.maxBy { it.center.y }.center - origin

        root.performTouchInput {
            swipe(start = start, end = Offset(start.x, list.boundsInRoot.top - origin.y + 4f), durationMillis = 220)
        }

        compose.runOnIdle {
            assertTrue("A short swipe should scroll the file grid", scroll.value() > before)
            assertTrue("A short swipe should leave selection empty", selected.value.isEmpty())
        }
        compose.onNodeWithText("파일 상세").assertDoesNotExist()
    }

    @Test fun draggingAtTheNewViewportEdgeScrollsAndAddsFiles() {
        val items = (1L..60L).map(::media)
        val selected = showFileDragFixture(items)
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val list = compose.onNodeWithContentDescription("미디어 파일 목록")
        val before = list.fetchSemanticsNode().boundsInRoot
        val first = items.first()
        val start = compose.onNode(hasText(first.displayName) and hasClickAction())
            .fetchSemanticsNode().boundsInRoot.center - origin

        root.performTouchInput {
            down(start)
            move(delayMillis = 1_600)
        }
        compose.onNodeWithContentDescription("${first.displayName} 선택").assertIsOn()
        val activeList = list.fetchSemanticsNode()
        assertTrue("The selection footer should shrink the file viewport", activeList.boundsInRoot.height < before.height)
        val initiallyVisible = compose.onAllNodes(hasContentDescription(" 선택", substring = true) and hasClickAction())
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }.toSet()
        val scroll = activeList.config[SemanticsProperties.VerticalScrollAxisRange]
        val beforeScroll = compose.runOnIdle { scroll.value() }

        root.performTouchInput {
            moveTo(pointerId = 0, position = Offset(start.x, activeList.boundsInRoot.bottom - origin.y - 2f))
            move(delayMillis = 720)
            up()
        }

        val afterScroll = compose.runOnIdle {
            assertTrue("Holding at the edge should scroll the file grid", scroll.value() > beforeScroll)
            assertTrue("Scrolling should add at least one previously unseen file", items.any {
                it.uri in selected.value && "${it.displayName} 선택" !in initiallyVisible
            })
            scroll.value()
        }
        compose.mainClock.advanceTimeBy(240)
        compose.runOnIdle { assertEquals(afterScroll, scroll.value(), 0f) }
    }

    private fun verifyContinuousFileSelection(listView: Boolean) {
        val first = media(1)
        val protected = media(2).copy(isProtected = true)
        val third = media(3)
        val items = listOf(first, protected, third)
        val selected = showFileDragFixture(items, listView)

        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val firstPosition = compose.onNode(hasText(first.displayName) and hasClickAction())
            .fetchSemanticsNode().boundsInRoot.center - origin
        val thirdPosition = compose.onNode(hasText(third.displayName) and hasClickAction())
            .fetchSemanticsNode().boundsInRoot.center - origin
        root.performTouchInput {
            down(firstPosition)
            move(delayMillis = 600)
        }
        compose.onNodeWithContentDescription("${first.displayName} 선택").assertIsOn()
        root.performTouchInput {
            moveTo(pointerId = 0, position = thirdPosition)
            moveTo(pointerId = 0, position = firstPosition)
            up()
        }
        compose.onNodeWithContentDescription("${third.displayName} 선택").assertIsOn()
        compose.runOnIdle { assertEquals(setOf(first.uri, third.uri), selected.value) }
        compose.onNodeWithText("파일 상세").assertDoesNotExist()
    }

    private fun showFileDragFixture(items: List<MediaItem>, listView: Boolean = false): MutableState<Set<String>> {
        val selected = mutableStateOf(emptySet<String>())
        val actions = FilesScreenActions({}, {}, { uri ->
            selected.value = if (uri in selected.value) selected.value - uri else selected.value + uri
        }, { selected.value = emptySet() }, {}, {}, { _, _ -> }, {})
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Box(Modifier.requiredSize(360.dp, 700.dp)) {
                        FilesScreenContent(
                            AppUiState(access = MediaAccess.FULL, items = items, filtered = items,
                                selected = selected.value, fileListView = listView),
                            false, {}, {}, {}, actions,
                        )
                    }
                }
            }
        }
        return selected
    }

    @Test fun quickLastMonthUsesCalendarBoundariesAndBottomApply() {
        var applied: MediaFilter? = null
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Box(Modifier.requiredSize(360.dp, 700.dp)) {
                        FilterDialogContent(MediaFilter(), emptyList(), { applied = it }, {})
                    }
                }
            }
        }
        compose.onNodeWithText("날짜 범위").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("지난 달").performScrollTo().performClick()
        compose.onNodeWithText("필터 적용").performClick()
        compose.runOnIdle {
            val result = requireNotNull(applied)
            assertNotNull(result.fromDateMs)
            assertNotNull(result.toDateMs)
            val start = Calendar.getInstance().apply { timeInMillis = requireNotNull(result.fromDateMs) }
            val end = Calendar.getInstance().apply { timeInMillis = requireNotNull(result.toDateMs) }
            assertEquals(1, start.get(Calendar.DAY_OF_MONTH))
            assertEquals(end.getActualMaximum(Calendar.DAY_OF_MONTH), end.get(Calendar.DAY_OF_MONTH))
            assertTrue(requireNotNull(result.toDateMs) >= requireNotNull(result.fromDateMs))
        }
    }

    @Test fun photoDetailKeepsFileActionsAndShowsSizeOnlyInTheMetadata() {
        val item = media(1)
        val shown = mutableStateOf(true)
        var opened: MediaItem? = null
        compose.setContent {
            GalliPickTheme {
                if (shown.value) MediaDetailDialog(item, { shown.value = false }, { opened = it }, {})
            }
        }

        compose.onNodeWithText(item.displayName).assertIsDisplayed()
        compose.onNodeWithText("사진 정보").assertDoesNotExist()
        compose.onNodeWithText("사진 · 2 KB").assertDoesNotExist()
        compose.onNodeWithText("용량").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 KB").assertIsDisplayed()
        compose.onNodeWithText("파일 열기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(item, opened) }
        compose.onNodeWithContentDescription("닫기").performClick()
        compose.onNodeWithText("파일 상세").assertDoesNotExist()
    }
}
