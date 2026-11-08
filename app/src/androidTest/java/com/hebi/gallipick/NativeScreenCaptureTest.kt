package com.hebi.gallipick

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hebi.gallipick.domain.MediaKind
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Capture-only instrumentation: launches the real activity and reads real MediaStore rows.
 * The EXAMPLE files are public, repository-owned fixtures, never a user's photo library.
 * The CI job controls display size/density; this test deliberately never changes rotation.
 */
@RunWith(AndroidJUnit4::class)
class NativeScreenCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as GalliPickApplication
    private val created = mutableListOf<Uri>()
    private val fixtureSamples = mutableListOf<Stage7aFixtureMedia.Sample>()
    private val captures = JSONArray()
    private val detailFailures = JSONArray()
    private val progress = JSONArray()
    private val systemUiAnrEvents = JSONArray()
    private val keyboardObservations = JSONArray()
    private var systemUiWaitClicks = 0
    private var currentStage = "not-started"
    private var failureStage: String? = null
    private val arguments = InstrumentationRegistry.getArguments()
    private val profile = (arguments.getString("captureProfile") ?: "viewport")
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
    private val capturePrefix = "native-example-media-$profile-${System.currentTimeMillis()}"
    private val outputDirectory by lazy {
        val requested = arguments.getString("additionalTestOutputDir")
        val directory = requested?.let(::File) ?: File(app.filesDir, "native-screen-captures")
        Log.i("NativeScreenCapture", "Output preflight: path=$directory source=${if (requested == null) "app.filesDir" else "additionalTestOutputDir"} uid=${android.os.Process.myUid()}")
        try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create capture directory" }
            val probe = File(directory, "$capturePrefix-write-probe.tmp")
            try {
                probe.outputStream().use { it.write(1) }
            } finally {
                probe.delete()
            }
        } catch (failure: Exception) {
            throw IllegalStateException("Capture output is not writable: path=$directory exists=${directory.exists()} directory=${directory.isDirectory} canWrite=${directory.canWrite()} uid=${android.os.Process.myUid()}", failure)
        }
        Log.i("NativeScreenCapture", "Output preflight passed: $directory")
        directory
    }

    @Test fun captureRealActivityWithExampleMedia() {
        val prefs = app.getSharedPreferences("filters", Context.MODE_PRIVATE)
        val originalFilter = prefs.getString("default_filter", null)
        val originalTheme = prefs.getString("theme_mode", null)
        var scenario: ActivityScenario<MainActivity>? = null
        var testFailure: Throwable? = null
        try {
            recordStage("prepare-output")
            outputDirectory
            // createEmptyComposeRule does not launch an activity before permissions/fixtures exist.
            recordStage("grant-media-access")
            grantMediaAccess()
            prefs.edit().remove("default_filter").putString("theme_mode", AppThemeMode.LIGHT.name).commit()
            recordStage("seed-example-media")
            seedExampleMedia()
            recordStage("load-example-catalog")
            runBlocking {
                app.repository.clearAnalysisCache()
                withTimeout(30_000) {
                    while (true) {
                        app.repository.refresh()
                        val visible = app.repository.media.first()
                        if (created.all { uri -> visible.any { it.uri == uri.toString() && it.sizeBytes > 0 } }) break
                        delay(150)
                    }
                }
            }
            recordStage("launch-main-activity")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            recordStage("await-initial-catalog")
            awaitCatalog()
            compose.onNodeWithText("GalliPick").assertIsDisplayed()
            saveViewport("01-home")

            // Save the primary screens first, so detail-step failures cannot erase the evidence.
            navigate("파일")
            compose.onNodeWithContentDescription("파일 조건 선택").assertIsDisplayed()
            saveViewport("02-files")
            captureDetail("02b-files-list") {
                compose.onNodeWithContentDescription("목록 보기").performClick()
                compose.onNodeWithContentDescription("그리드 보기").assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("그리드 보기").performClick()
            captureDetail("02c-real-jpeg-detail") {
                compose.onNodeWithContentDescription("미디어 파일 목록")
                    .performScrollToNode(hasText("EXAMPLE_scene_20.jpg"))
                compose.onNodeWithText("EXAMPLE_scene_20.jpg").performClick()
                compose.onNodeWithText("앱에서 미리보기").assertIsDisplayed()
            }
            dismissDialogIfPresent("닫기", contentDescription = true)
            dismissDialogIfPresent("파일 상세 닫기", contentDescription = true)
            navigate("정리")
            compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
            compose.onNodeWithContentDescription("정리로 돌아가기").assertIsDisplayed()
            compose.onNodeWithText("중복 정리").assertIsDisplayed()
            saveViewport("03-duplicates-before-scan")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("사진 비교").assertIsDisplayed()
            saveViewport("04-cleanup")
            recordStage("stage9g-real-history")
            compose.onNodeWithContentDescription("정리 기록 열기").performScrollTo().performClick()
            compose.onNodeWithText("정리 기록이 없습니다").assertIsDisplayed()
            saveStage9GMainViewport("01-cleanup-history-empty")
            compose.onNodeWithContentDescription("정리로 돌아가기").performClick()
            compose.onNodeWithText("사진 비교").assertIsDisplayed()
            recordStage("stage9g-real-photo-analysis")
            compose.onNodeWithContentDescription("유사 사진 분석 열기").performScrollTo().performClick()
            compose.onNodeWithText("사진 분석 시작").assertIsDisplayed()
            saveStage9GMainViewport("02-photo-analysis-idle")
            compose.onNodeWithContentDescription("사진 분석 닫기").performClick()
            compose.onNodeWithText("사진 비교").assertIsDisplayed()
            recordStage("stage9a-open-folder-storage")
            compose.onNodeWithContentDescription("폴더별 용량 분석 열기").performScrollTo().performClick()
            compose.onNodeWithText("폴더별 용량 분석").assertIsDisplayed()
            saveStage9AFolderViewport()
            compose.onNodeWithContentDescription("폴더별 용량 분석 닫기").performClick()
            compose.onNodeWithText("사진 비교").assertIsDisplayed()
            navigate("설정")
            compose.onNodeWithText("다크 모드").performScrollTo().performClick().assertIsSelected()
            saveViewport("05-settings-dark")
            compose.onNodeWithText("라이트 모드").performScrollTo().performClick().assertIsSelected()
            saveViewport("05b-settings-light")
            // Capture the real app's status-bar-safe trash route, not just the standalone modal fixture.
            compose.onNodeWithText("휴지통 열기").performScrollTo().performClick()
            compose.onNodeWithText("휴지통이 비어 있습니다").assertIsDisplayed()
            saveViewport("05c-trash-empty-real-activity")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("화면 테마").assertIsDisplayed()

            captureDetail("06-files-size-descending") {
                navigate("파일")
                chooseSort("용량 큰 순")
            }
            captureDetail("07-files-video-duration-descending") {
                navigate("파일")
                chooseSort("영상 긴 순")
            }
            captureDetail("08-files-filter-dialog") {
                navigate("파일")
                compose.onNodeWithContentDescription("파일 조건 선택").performScrollTo().performClick()
                compose.onNodeWithContentDescription("필터 닫기").assertIsDisplayed()
                compose.onNodeWithText("날짜 범위").assertIsDisplayed()
            }
            captureDetail("08b-filter-with-keyboard") {
                compose.onNodeWithText("파일 이름 검색").performScrollTo().performClick().performTextInput("EXAMPLE")
                compose.onNodeWithText("필터 적용").assertIsDisplayed()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            dismissDialogIfPresent("취소")

            captureDetail("09-duplicates-scanned") {
                navigate("정리")
                compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
                compose.onNodeWithText("검사 시작").performScrollTo().performClick()
                compose.waitUntil(timeoutMillis = 90_000) {
                    compose.onAllNodesWithContentDescription("중복 묶음 1 비교")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("중복 묶음 1 비교").performScrollTo()
                val groups = runBlocking {
                    withTimeout(90_000) {
                        app.repository.duplicates.first { it.size >= Stage7aFixtureMedia.MIN_DUPLICATE_GROUPS }
                    }
                }
                assertTrue("Rich fixture must yield at least three exact-duplicate groups", groups.size >= 3)
            }
            captureDetail("10-duplicate-comparison") {
                compose.onNodeWithContentDescription("중복 묶음 1 비교").performScrollTo().performClick()
                compose.onNodeWithContentDescription("선택 파일 파일 비교 패널").assertIsDisplayed()
            }
            dismissDialogIfPresent("비교 닫기", contentDescription = true)

            assertTrue("All fifteen rich-fixture captures must exist", captures.length() == 15)
            assertTrue("Every detail capture must succeed", detailFailures.length() == 0)
        } catch (failure: Throwable) {
            testFailure = failure
            failureStage = currentStage
            Log.e("NativeScreenCapture", "Capture failed at stage=$currentStage captures=${captures.length()} seeded=${created.size}", failure)
            throw failure
        } finally {
            var finalizationFailure: Throwable? = null
            fun finish(label: String, action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    Log.e("NativeScreenCapture", "Finalization failed: $label (originalStage=$failureStage)", failure)
                    val original = testFailure ?: finalizationFailure
                    if (original != null) original.addSuppressed(failure) else finalizationFailure = failure
                }
            }
            finish("close-activity") { scenario?.close() }
            finish("remove-example-media") {
                // All inserted URIs are owned by this test's target app, so no delete dialog is needed.
                runBlocking {
                    app.repository.clearAnalysisCache()
                    fixtureSamples.filter { it.protected }.forEach { app.repository.setProtected(it.uri.toString(), false) }
                    created.forEach { uri -> app.contentResolver.delete(uri, null, null) }
                    app.repository.refresh()
                }
            }
            finish("restore-settings") {
                prefs.edit().apply {
                    if (originalFilter == null) remove("default_filter") else putString("default_filter", originalFilter)
                    if (originalTheme == null) remove("theme_mode") else putString("theme_mode", originalTheme)
                }.commit()
            }
            finish("write-manifest") { writeManifest(testFailure) }
            if (testFailure == null) finalizationFailure?.let { throw it }
        }
    }

    private fun grantMediaAccess() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
    }

    private fun seedExampleMedia() {
        fixtureSamples += Stage7aFixtureMedia.seed(app, instrumentation.context.assets)
        created += fixtureSamples.map { it.uri }
        runBlocking {
            fixtureSamples.filter { it.protected }.forEach { sample ->
                app.repository.setProtected(sample.uri.toString(), true)
            }
        }
    }

    private fun awaitCatalog() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("불러오는 중").fetchSemanticsNodes().isEmpty()
                && app.repository.catalogReady.value
        }
        compose.waitForIdle()
    }

    private fun navigate(title: String) {
        recordStage("navigate-$title")
        val navigation = hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(navigation).assertIsDisplayed().performClick()
        compose.onNode(navigation).assertIsSelected()
        awaitCatalog()
    }

    private fun chooseSort(label: String) {
        compose.onNodeWithContentDescription("정렬 기준 선택").performClick()
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun captureDetail(name: String, action: () -> Unit) {
        try {
            recordStage("detail-$name")
            action()
            saveViewport(name)
        } catch (failure: Throwable) {
            Log.e("NativeScreenCapture", "Detail failed: $name", failure)
            detailFailures.put(JSONObject().put("step", name).put("error", failure.toString()))
            // Preserve the actual failed viewport too; do not substitute a synthetic UI.
            runCatching { saveViewport("$name-FAILED") }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
    }

    private fun dismissDialogIfPresent(label: String, contentDescription: Boolean = false) {
        val nodes = if (contentDescription) compose.onAllNodesWithContentDescription(label)
            else compose.onAllNodesWithText(label)
        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes.onFirst().performClick()
        compose.waitForIdle()
    }

    private fun saveStage9GMainViewport(name: String) {
        recordStage("stage9g-$name")
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        clearKnownSystemUiAnr(name)
        assertSystemUiAnrAbsent(name)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not provide a Stage9G real-app viewport for $name"
        }
        try {
            val directory = File(app.filesDir, "stage9g-native-captures")
            check(directory.isDirectory || directory.mkdirs()) { "Could not create Stage 9-G native directory" }
            val file = File(directory, "$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("NativeScreenCapture", "Saved Stage9G real-app capture $file ${bitmap.width}x${bitmap.height}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveStage9AFolderViewport() {
        val name = "stage9a-folder-storage"
        recordStage(name)
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        clearKnownSystemUiAnr(name)
        assertSystemUiAnrAbsent(name)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not provide a folder-usage viewport"
        }
        try {
            val directory = File(app.filesDir, "stage9a-screen-captures")
            check(directory.isDirectory || directory.mkdirs()) { "Could not create Stage 9-A screenshot directory" }
            val file = File(directory, "$capturePrefix-$name.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("NativeScreenCapture", "Saved Stage9A screenshot $file ${bitmap.width}x${bitmap.height}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveViewport(name: String) {
        recordStage("capture-$name")
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Android compositor/thumbnail work can finish after Compose reports itself idle.
        SystemClock.sleep(500)
        clearKnownSystemUiAnr(name)
        assertSystemUiAnrAbsent(name)
        if (name == "08b-filter-with-keyboard") {
            awaitVisibleKeyboard(name)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            compose.onNodeWithText("필터 적용").assertIsDisplayed()
            val field = compose.onNodeWithText("파일 이름 검색").assertIsDisplayed().fetchSemanticsNode()
            assertTrue("The focused field must remain fully visible above the keyboard: visible=${field.boundsInRoot}, measured=${field.size}", field.boundsInRoot.height >= field.size.height - 1f)
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not provide a full viewport screenshot for $name"
        }
        val filename = "$capturePrefix-$name.png"
        try {
            // Also reject an ANR that appeared between the preflight and the raw screenshot.
            assertSystemUiAnrAbsent(name)
            File(outputDirectory, filename).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            captures.put(JSONObject().put("screen", name).put("file", filename)
                .put("widthPixels", bitmap.width).put("heightPixels", bitmap.height))
            Log.i("NativeScreenCapture", "Saved $filename ${bitmap.width}x${bitmap.height}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitVisibleKeyboard(screen: String) {
        val automation = instrumentation.uiAutomation
        val serviceInfo = automation.serviceInfo
        val previousFlags = serviceInfo.flags
        var observed: Rect? = null
        try {
            serviceInfo.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = serviceInfo
            compose.waitUntil(timeoutMillis = 20_000) {
                observed = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
                    ?.takeIf { it.width() > 0 && it.height() >= 100 }
                observed != null
            }
            compose.waitForIdle()
            keyboardObservations.put(JSONObject().put("screen", screen).put("visible", true)
                .put("left", observed!!.left).put("top", observed!!.top)
                .put("right", observed!!.right).put("bottom", observed!!.bottom))
            Log.i("NativeScreenCapture", "Visible input-method window for $screen: $observed")
        } finally {
            serviceInfo.flags = previousFlags
            automation.serviceInfo = serviceInfo
        }
    }

    private fun clearKnownSystemUiAnr(screen: String) {
        repeat(3) { attempt ->
            val root = systemUiAnrRoot() ?: return
            val owner = root.packageName?.toString()
            check(owner == "android" || owner == "com.android.systemui") {
                "Refusing to interact with System UI ANR title from unexpected package: $owner"
            }
            val wait = root.findAccessibilityNodeInfosByText("Wait")
                .firstOrNull { it.text?.toString() == "Wait" }
            var button = wait
            // Some Android versions put the Wait label inside a clickable button row.
            repeat(3) { if (button != null && button?.isClickable != true) button = button?.parent }
            val clicked = button?.takeIf { it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (clicked) systemUiWaitClicks++
            systemUiAnrEvents.put(JSONObject().put("screen", screen).put("attempt", attempt + 1)
                .put("title", "System UI isn't responding").put("ownerPackage", owner)
                .put("action", "Wait").put("clicked", clicked)
                .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()))
            Log.i("NativeScreenCapture", "Emulator System UI ANR: screen=$screen attempt=${attempt + 1} owner=$owner WaitClicked=$clicked; this is not a GalliPick ANR")
            // A missing/stale Wait node is handled by rereading the active root next iteration.
            SystemClock.sleep(500)
        }
        assertSystemUiAnrAbsent(screen)
    }

    private fun systemUiAnrRoot(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return root.takeIf {
            it.findAccessibilityNodeInfosByText("System UI isn't responding")
                .any { node -> node.text?.toString() == "System UI isn't responding" }
        }
    }

    private fun assertSystemUiAnrAbsent(screen: String) {
        assertTrue("Refusing to save $screen while the emulator's System UI ANR dialog is visible",
            systemUiAnrRoot() == null)
    }

    private fun recordStage(stage: String) {
        currentStage = stage
        progress.put(JSONObject().put("stage", stage).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()))
        Log.i("NativeScreenCapture", "Stage=$stage captures=${captures.length()} seeded=${created.size}")
    }

    private fun writeManifest(testFailure: Throwable?) {
        File(outputDirectory, "$capturePrefix-manifest.json").writeText(JSONObject()
            .put("activity", MainActivity::class.java.name)
            .put("profile", profile)
            .put("fontScale", app.resources.configuration.fontScale)
            .put("densityDpi", app.resources.configuration.densityDpi)
            .put("captureMethod", "UiAutomation.takeScreenshot: full Android viewport")
            .put("fixture", "stage7a-rich-media-v1")
            .put("fixtureKind", "test-owned decoded JPEG/PNG and playable MP4; procedural scenes")
            .put("photos", fixtureSamples.count { it.kind == MediaKind.PHOTO })
            .put("videos", fixtureSamples.count { it.kind == MediaKind.VIDEO })
            .put("protectedMedia", fixtureSamples.count { it.protected })
            .put("minimumDuplicateGroups", Stage7aFixtureMedia.MIN_DUPLICATE_GROUPS)
            .put("exampleMedia", JSONArray(fixtureSamples.map { it.name }))
            .put("captures", captures)
            .put("detailFailures", detailFailures)
            .put("keyboardObservations", keyboardObservations)
            .put("systemUiAnrWaitClicks", systemUiWaitClicks)
            .put("systemUiAnrEvents", systemUiAnrEvents)
            .put("progress", progress)
            .put("failureStage", failureStage ?: JSONObject.NULL)
            .put("testFailure", testFailure?.toString() ?: JSONObject.NULL)
            .toString(2))
    }
}
