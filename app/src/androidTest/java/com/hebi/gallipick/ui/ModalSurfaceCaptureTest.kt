package com.hebi.gallipick.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
import com.hebi.gallipick.Stage7aFixtureMedia
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.TrashOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures the actual Compose dialogs with test-owned media; never issues a trash request. */
@RunWith(AndroidJUnit4::class)
class ModalSurfaceCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Screen { FILTER, DETAIL, PHOTO, VIDEO, COMPARE, REVIEW, RESTORE, TRASH, PRESET, CACHE, RESULT }

    @Test fun captureAllDialogAndDetailSurfaces() {
        val photoA = photo("EXAMPLE_original.jpg", 1)
        val photoB = photo("EXAMPLE_copy.jpg", 2)
        val output = File(compose.activity.filesDir, "modal-screen-captures").apply { mkdirs() }
        val videoBytes = InstrumentationRegistry.getInstrumentation().context.assets.open("preview-baseline.mp4").use { it.readBytes() }
        val videoFixture = Stage7aFixtureMedia.insert(compose.activity, "EXAMPLE_video.mp4", "video/mp4",
            videoBytes, System.currentTimeMillis(), "Movies/GalliPick_EXAMPLE/")
        val video = MediaItem(videoFixture.uri.toString(), ContentUris.parseId(videoFixture.uri),
            MediaStore.VOLUME_EXTERNAL_PRIMARY, videoFixture.name, MediaKind.VIDEO, "video/mp4",
            "Movies/GalliPick_EXAMPLE/", videoBytes.size.toLong(), 1920, 1080, 30_000, 0, 0, 0)
        val group = DuplicateGroup("example", listOf(photoA, photoB))
        var current by mutableStateOf(Screen.FILTER)
        var dark by mutableStateOf(true)
        val filter = MediaFilter(fromDateMs = System.currentTimeMillis() - 6L * 86_400_000)

        try {
            compose.setContent {
                GalliPickTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize(), color = AppBackground) {
                        when (current) {
                            Screen.FILTER -> FilterDialog(filter, listOf(photoA, photoB, video), {}, {})
                            Screen.DETAIL -> MediaDetailDialog(photoA.copy(isProtected = true), {}, {}, {})
                            Screen.PHOTO -> MediaPreviewDialog(photoA, {}, {})
                            Screen.VIDEO -> MediaPreviewDialog(video, {}, {})
                            Screen.COMPARE -> DuplicateCompareDialog(group, photoA.uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                            Screen.REVIEW -> TrashReviewDialog(listOf(photoB), listOf(photoA), false, 0, {}, {})
                            Screen.RESTORE -> TrashReviewDialog(listOf(photoB), emptyList(), true, 0, {}, {})
                            Screen.TRASH -> TrashScreen(listOf(photoB.copy(isTrashed = true)), {}, {})
                            Screen.PRESET -> SavePresetDialog({}, {})
                            Screen.CACHE -> ClearAnalysisCacheDialog({}, {})
                            Screen.RESULT -> CleanupResultDialog(CleanupOperationResult.verified(TrashOutcome(1, 0, 0), false, 1, photoB.sizeBytes), {}, {}, {})
                        }
                    }
                }
            }
            screenshot(output, "01-filter")
            compose.onNodeWithText("기간 선택").assertIsDisplayed().performClick()
            compose.onNodeWithText("시작일 선택").assertIsDisplayed()
            compose.onNodeWithText("날짜 선택").assertIsDisplayed()
            screenshot(output, "02-calendar-dark")
            compose.runOnIdle { dark = false; current = Screen.DETAIL }
            compose.onNodeWithText("정리에서 보호 중").assertIsDisplayed()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(photoA.displayName).fetchSemanticsNodes().isNotEmpty()
            }
            screenshot(output, "03-file-detail")
            compose.runOnIdle { current = Screen.PHOTO }
            compose.onNodeWithText("외부 앱에서 원본 열기").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("사진 미리보기: ${photoA.displayName}").fetchSemanticsNodes().isNotEmpty() }
            screenshot(output, "04-photo-preview")
            compose.runOnIdle { current = Screen.VIDEO }
            compose.onNodeWithText("재생").assertIsDisplayed()
            screenshot(output, "05-video-preview")
            compose.runOnIdle { current = Screen.COMPARE }
            compose.onNodeWithContentDescription("A 파일 비교 패널").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("사진 미리보기: ${photoA.displayName}").fetchSemanticsNodes().isNotEmpty() }
            screenshot(output, "06-duplicate-compare")
            compose.runOnIdle { current = Screen.REVIEW }
            compose.onNodeWithText("휴지통 이동 대상").assertIsDisplayed()
            screenshot(output, "07-trash-review")
            compose.runOnIdle { current = Screen.RESTORE }
            compose.onNodeWithText("복원 대상").assertIsDisplayed()
            screenshot(output, "08-restore-review")
            compose.runOnIdle { current = Screen.TRASH }
            compose.onNodeWithText("휴지통").assertIsDisplayed()
            compose.onNodeWithText("EXAMPLE_copy.jpg").assertIsDisplayed()
            screenshot(output, "09-trash-list")
            compose.runOnIdle { current = Screen.PRESET }
            compose.onNodeWithText("현재 필터 저장").assertIsDisplayed()
            screenshot(output, "10-filter-preset")
            compose.runOnIdle { current = Screen.CACHE }
            compose.onNodeWithText("분석 캐시를 비울까요?").assertIsDisplayed()
            screenshot(output, "11-cache-confirm")
            compose.runOnIdle { current = Screen.RESULT }
            compose.onNodeWithText("휴지통 이동 완료").assertIsDisplayed()
            screenshot(output, "12-cleanup-result")
            assertEquals("All modal/detail captures must be saved", 12, output.listFiles { file -> file.extension == "png" }?.size)
        } finally {
            listOf(photoA.uri, photoB.uri, video.uri).forEach { uri ->
                compose.activity.contentResolver.delete(Uri.parse(uri), null, null)
            }
        }
    }

    private fun photo(name: String, id: Long): MediaItem {
        val bytes = Stage7aFixtureMedia.sceneJpeg(3)
        val sample = Stage7aFixtureMedia.insert(compose.activity, name, "image/jpeg", bytes,
            System.currentTimeMillis(), "Pictures/GalliPick_EXAMPLE/")
        return MediaItem(sample.uri.toString(), ContentUris.parseId(sample.uri), MediaStore.VOLUME_EXTERNAL_PRIMARY,
            name, MediaKind.PHOTO, "image/jpeg", "Pictures/GalliPick_EXAMPLE/",
            bytes.size.toLong(), 480, 720, 0, 0, 0, 0)
    }

    private fun screenshot(output: File, name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(240)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Android did not provide a full-viewport screenshot: $name"
        }
        try {
            File(output, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not save $name" }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
