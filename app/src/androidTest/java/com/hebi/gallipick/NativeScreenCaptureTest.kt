package com.hebi.gallipick

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
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
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudBilling
import com.hebi.gallipick.data.cloud.CloudBillingOffer
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.CloudBillingSnapshot
import com.hebi.gallipick.data.cloud.CloudBillingUpdate
import com.hebi.gallipick.data.cloud.GALLIPICK_STANDALONE_AD_FREE
import com.hebi.gallipick.data.cloud.productionBillingProduct
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.ui.AppBackground
import com.hebi.gallipick.ui.CloudBillingSection
import com.hebi.gallipick.ui.CloudRoute
import com.hebi.gallipick.ui.CloudScreen
import com.hebi.gallipick.ui.GalliPickTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

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
    private val captureTheme = when (arguments.getString("captureTheme")?.lowercase()) {
        "dark" -> AppThemeMode.DARK
        else -> AppThemeMode.LIGHT
    }
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
        val originalFileListView = prefs.getBoolean("file_list_view", false).takeIf { prefs.contains("file_list_view") }
        var scenario: ActivityScenario<MainActivity>? = null
        var testFailure: Throwable? = null
        try {
            recordStage("prepare-output")
            outputDirectory
            // createEmptyComposeRule does not launch an activity before permissions/fixtures exist.
            recordStage("grant-media-access")
            grantMediaAccess()
            prefs.edit().remove("default_filter").putString("theme_mode", captureTheme.name)
                .putBoolean("file_list_view", false).commit()
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
                compose.onNodeWithContentDescription("파일 추가 작업").performClick()
                compose.onNodeWithText("목록 보기").performClick()
            }
            compose.onNodeWithContentDescription("파일 추가 작업").performClick()
            compose.onNodeWithText("격자 보기").performClick()
            captureDetail("02c-real-jpeg-detail") {
                val mediaList = hasContentDescription("미디어 파일 목록") or
                    hasContentDescription("기기와 클라우드 미디어 파일 목록")
                compose.onNode(mediaList)
                    .performScrollToNode(hasText("EXAMPLE_scene_20.jpg"))
                compose.onNodeWithText("EXAMPLE_scene_20.jpg").performClick()
                compose.onNodeWithText("미리보기").assertIsDisplayed()
            }
            dismissDialogIfPresent("닫기", contentDescription = true)
            dismissDialogIfPresent("파일 상세 닫기", contentDescription = true)
            navigate("정리")
            compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
            compose.onNodeWithContentDescription("정리로 돌아가기").assertIsDisplayed()
            compose.onNodeWithText("중복 정리").assertIsDisplayed()
            saveViewport("03-duplicates-before-scan")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("사진 비교").performScrollTo().assertIsDisplayed()
            saveViewport("04-cleanup")
            recordStage("stage9g-real-history")
            compose.onNodeWithContentDescription("정리 기록 열기").performScrollTo().performClick()
            compose.onNodeWithText("정리 기록이 없습니다").performScrollTo().assertIsDisplayed()
            saveStage9GMainViewport("01-cleanup-history-empty")
            compose.onNodeWithContentDescription("정리로 돌아가기").performClick()
            compose.onNodeWithText("사진 비교").performScrollTo().assertIsDisplayed()
            recordStage("stage9g-real-photo-analysis")
            compose.onNodeWithContentDescription("유사 사진 분석 열기").performScrollTo().performClick()
            compose.onNodeWithText("사진 분석 시작").performScrollTo().assertIsDisplayed()
            saveStage9GMainViewport("02-photo-analysis-idle")
            compose.onNodeWithContentDescription("사진 분석 닫기").performScrollTo().performClick()
            compose.onNodeWithText("사진 비교").performScrollTo().assertIsDisplayed()
            recordStage("stage9a-open-folder-storage")
            compose.onNodeWithContentDescription("폴더별 용량 분석 열기").performScrollTo().performClick()
            compose.onNodeWithText("폴더별 용량 분석").assertIsDisplayed()
            saveStage9AFolderViewport()
            compose.onNodeWithContentDescription("폴더별 용량 분석 닫기").performClick()
            compose.onNodeWithText("사진 비교").performScrollTo().assertIsDisplayed()
            navigate("설정")
            compose.onNodeWithText("다크 모드").performScrollTo().performClick().assertIsSelected()
            saveViewport("05-settings-dark")
            compose.onNodeWithText("라이트 모드").performScrollTo().performClick().assertIsSelected()
            saveViewport("05b-settings-light")
            if (captureTheme == AppThemeMode.DARK) {
                compose.onNodeWithText("다크 모드").performScrollTo().performClick().assertIsSelected()
            }
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
                if (compose.onAllNodesWithText("고급 조건 열기").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithText("고급 조건 열기").performScrollTo().performClick()
                }
                compose.onNodeWithText("파일 이름 검색").performScrollTo().performClick().performTextInput("EXAMPLE")
                compose.onNodeWithText("필터 적용").assertIsDisplayed()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            dismissDialogIfPresent("취소")

            captureDetail("09-duplicates-scanned") {
                navigate("정리")
                compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
                compose.onNodeWithText("검사 시작").performScrollTo().performClick()
                val groups = runBlocking {
                    withTimeout(90_000) {
                        app.repository.duplicates.first { it.size >= Stage7aFixtureMedia.MIN_DUPLICATE_GROUPS }
                    }
                }
                assertTrue("Rich fixture must yield at least three exact-duplicate groups", groups.size >= 3)
                compose.waitUntil(timeoutMillis = 30_000) {
                    compose.onAllNodesWithContentDescription("중복 파일 목록")
                        .fetchSemanticsNodes().isNotEmpty()
                }
                // Lazy grid rows need not be composed in a short landscape viewport.
                compose.onNodeWithContentDescription("중복 파일 목록")
                    .performScrollToNode(hasContentDescription("중복 묶음 1 비교"))
                compose.onNodeWithContentDescription("중복 묶음 1 비교").assertIsDisplayed()
            }
            captureDetail("10-duplicate-comparison") {
                compose.onNodeWithContentDescription("중복 파일 목록")
                    .performScrollToNode(hasContentDescription("중복 묶음 1 비교"))
                compose.onNodeWithContentDescription("중복 묶음 1 비교").performClick()
                compose.onNodeWithContentDescription("선택 파일 파일 비교 패널").assertIsDisplayed()
            }
            dismissDialogIfPresent("비교 닫기", contentDescription = true)

            assertTrue("All fifteen rich-fixture captures must exist", captures.length() == 15)
            assertTrue("Every detail capture must succeed: $detailFailures", detailFailures.length() == 0)
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
                    if (originalFileListView == null) remove("file_list_view") else putBoolean("file_list_view", originalFileListView)
                }.commit()
            }
            finish("write-manifest") { writeManifest(testFailure) }
            if (testFailure == null) finalizationFailure?.let { throw it }
        }
    }

    @Test fun captureBillingPlanSelection() {
        assumeTrue(
            "The subscription fixture is enabled only by captureBillingSelection",
            arguments.getString("captureBillingSelection") == "true",
        )
        val offers = billingCaptureOffers()
        val connection = billingCaptureConnection(offers)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.setContent {
                    GalliPickTheme(captureTheme) {
                        Surface(Modifier.fillMaxSize(), color = AppBackground) {
                            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                CloudScreen(connection, onBackFromAdRemoval = {}) { route ->
                                    check(route == CloudRoute.BILLING)
                                    CloudBillingSection(connection, activity) { _, _ -> }
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("요금제·결제").assertIsDisplayed()
            for (row in listOf("10 GB", "50 GB", "100 GB", "광고 제거만")) {
                compose.onNodeWithText(row).assertIsDisplayed()
            }
            compose.onNodeWithText("월간").performClick()
            compose.onNodeWithText("10 GB").performClick()
            compose.onNodeWithText("10 GB 구독하기 · 월 ₩1,900").assertIsDisplayed()
            saveViewport("billing-monthly-10gb")

            compose.onNodeWithText("연간").performClick()
            compose.onNodeWithText("10 GB").performClick()
            compose.onNodeWithText("10 GB 구독하기 · 연 ₩19,900").assertIsDisplayed()
            saveViewport("billing-annual-10gb")
        }
        File(outputDirectory, "$capturePrefix-billing-manifest.json").writeText(JSONObject()
            .put("screen", "CloudScreen/CloudBillingSection")
            .put("fixture", "synthetic KR quotes for four products; no Google Play or Firebase calls")
            .put("theme", captureTheme.name.lowercase(Locale.ROOT))
            .put("fontScale", app.resources.configuration.fontScale)
            .put("densityDpi", app.resources.configuration.densityDpi)
            .put("offers", JSONArray(offers.map { offer -> JSONObject()
                .put("productId", offer.product.productId)
                .put("basePlanId", offer.product.basePlanId)
                .put("formattedPrice", offer.formattedPrice) }))
            .put("captures", captures)
            .toString(2))
    }

    private fun billingCaptureOffers(): List<CloudBillingOffer> = listOf(
        Triple("gallipick_cloud_10gb", 1_900, null),
        Triple("gallipick_cloud_50gb", 8_900, null),
        Triple("gallipick_cloud_100gb", 15_900, null),
        Triple(GALLIPICK_STANDALONE_AD_FREE, 1_000, 9_900),
    ).flatMap { (productId, monthly, annual) ->
        listOfNotNull("monthly" to monthly, annual?.let { "annual" to it }).map { (basePlanId, price) ->
            val product = checkNotNull(productionBillingProduct(productId, basePlanId))
            val title = if (product.isStandaloneAdFree) "광고 제거만" else "${product.quotaBytes / 1_000_000_000L} GB"
            CloudBillingOffer(product, title, String.format(Locale.KOREA, "₩%,d", price), price * 1_000_000L, "KRW")
        }
    }

    private fun billingCaptureConnection(offers: List<CloudBillingOffer>): CloudAccountConnection {
        val identity = CloudAccountIdentity("billing-capture", "화면 확인 계정", null)
        val account = object : CloudAccountBackend {
            override val account = MutableStateFlow<CloudAccountIdentity?>(identity)
            override suspend fun signIn(googleIdToken: String): Unit = error("Capture fixture is already signed in")
            override fun signOut() { account.value = null }
            override suspend fun clearGoogleState() = Unit
        }
        val snapshot = CloudBillingSnapshot(
            CloudBillingPlan(enabled = true, products = offers.map { it.product }, testPurchasesOnly = false,
                cloudCheckoutAllowed = true),
            offers,
        )
        return CloudAccountConnection(
            session = CloudAccountSession(account),
            billing = object : CloudBilling {
                override fun open(uid: String): CloudBillingSession {
                    check(uid == identity.uid)
                    return object : CloudBillingSession {
                        override suspend fun loadPlan(): CloudBillingPlan = snapshot.plan
                        override suspend fun load(): CloudBillingSnapshot = snapshot
                        override suspend fun recover(): CloudBillingUpdate = error("Capture fixture cannot recover purchases")
                        override suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate =
                            error("Capture fixture cannot launch checkout")
                        override fun close() = Unit
                    }
                }
            },
            getGoogleIdToken = { error("Capture fixture cannot access Google accounts") },
        )
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
            val focusedQuery = compose.onNodeWithText("파일 이름 검색")
            // The real IME can resize the form after the initial pre-focus scroll.
            focusedQuery.assertIsFocused().performScrollTo().assertIsFocused().assertIsDisplayed()
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            compose.onNodeWithText("필터 적용").assertIsDisplayed()
            val field = focusedQuery.fetchSemanticsNode()
            assertTrue("The focused field must remain fully visible above the keyboard: visible=${field.boundsInRoot}, measured=${field.size}", field.boundsInRoot.height >= field.size.height - 1f)
            val actions = compose.onNodeWithContentDescription("필터 작업").fetchSemanticsNode().boundsInRoot
            assertTrue("The focused field must stay above the fixed filter actions", field.boundsInRoot.bottom <= actions.top + 1f)
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
            .put("captureTheme", captureTheme.name.lowercase())
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
