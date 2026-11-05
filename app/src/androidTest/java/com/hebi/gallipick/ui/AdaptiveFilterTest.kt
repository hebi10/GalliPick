package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.InterceptPlatformTextInput
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.Orientation
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveFilterTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wideShortFormUsesTwoColumnsAndKeepsScrolledDatesAboveTheFooter() {
        showFilter(width = 900, height = 360)

        val basic = compose.onNodeWithContentDescription("기본 필터 조건").fetchSemanticsNode().boundsInRoot
        val advanced = compose.onNodeWithContentDescription("고급 필터 조건").fetchSemanticsNode().boundsInRoot
        assertTrue("Wide filters must use the available width", basic.right <= advanced.left)
        compose.onNodeWithText("종료일").performScrollTo().assertIsDisplayed()
        assertBodyEndsAboveFooter()
        val endDate = compose.onNodeWithText("종료일").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithContentDescription("필터 작업").fetchSemanticsNode().boundsInRoot
        assertTrue("A scrolled date field must not draw beneath the actions", endDate.bottom <= footer.top)
        compose.onNodeWithText("필터 적용").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun narrowFormExplicitlyRevealsAdvancedConditionsAndAppliesTheirValues() {
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520, onApply = { applied = it })

        compose.onNodeWithText("시작일").assertDoesNotExist()
        compose.onNodeWithText("고급 조건 열기").performScrollTo().performClick()
        compose.onNodeWithText("시작일").performScrollTo().performTextReplacement("2026-11-01")
        compose.onNodeWithText("촬영일  ▾").performScrollTo().performClick()
        compose.onNodeWithText("파일 이름").performScrollTo().performClick()
        compose.onNodeWithText("필터 적용").performClick()

        compose.runOnIdle {
            assertNotNull(applied?.fromDateMs)
            assertEquals(SortField.NAME, applied?.sortField)
        }
    }

    @Test fun recentDateShortcutsAndCalendarButtonsStayReachable() {
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520, onApply = { applied = it })

        compose.onNodeWithText("고급 조건 열기").performScrollTo().performClick()
        compose.onNodeWithText("최근 7일").performScrollTo().performClick()
        compose.onNodeWithText("시작일").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("시작일 달력에서 선택").assertExists()
        compose.onNodeWithContentDescription("종료일 달력에서 선택").assertExists()
        compose.onNodeWithText("필터 적용").performClick()

        compose.runOnIdle {
            val start = requireNotNull(applied?.fromDateMs)
            val end = requireNotNull(applied?.toDateMs)
            val days = (end - start + 1) / 86_400_000
            assertTrue("Recent 7 days must include the whole final day", days in 7L..8L)
        }
    }

    @Test fun shortNarrowLargeTextKeepsTheFinalDateFieldAndApplyReachable() {
        var applied: MediaFilter? = null
        // The reduced height also covers the body constraints left by an on-screen keyboard.
        showFilter(width = 320, height = 340, fontScale = 1.6f, onApply = { applied = it })

        compose.onNodeWithText("고급 조건 열기").performScrollTo().performClick()
        compose.onNodeWithText("종료일").performScrollTo().assertIsDisplayed()
            .performTextReplacement("2026-11-03")
        assertBodyEndsAboveFooter()
        compose.onNodeWithText("필터 적용").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNotNull(applied?.toDateMs) }
    }

    @Test fun focusedQueryStaysFullyVisibleWhenKeyboardLeavesOnly116dp() {
        val height = mutableStateOf(480)
        showFilter(width = 900, height = 480, windowHeight = height)
        val query = compose.onNodeWithText("파일 이름 검색")
        query.performScrollTo().performClick().assertIsFocused()

        compose.runOnIdle { height.value = 116 }
        saveScreenshot("filter-resize-focused-116dp")

        query.assertIsFocused().assertIsDisplayed()
        assertFocusedFieldAndActionsFit(query)
    }

    @Test fun shortLargeTextChromeKeepsTheFocusedQueryAndEveryActionReachable() {
        val height = mutableStateOf(480)
        showFilter(width = 900, height = 480, fontScale = 1.6f, windowHeight = height)
        val query = compose.onNodeWithText("파일 이름 검색")
        query.performScrollTo().performClick().assertIsFocused()

        compose.runOnIdle { height.value = 180 }
        saveScreenshot("filter-resize-focused-large-text")

        query.assertIsFocused().assertIsDisplayed()
        assertFocusedFieldAndActionsFit(query)
    }

    @Test fun compactInvalidApplyRevealsTheErrorAndPreservesTypedBounds() {
        var applied: MediaFilter? = null
        showFilter(width = 900, height = 130, onApply = { applied = it })
        val minimum = compose.onNodeWithText("최소 MB")
        val maximum = compose.onNodeWithText("최대 MB")
        minimum.performScrollTo().performTextReplacement("2")
        maximum.performScrollTo().performTextReplacement("1")
        maximum.performClick().assertIsFocused()

        compose.onNodeWithText("필터 적용").performClick()

        compose.onNodeWithText("파일 크기: 최소값이 최대값보다 클 수 없습니다.").assertIsDisplayed()
        compose.onNodeWithText("필터 적용").assertIsDisplayed().assertIsEnabled()
        maximum.assertIsNotFocused()
        assertBodyEndsAboveFooter()
        compose.runOnIdle { assertNull(applied) }
        minimum.performScrollTo().assertTextContains("2")
        maximum.performScrollTo().assertTextContains("1").performClick()
        // The same error must be revealed on a repeated invalid attempt too.
        compose.onNodeWithText("필터 적용").performClick()
        compose.onNodeWithText("파일 크기: 최소값이 최대값보다 클 수 없습니다.").assertIsDisplayed()
        compose.runOnIdle { assertNull(applied) }
    }

    @Test fun enteringMbUsesTheSameBinaryBytesAsTheAppsSizeLabels() {
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520, onApply = { applied = it })

        compose.onNodeWithText("앱의 용량 표기와 같은 기준입니다. 1 MB = 1,048,576바이트.").assertDoesNotExist()
        compose.onNodeWithText("용량 단위 안내").performScrollTo().performClick()
        compose.onNodeWithText("앱의 용량 표기와 같은 기준입니다. 1 MB = 1,048,576바이트.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("최소 MB").performScrollTo().performTextReplacement("1")
        compose.onNodeWithText("최대 MB").performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("필터 적용").performClick()
        compose.runOnIdle {
            assertEquals(1_048_576L, applied?.minSizeBytes)
            assertEquals(2_097_152L, applied?.maxSizeBytes)
        }
    }

    @Test fun untouchedPersistedBoundsRoundTripWithoutLosingBytesOrMilliseconds() {
        val original = MediaFilter(
            kind = MediaKind.VIDEO, minSizeBytes = 1, maxSizeBytes = Long.MAX_VALUE,
            minDurationMs = 125_001, maxDurationMs = 600_007,
        )
        var applied: MediaFilter? = null
        showFilter(width = 900, height = 480, current = original, onApply = { applied = it })

        compose.onNodeWithText("필터 적용").performClick()
        compose.runOnIdle { assertEquals(original, applied) }
    }

    @Test fun returningToVideoBeforeApplyPreservesDraftDurationBounds() {
        val original = MediaFilter(kind = MediaKind.VIDEO, minDurationMs = 125_001, maxDurationMs = 600_007)
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520, current = original, onApply = { applied = it })

        compose.onNodeWithText("사진").performClick()
        compose.onNodeWithText("동영상").performClick()
        compose.onNodeWithText("필터 적용").performClick()
        compose.runOnIdle { assertEquals(original, applied) }
    }

    @Test fun applyingPhotoTypeClearsDurationConditionsAsBefore() {
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520,
            current = MediaFilter(kind = MediaKind.VIDEO, minDurationMs = 60_000, maxDurationMs = 120_000),
            onApply = { applied = it })

        compose.onNodeWithText("사진").performClick()
        compose.onNodeWithText("필터 적용").performClick()
        compose.runOnIdle {
            assertEquals(MediaKind.PHOTO, applied?.kind)
            assertNull(applied?.minDurationMs)
            assertNull(applied?.maxDurationMs)
        }
    }

    @Test fun existingAdvancedConditionsOpenAutomaticallyAndSurviveAnEmptyItemList() {
        val original = MediaFilter(
            kind = MediaKind.PHOTO, query = "여행", folder = "Pictures/Archive/", mimeType = "image/webp",
            minPixels = 12_345_678, orientation = Orientation.PORTRAIT, favoritesOnly = true, protectedOnly = true,
            sortField = SortField.NAME, sortDirection = SortDirection.ASCENDING,
        )
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520, current = original, onApply = { applied = it })

        compose.onNodeWithText("고급 조건 접기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("image/webp  ▾").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("필터 적용").performClick()
        compose.runOnIdle { assertEquals(original, applied) }
    }

    @Test fun collapsingAdvancedConditionsDoesNotBypassDateValidation() {
        var applied: MediaFilter? = null
        showFilter(width = 320, height = 520, onApply = { applied = it })

        compose.onNodeWithText("고급 조건 열기").performScrollTo().performClick()
        compose.onNodeWithText("시작일").performScrollTo().performTextReplacement("2026-02-30")
        compose.onNodeWithText("고급 조건 접기").performScrollTo().performClick()
        compose.onNodeWithText("필터 적용").performClick()

        compose.onNodeWithText("존재하는 날짜를 입력해 주세요.").assertIsDisplayed()
        compose.runOnIdle { assertNull(applied) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun showFilter(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        current: MediaFilter = MediaFilter(),
        windowHeight: State<Int>? = null,
        onApply: (MediaFilter) -> Unit = {},
    ) {
        compose.setContent {
            GalliPickTheme {
                // Resize cases model the available height themselves. Do not also let the real
                // IME resize/pan the host activity; NativeScreenCaptureTest verifies the real IME.
                InterceptPlatformTextInput(interceptor = { request, next ->
                    if (windowHeight == null) next.startInputMethod(request) else awaitCancellation()
                }) {
                    CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = fontScale)) {
                        Box(Modifier.requiredSize(width.dp, (windowHeight?.value ?: height).dp)) {
                            FilterDialogContent(current, emptyList(), onApply, {}, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertBodyEndsAboveFooter() {
        val body = compose.onNodeWithContentDescription("필터 조건").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithContentDescription("필터 작업").fetchSemanticsNode().boundsInRoot
        assertTrue("The form must retain scrollable height", body.height > 0f)
        assertTrue("The form viewport must end before its separate footer", body.bottom <= footer.top)
    }

    private fun assertFocusedFieldAndActionsFit(field: SemanticsNodeInteraction) {
        assertBodyEndsAboveFooter()
        val body = compose.onNodeWithContentDescription("필터 조건").fetchSemanticsNode().boundsInRoot
        val fieldNode = field.fetchSemanticsNode()
        val fieldBounds = fieldNode.boundsInRoot
        assertTrue("The whole field including its floating label must remain visible", fieldBounds.height >= fieldNode.size.height - 1f)
        val footer = compose.onNodeWithContentDescription("필터 작업").fetchSemanticsNode().boundsInRoot
        assertTrue("A complete text field needs at least 56dp after the keyboard opens", fieldBounds.height >= 56f * .6f - 1f)
        assertTrue("The focused field must remain inside the clipped body", fieldBounds.top >= body.top && fieldBounds.bottom <= body.bottom)
        assertTrue("The focused field must end above the actions", fieldBounds.bottom <= footer.top)
        listOf("초기화", "취소", "필터 적용").forEach { label ->
            val action = compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            assertTrue("Each action must retain a 48dp touch target", action.fetchSemanticsNode().boundsInRoot.height >= 48f * .6f - 1f)
        }
    }
}
