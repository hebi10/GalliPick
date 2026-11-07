package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
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

    @Test fun narrowFilesGridShowsThreePhotoColumnsAndCanSwitchToReadableList() {
        val items = (1L..7L).map(::media)
        var selectAllCount = 0
        val actions = FilesScreenActions({}, { selectAllCount++ }, {}, {}, {}, {}, { _, _ -> }, {})
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Box(Modifier.requiredSize(420.dp, 760.dp)) {
                        FilesScreenContent(
                            AppUiState(access = MediaAccess.FULL, items = items, filtered = items),
                            false, {}, {}, {}, actions,
                        )
                    }
                }
            }
        }

        val a = compose.onNodeWithContentDescription("sample-1.jpg 선택").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithContentDescription("sample-2.jpg 선택").fetchSemanticsNode().boundsInRoot
        val c = compose.onNodeWithContentDescription("sample-3.jpg 선택").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference media grid should show 3 photos per row", a.left < b.left && b.left < c.left)
        assertTrue("First three selections should share a grid row", kotlin.math.abs(a.top - c.top) < 1f)
        compose.onNodeWithContentDescription("파일 목록 요약").assertExists()
        compose.onNodeWithText("모두 선택").performClick()
        compose.runOnIdle { assertEquals(1, selectAllCount) }
        compose.onNodeWithContentDescription("목록 보기").performClick()
        compose.onNodeWithText("sample-1.jpg").assertIsDisplayed()
        compose.onNodeWithText("2 KB · 1970.01.01").assertExists()
        compose.onNodeWithContentDescription("그리드 보기").performClick()
        compose.onNodeWithContentDescription("sample-1.jpg 선택").assertExists()
    }

    @Test fun gridSelectionIndicatorMovesToCornerWhileHitTargetRemainsAccessible() {
        val item = media(1)
        var selected by mutableStateOf(emptySet<String>())
        var toggles = 0
        val actions = FilesScreenActions({}, {}, { uri ->
            toggles++
            selected = if (uri in selected) selected - uri else selected + uri
        }, {}, {}, {}, { _, _ -> }, {})
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Box(Modifier.requiredSize(360.dp, 700.dp)) {
                        FilesScreenContent(
                            AppUiState(access = MediaAccess.FULL, items = listOf(item),
                                filtered = listOf(item), selected = selected),
                            false, {}, {}, {}, actions,
                        )
                    }
                }
            }
        }

        val checkbox = compose.onNodeWithContentDescription("sample-1.jpg 선택")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val hit = checkbox.fetchSemanticsNode().boundsInRoot
        val glyph = compose.onNodeWithTag("grid-selection-indicator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Selection circle must stay inside the 48dp touch target",
            glyph.left >= hit.left && glyph.top >= hit.top && glyph.right <= hit.right && glyph.bottom <= hit.bottom)
        assertTrue("Visible circle should be within 8dp of the thumbnail top-left",
            glyph.left - hit.left < hit.width / 6f && glyph.top - hit.top < hit.height / 6f)
        checkbox.performClick()
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertTrue(item.uri in selected)
        }
        compose.onNodeWithText(item.displayName).performClick()
        compose.onNodeWithText("파일 상세").assertIsDisplayed()
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
}
