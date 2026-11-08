package com.hebi.gallipick.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.Stage7aFixtureMedia
import com.hebi.gallipick.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Android viewport captures of actual Compose feature components, supplied with test-owned
 * MediaStore photographs and sample history. These are not full MainActivity navigation captures.
 */
@RunWith(AndroidJUnit4::class)
class Stage9GFeatureCapturesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Screen {
        ANALYSIS_COMPLETE, ANALYSIS_SCROLLED, ANALYSIS_RUNNING,
        SIMILAR_RESULTS, SIMILAR_COMPARE, BURST_RESULTS, BURST_COMPARE,
        SOFT_FOCUS_RESULTS, HISTORY, HISTORY_CONFIRM, HISTORY_EMPTY, HISTORY_DARK
    }

    @Test fun captureAllStage9FeatureSurfaces() {
        val context = compose.activity
        val screenshots = File(context.filesDir, "stage9g-feature-captures").apply { mkdirs() }
        screenshots.listFiles()?.forEach { it.delete() }
        val captured = JSONArray()
        val names = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        val samples = (0..2).map { index ->
            Stage7aFixtureMedia.insert(
                context,
                "EXAMPLE_stage9g_${index + 1}.jpg",
                "image/jpeg",
                Stage7aFixtureMedia.sceneJpeg(index + 3),
                now - 30_000L + index * 2_000L,
                "Pictures/GalliPick_Stage9G_EXAMPLE/",
            )
        }
        try {
            val photos = samples.mapIndexed { index, sample ->
                MediaItem(
                    uri = sample.uri.toString(),
                    id = ContentUris.parseId(sample.uri),
                    volume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    displayName = sample.name,
                    kind = MediaKind.PHOTO,
                    mimeType = "image/jpeg",
                    relativePath = "Pictures/GalliPick_Stage9G_EXAMPLE/",
                    sizeBytes = 48_000L + index * 4_000L,
                    width = 1200, height = 900, durationMs = 0,
                    dateTakenMs = now - 30_000L + index * 2_000L,
                    dateAddedMs = now, dateModifiedMs = now,
                )
            }
            val similar = SimilarPhotoGroup(
                "similar:stage9g",
                photos.take(2).map { SimilarPhoto(it, "0000000000000000") },
            )
            val burst = SimilarPhotoGroup(
                "burst:stage9g",
                photos.take(2).map { SimilarPhoto(it, "0000000000000000") },
            )
            val focus = listOf(
                SoftFocusCandidate(photos[0], 4.25, similar.id),
                SoftFocusCandidate(photos[2], 7.50, null),
            )
            val completed = AppUiState(
                access = MediaAccess.FULL,
                items = photos,
                photoAnalysis = PhotoAnalysisState(
                    phase = PhotoAnalysisPhase.COMPLETE, completed = 3, total = 3,
                ),
                similarGroups = listOf(similar),
                burstGroups = listOf(burst),
                softFocusCandidates = focus,
            )
            val running = completed.copy(
                photoAnalysis = PhotoAnalysisState(
                    phase = PhotoAnalysisPhase.RUNNING, completed = 2, total = 3,
                ),
            )
            val history = listOf(
                CleanupHistoryEntry(
                    "EXAMPLE-move", now - 7_200_000L, CleanupHistoryAction.MOVE_TO_TRASH,
                    3, 2, 7_500_000L,
                ),
                CleanupHistoryEntry(
                    "EXAMPLE-restore", now - 3_600_000L, CleanupHistoryAction.RESTORE,
                    1, 1, 3_500_000L,
                ),
            )
            var screen by mutableStateOf(Screen.ANALYSIS_COMPLETE)
            var dark by mutableStateOf(false)
            compose.setContent {
                GalliPickTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize(), color = AppBackground) {
                        when (screen) {
                            Screen.ANALYSIS_COMPLETE, Screen.ANALYSIS_SCROLLED ->
                                PhotoAnalysisScreen(
                                    state = completed, onBack = {}, onStart = {},
                                    onPause = {}, onResume = {}, requestAccess = {}, refresh = {},
                                )
                            Screen.ANALYSIS_RUNNING ->
                                PhotoAnalysisScreen(
                                    state = running, onBack = {}, onStart = {},
                                    onPause = {}, onResume = {}, requestAccess = {}, refresh = {},
                                )
                            Screen.SIMILAR_RESULTS ->
                                SimilarPhotoResultsScreen(listOf(similar), emptySet(), {}, {}, {})
                            Screen.SIMILAR_COMPARE ->
                                SimilarPhotoCompareScreen(
                                    group = similar, groupIndex = 1, groupCount = 1,
                                    selectedUris = emptySet(), protectedFolders = emptySet(),
                                    onBack = {}, onToggleCandidate = {}, onOpenMedia = {}, onReview = {},
                                )
                            Screen.BURST_RESULTS ->
                                BurstPhotoResultsScreen(listOf(burst), emptySet(), {}, {}, {})
                            Screen.BURST_COMPARE ->
                                SimilarPhotoCompareScreen(
                                    group = burst, groupIndex = 1, groupCount = 1,
                                    selectedUris = emptySet(), protectedFolders = emptySet(),
                                    onBack = {}, onToggleCandidate = {}, onOpenMedia = {}, onReview = {},
                                    title = "연속 촬영 사진 비교", groupTitle = "연속 촬영 묶음",
                                )
                            Screen.SOFT_FOCUS_RESULTS ->
                                SoftFocusResultsScreen(focus, emptySet(), {}, {}, {}, {})
                            Screen.HISTORY, Screen.HISTORY_CONFIRM, Screen.HISTORY_DARK ->
                                CleanupHistoryScreen(history, {}, {}, {})
                            Screen.HISTORY_EMPTY ->
                                CleanupHistoryScreen(emptyList(), {}, {}, {})
                        }
                    }
                }
            }

            compose.onNodeWithText("특징 분석 완료").assertExists()
            screenshot(screenshots, captured, names, "01-analysis-complete")
            compose.runOnIdle { screen = Screen.ANALYSIS_SCROLLED }
            compose.onNodeWithText("선명도 확인").performScrollTo().assertIsDisplayed()
            screenshot(screenshots, captured, names, "02-analysis-scrolled")

            compose.runOnIdle { screen = Screen.ANALYSIS_RUNNING }
            compose.onNodeWithText("사진 특징 분석 중").assertExists()
            screenshot(screenshots, captured, names, "03-analysis-running")

            compose.runOnIdle { screen = Screen.SIMILAR_RESULTS }
            compose.onNodeWithText("비슷한 사진 찾기").assertExists()
            screenshot(screenshots, captured, names, "04-similar-results")

            compose.runOnIdle { screen = Screen.SIMILAR_COMPARE }
            compose.onNodeWithText("사진 비교").assertExists()
            screenshot(screenshots, captured, names, "05-similar-compare")

            compose.runOnIdle { screen = Screen.BURST_RESULTS }
            compose.onNodeWithText("연속 촬영 사진").assertExists()
            screenshot(screenshots, captured, names, "06-burst-results")

            compose.runOnIdle { screen = Screen.BURST_COMPARE }
            compose.onNodeWithText("연속 촬영 사진 비교").assertExists()
            screenshot(screenshots, captured, names, "07-burst-compare")

            compose.runOnIdle { screen = Screen.SOFT_FOCUS_RESULTS }
            compose.onNodeWithText("선명도 확인").assertExists()
            screenshot(screenshots, captured, names, "08-soft-focus-results")

            compose.runOnIdle { screen = Screen.HISTORY }
            compose.onNodeWithText("정리 기록").assertExists()
            screenshot(screenshots, captured, names, "09-history-records")

            compose.runOnIdle { screen = Screen.HISTORY_CONFIRM }
            compose.onNodeWithText("전체 기록 삭제").performClick()
            compose.onNodeWithText("전체 삭제 확인").assertExists()
            screenshot(screenshots, captured, names, "10-history-confirm")

            compose.runOnIdle { screen = Screen.HISTORY_EMPTY }
            compose.onNodeWithText("정리 기록이 없습니다").assertExists()
            screenshot(screenshots, captured, names, "11-history-empty")

            compose.runOnIdle { dark = true; screen = Screen.HISTORY_DARK }
            compose.onNodeWithText("정리 기록").assertExists()
            screenshot(screenshots, captured, names, "12-history-dark")

            assertEquals(Screen.entries.size, names.size)
            val arguments = InstrumentationRegistry.getArguments()
            val manifest = JSONObject()
                .put("fixture", "stage9g-test-owned-MediaStore-jpegs")
                .put("captureType", "Compose feature components, not MainActivity")
                .put("profile", arguments.getString("captureProfile") ?: "unknown")
                .put("count", names.size)
                .put("captures", captured)
            File(screenshots, "stage9g-manifest.json").writeText(manifest.toString(2))
        } finally {
            samples.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
    }

    private fun screenshot(output: File, manifest: JSONArray, names: MutableSet<String>, name: String) {
        require(names.add(name)) { "Duplicate capture name $name" }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(240)
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Android viewport screenshot unavailable for $name"
        }
        try {
            val file = File(output, "$name.png")
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            manifest.put(JSONObject()
                .put("name", name)
                .put("file", file.name)
                .put("width", image.width)
                .put("height", image.height))
        } finally {
            image.recycle()
        }
    }
}
