package com.hebi.gallipick.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.Stage7aFixtureMedia
import com.hebi.gallipick.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoReviewInboxCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureReviewListAndDetailWithActualLocalJpegs() {
        val context = compose.activity
        val output = File(context.filesDir, "stage12c-inbox-captures").apply { mkdirs() }
        output.listFiles()?.forEach { it.delete() }
        val pictures = (0..2).map { index ->
            Stage7aFixtureMedia.insert(
                context, "EXAMPLE_review_inbox_${index + 1}.jpg", "image/jpeg",
                Stage7aFixtureMedia.sceneJpeg(index + 3),
                System.currentTimeMillis() - (3 - index) * 2_000L,
                "Pictures/GalliPick_Review_Test/",
            )
        }
        try {
            val items = pictures.map { shot ->
                MediaItem(
                    uri = shot.uri.toString(), id = ContentUris.parseId(shot.uri),
                    volume = MediaStore.VOLUME_EXTERNAL_PRIMARY, displayName = shot.name,
                    kind = MediaKind.PHOTO, mimeType = "image/jpeg",
                    relativePath = "Pictures/GalliPick_Review_Test/",
                    sizeBytes = 48_000L, width = 1200, height = 900, durationMs = 0,
                    dateTakenMs = System.currentTimeMillis(),
                    dateAddedMs = System.currentTimeMillis(),
                    dateModifiedMs = System.currentTimeMillis(),
                )
            }
            val group = SimilarPhotoGroup("similar:capture", items.map {
                SimilarPhoto(it, "0000000000000000")
            })
            val notes = listOf(
                PhotoReviewEntry(group.id, items[0].uri, items[0].fingerprint,
                    items[0].volumeVersion, PhotoReviewDecision.KEEP, 1L),
                PhotoReviewEntry(group.id, items[1].uri, items[1].fingerprint,
                    items[1].volumeVersion, PhotoReviewDecision.DEFER, 2L),
            )
            val args = InstrumentationRegistry.getArguments()
            val theme = args.getString("captureTheme")?.lowercase() ?: "light"
            compose.setContent {
                SideEffect {
                    WindowCompat.getInsetsController(context.window, context.window.decorView).apply {
                        isAppearanceLightStatusBars = theme != "dark"
                        isAppearanceLightNavigationBars = theme != "dark"
                    }
                }
                GalliPickTheme(if (theme == "dark") AppThemeMode.DARK else AppThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize(), color = AppBackground) {
                        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            PhotoReviewInboxScreen(
                                groups = listOf(group), entries = notes, protectedFolders = emptySet(),
                                onBack = {}, onAnalyze = {}, onMark = { _, _, _ -> },
                                onCompare = { _, _ -> }, onConfirmCandidates = {}, onClear = {},
                            )
                        }
                    }
                }
            }
            val captures = JSONArray()
            compose.onNodeWithText("사진 검토함").assertExists()
            capture(output, "01-review-list", captures)
            compose.onNodeWithContentDescription("유사 사진 3장 검토").performClick()
            compose.onNodeWithTag("review-inbox-detail").assertExists()
            capture(output, "02-review-detail", captures)
            assertEquals(2, captures.length())
            File(output, "stage12c-manifest.json").writeText(JSONObject()
                .put("profile", args.getString("captureProfile") ?: "unknown")
                .put("theme", theme).put("count", 2)
                .put("fixture", "test-owned MediaStore JPEGs")
                .put("captures", captures)
                .toString(2))
        } finally {
            pictures.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
    }

    private fun capture(folder: File, name: String, manifest: JSONArray) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(350)
        val bmp = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val file = File(folder, "${name}.png")
            file.outputStream().use {
                check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            manifest.put(JSONObject()
                .put("name", name).put("file", file.name)
                .put("width", bmp.width).put("height", bmp.height))
        } finally {
            bmp.recycle()
        }
    }
}
