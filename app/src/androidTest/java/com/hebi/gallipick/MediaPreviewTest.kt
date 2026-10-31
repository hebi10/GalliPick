package com.hebi.gallipick

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.ui.GalliPickTheme
import com.hebi.gallipick.ui.MediaDetail
import com.hebi.gallipick.ui.MediaPreviewDialog
import com.hebi.gallipick.ui.PREVIEW_MAX_EDGE
import com.hebi.gallipick.ui.VideoPreview
import com.hebi.gallipick.ui.decodePreviewBitmap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()

    @After fun cleanup() { files.forEach { it.delete() } }

    @Test fun largePhotoDecodeIsBoundedAndEncodedOrientationIsApplied() = runBlocking {
        val large = imageFile(4096, 512)
        val bounded = decodePreviewBitmap(compose.activity.contentResolver, Uri.fromFile(large))
        try {
            assertEquals(PREVIEW_MAX_EDGE, bounded.width)
            assertEquals(256, bounded.height)
            assertTrue(bounded.allocationByteCount <= PREVIEW_MAX_EDGE * PREVIEW_MAX_EDGE * 4)
        } finally { bounded.recycle() }

        val rotated = imageFile(80, 40)
        ExifInterface(rotated.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val oriented = decodePreviewBitmap(compose.activity.contentResolver, Uri.fromFile(rotated))
        try { assertEquals(40, oriented.width); assertEquals(80, oriented.height) }
        finally { oriented.recycle() }
    }

    @Test fun internalPhotoPreviewZoomAndExternalOpenAreSeparateActions() {
        val item = media(imageFile(160, 80))
        var externalOpens = 0
        compose.setContent { GalliPickTheme { MediaDetail(item, { externalOpens++ }, {}) } }
        compose.onNodeWithText("앱에서 미리보기").performScrollTo().performClick()
        waitForPhoto(item)
        compose.onNodeWithContentDescription("사진 확대").performClick()
        compose.onNodeWithContentDescription("사진 미리보기: ${item.displayName}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150%"))
        compose.onNodeWithText("맞춤").performClick()
        compose.onNodeWithContentDescription("사진 미리보기: ${item.displayName}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))
        compose.runOnIdle { assertEquals(0, externalOpens) }
        compose.onNodeWithText("외부 앱에서 원본 열기").performClick()
        compose.runOnIdle { assertEquals(1, externalOpens) }
        compose.onAllNodesWithContentDescription("미리보기 닫기").assertCountEquals(0)
        compose.onNodeWithText("원본 열기").performScrollTo().assertIsDisplayed()
    }

    @Test fun photoErrorCanRetryAfterFileBecomesAvailable() {
        val file = File(compose.activity.cacheDir, "preview-${System.nanoTime()}.jpg").also { files += it }
        val item = media(file)
        compose.setContent { GalliPickTheme { MediaPreviewDialog(item, {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("사진을 불러올 수 없습니다. 파일과 접근 권한을 확인해 주세요.").fetchSemanticsNodes().isNotEmpty() }
        writeImage(file, 96, 48)
        compose.onNodeWithText("다시 시도").performClick()
        waitForPhoto(item)
    }

    @Test fun stoppedPhotoDecodesSameUriAgainOnForeground() {
        val file = imageFile(160, 80)
        val item = media(file)
        compose.setContent { GalliPickTheme { MediaPreviewDialog(item, {}, {}) } }
        waitForPhoto(item)
        compose.onNodeWithText("미리보기 160 × 80 · 긴 변 최대 2,048px").assertIsDisplayed()
        // Dialog supplies its host Activity's ViewTreeLifecycleOwner, overriding outer locals.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, compose.activityRule.scenario.state)
        // Keep URI and MediaItem identity unchanged: foregrounding must decode a fresh bitmap.
        writeImage(file, 96, 48)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val refreshedDimensions = "미리보기 96 × 48 · 긴 변 최대 2,048px"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(refreshedDimensions).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(refreshedDimensions).assertIsDisplayed()
    }

    @Test fun missingVideoShowsRecoverableErrorAndDisabledPlaybackControls() {
        val item = media(File(compose.activity.cacheDir, "missing-${System.nanoTime()}.mp4"), MediaKind.VIDEO)
        compose.setContent { GalliPickTheme { MediaPreviewDialog(item, {}, {}) } }
        val error = "영상을 재생할 수 없습니다. 파일과 접근 권한을 확인해 주세요."
        compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("재생").assertIsNotEnabled()
        compose.onNodeWithContentDescription("영상 재생 위치").assertIsNotEnabled()
        compose.onNodeWithText("다시 시도").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun stoppedAndDisposedVideoReleaseEachNativeViewWithoutAutoplay() {
        val item = media(File(compose.activity.cacheDir, "session-test.mp4"), MediaKind.VIDEO)
        val views = mutableListOf<CountingVideoView>()
        var visible by mutableStateOf(true)
        var displayedItem by mutableStateOf(item)
        lateinit var owner: TestLifecycleOwner
        compose.runOnUiThread { owner = TestLifecycleOwner() }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                GalliPickTheme { if (visible) VideoPreview(displayedItem, createView = { CountingVideoView(it).also(views::add) }) }
            }
        }
        compose.runOnIdle { assertEquals(1, views.size); assertEquals(0, views.single().starts) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(1, views.first().stops) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, views.size); assertEquals(0, views.last().starts) }
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(1, views[1].stops); assertEquals(3, views.size); assertEquals(0, views.last().starts) }
        compose.runOnIdle { displayedItem = item.copy(uri = "file:///changed-preview.mp4", generationModified = 1) }
        compose.runOnIdle { assertEquals(1, views[2].stops); assertEquals(4, views.size); assertEquals(0, views.last().starts) }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertEquals(1, views.last().stops) }
    }

    @Test fun nativeVideoPreparesPlaysAndPausesAnActualBaselineClip() {
        val file = File(compose.activity.cacheDir, "preview-clip.mp4").also { files += it }
        InstrumentationRegistry.getInstrumentation().context.assets.open("preview-baseline.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        lateinit var view: VideoView
        compose.setContent { GalliPickTheme {
            VideoPreview(media(file, MediaKind.VIDEO), createView = { VideoView(it).also { native -> view = native } })
        } }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("재생").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.onNodeWithText("재생").assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            var advanced = false
            compose.runOnUiThread { advanced = view.currentPosition > 0 }
            advanced
        }
        compose.onNodeWithText("일시정지").performClick()
        compose.runOnIdle { assertTrue(!view.isPlaying); assertTrue(view.duration > 0) }
    }

    private fun waitForPhoto(item: MediaItem) {
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("사진 미리보기: ${item.displayName}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("사진 미리보기: ${item.displayName}").assertIsDisplayed()
    }

    private fun imageFile(width: Int, height: Int): File =
        File(compose.activity.cacheDir, "preview-${System.nanoTime()}.jpg").also { files += it; writeImage(it, width, height) }

    private fun writeImage(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(70, 130, 100))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally { bitmap.recycle() }
    }

    private fun media(file: File, kind: MediaKind = MediaKind.PHOTO) = MediaItem(
        uri = Uri.fromFile(file).toString(), id = 0, volume = "test", displayName = file.name, kind = kind,
        mimeType = if (kind == MediaKind.PHOTO) "image/jpeg" else "video/mp4", relativePath = "test/", sizeBytes = file.length(),
        width = 160, height = 80, durationMs = 0, dateTakenMs = 0, dateAddedMs = 0, dateModifiedMs = 0,
    )

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** Checks lifecycle ownership separately from codec/device playback support. */
    private class CountingVideoView(context: Context) : VideoView(context) {
        var starts = 0
        var stops = 0
        override fun setVideoURI(uri: Uri?) = Unit
        override fun start() { starts++ }
        override fun stopPlayback() { stops++; super.stopPlayback() }
    }
}
