package com.hebi.gallipick.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.Stage7aFixtureMedia
import com.hebi.gallipick.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoOcrNativeCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureOfflineOcrSearchModeAndMatchingResults() {
        val activity = compose.activity
        val output = File(activity.filesDir, "stage13a-ocr-captures").apply { mkdirs() }
        output.listFiles()?.forEach { it.delete() }
        val now = System.currentTimeMillis()
        val pictures = (0..1).map { i ->
            Stage7aFixtureMedia.insert(
                activity, "EXAMPLE_ocr_photo_" + (i + 1) + ".jpg", "image/jpeg",
                Stage7aFixtureMedia.sceneJpeg(i + 3), now - i * 1000,
                "Pictures/GalliPick_OCR_EXAMPLE/",
            )
        }
        try {
            val items = pictures.map { picture ->
                MediaItem(
                    uri = picture.uri.toString(), id = ContentUris.parseId(picture.uri),
                    volume = MediaStore.VOLUME_EXTERNAL_PRIMARY, displayName = picture.name,
                    kind = MediaKind.PHOTO, mimeType = "image/jpeg",
                    relativePath = "Pictures/GalliPick_OCR_EXAMPLE/",
                    sizeBytes = 52_000L, width = 1200, height = 900, durationMs = 0,
                    dateTakenMs = now, dateAddedMs = now, dateModifiedMs = now,
                    volumeVersion = "test-epoch",
                )
            }
            val notes = listOf(
                PhotoOcrEntry(items[0].uri, items[0].fingerprint, items[0].volumeVersion,
                    "서울 여행 숙소 확인 2026", OcrResultStatus.SUCCESS),
                PhotoOcrEntry(items[1].uri, items[1].fingerprint, items[1].volumeVersion,
                    "카페 결제 영수증", OcrResultStatus.SUCCESS),
            )
            val args = InstrumentationRegistry.getArguments()
            val theme = args.getString("captureTheme")?.lowercase() ?: "light"
            compose.setContent {
                SideEffect {
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = theme != "dark"
                        isAppearanceLightNavigationBars = theme != "dark"
                    }
                }
                GalliPickTheme(if (theme == "dark") AppThemeMode.DARK else AppThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize(), color = AppBackground) {
                        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            FilesScreenContent(
                                state = AppUiState(access = MediaAccess.FULL,
                                    items = items, filtered = items,
                                    ocrEntries = notes,
                                    ocrScan = OcrScanState(OcrPhase.COMPLETE, 2, 2)),
                                wide = maxWidth >= 650.dp, trash = {}, open = {}, requestAccess = {},
                                actions = FilesScreenActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {}),
                            )
                        }
                    }
                }
            }
            val captures = JSONArray()
            compose.onNodeWithText("사진 속 글자").performClick()
            compose.onNodeWithText("사진 속 글자 검색 · 로컬 OCR").assertExists()
            capture(output, "01-ocr-scope", captures)
            compose.onNodeWithContentDescription("사진 속 글자 검색").performTextInput("서울")
            compose.onNodeWithText("검색 완료").performClick()
            capture(output, "02-ocr-matching-results", captures)
            File(output, "stage13a-manifest.json").writeText(JSONObject()
                .put("profile", args.getString("captureProfile") ?: "unknown")
                .put("theme", theme)
                .put("count", 2).put("captures", captures)
                .put("fixture", "test-owned MediaStore JPEG + seeded local OCR text")
                .toString(2))
        } finally {
            pictures.forEach { activity.contentResolver.delete(it.uri, null, null) }
        }
    }

    private fun capture(folder: File, name: String, manifest: JSONArray) {
        var rendered: Bitmap? = null
        for (attempt in 1..8) {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(280)
            val bitmap = checkNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
            if (renderedApp(bitmap)) {
                rendered = bitmap
                break
            }
            bitmap.recycle()
        }
        val screenshot = checkNotNull(rendered) { "OCR UI did not render: " + name }
        try {
            val file = File(folder, name + ".png")
            file.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            manifest.put(JSONObject().put("name", name).put("file", file.name)
                .put("width", screenshot.width).put("height", screenshot.height))
        } finally {
            screenshot.recycle()
        }
    }

    private fun renderedApp(bitmap: Bitmap): Boolean {
        val colors = HashSet<Int>()
        val xStep = (bitmap.width / 75).coerceAtLeast(1)
        val yStep = (bitmap.height / 100).coerceAtLeast(1)
        for (y in bitmap.height / 10 until bitmap.height * 9 / 10 step yStep) {
            for (x in bitmap.width / 20 until bitmap.width * 19 / 20 step xStep) {
                val rgb = bitmap.getPixel(x, y)
                val red = ((rgb ushr 16) and 255) / 24
                val green = ((rgb ushr 8) and 255) / 24
                val blue = (rgb and 255) / 24
                colors += (red shl 8) or (green shl 4) or blue
                if (colors.size >= 8) return true
            }
        }
        return false
    }
}
