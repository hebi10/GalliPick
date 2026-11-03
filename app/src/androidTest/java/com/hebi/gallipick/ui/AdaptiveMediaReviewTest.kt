package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class AdaptiveMediaReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun wideComparisonShowsBothRolesAndNeverOffersAProtectedCopyCheckbox() {
        val group = DuplicateGroup("wide", listOf(media(1, "first.mp4"), media(2, "protected.mp4").copy(isProtected = true)))
        compose.setContent { GalliPickTheme(AppThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp), color = MaterialTheme.colorScheme.surface) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        val a = compose.onNodeWithContentDescription("A 파일 비교 패널").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithContentDescription("B 파일 비교 패널").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(a.right <= b.left)
        compose.onNodeWithContentDescription("A 영상 표시 영역").assertHeightIsAtLeast(80.dp)
        compose.onNodeWithContentDescription("B 영상 표시 영역").assertHeightIsAtLeast(80.dp)
        compose.onNodeWithText("남길 파일 · 보호됨").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 정리 후보 선택").assertDoesNotExist()
        saveScreenshot("duplicate-compare-wide-dark")
    }

    @Test fun changingTheKeeperUpdatesRolesWithoutSwappingTheComparisonPanels() {
        val group = DuplicateGroup("stable-panels", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val keeper = mutableStateOf(group.items.first().uri)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp)) {
                    DuplicateCompareContent(group, keeper.value, emptySet(), emptySet(), {}, {}, { _, uri -> keeper.value = uri }, {}, { _, _ -> })
                }
            }
        } }
        compose.onNodeWithContentDescription("B 남길 파일로 지정").performScrollTo().performClick()
        compose.onNode(hasText("first.mp4") and hasAnyAncestor(hasContentDescription("A 파일 비교 패널"))).assertIsDisplayed()
        compose.onNode(hasText("second.mp4") and hasAnyAncestor(hasContentDescription("B 파일 비교 패널"))).assertIsDisplayed()
        compose.onNodeWithText("남길 파일 · 직접 지정").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 정리 후보 선택").assertDoesNotExist()
        compose.onNodeWithContentDescription("A 정리 후보 선택").performScrollTo().assertIsEnabled()
    }

    @Test fun shortWideWindowUsesOnePreviewAndCanSwitchBetweenCopies() {
        val group = DuplicateGroup("short", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 280.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        compose.onNodeWithContentDescription("A 파일 비교 패널").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 파일 비교 패널").assertDoesNotExist()
        compose.onNodeWithTag("B 비교 탭").assert(hasText("B · 정리 후보")).performClick()
        compose.onNodeWithContentDescription("B 파일 비교 패널").assertIsDisplayed()
        compose.onNodeWithContentDescription("A 파일 비교 패널").assertDoesNotExist()
    }

    @Test fun comparisonCanBrowseAllCopiesWithoutShowingTheSameFileTwice() {
        val group = DuplicateGroup("three", listOf(media(1, "first.mp4"), media(2, "second.mp4"), media(3, "third.mp4")))
        var chosenKeeper: String? = null
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp)) { DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, uri -> chosenKeeper = uri }, {}, { _, _ -> }) }
            }
        } }
        compose.onNodeWithContentDescription("B 비교 파일 변경").assert(hasText("비교 대상 변경")).performClick()
        compose.onNodeWithText("third.mp4").performClick()
        compose.onNodeWithText("third.mp4").assertIsDisplayed()
        compose.onNodeWithText("second.mp4").assertDoesNotExist()
        compose.onNodeWithContentDescription("B 비교 파일 변경").performClick()
        compose.onAllNodesWithText("first.mp4").onLast().performClick()
        compose.onAllNodesWithText("first.mp4").assertCountEquals(1)
        compose.onAllNodesWithText("third.mp4").assertCountEquals(1)
        compose.runOnIdle { assertNull("Browsing another copy must not choose a keeper", chosenKeeper) }
    }

    @Test fun roleTabsFollowTheDisplayedFilesAfterChoosingANewKeeper() {
        val group = DuplicateGroup("role-tabs", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val keeper = mutableStateOf(group.items.first().uri)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(400.dp, 800.dp)) {
                    DuplicateCompareContent(group, keeper.value, emptySet(), emptySet(), {}, {}, { _, uri -> keeper.value = uri }, {}, { _, _ -> })
                }
            }
        } }
        compose.onNodeWithTag("A 비교 탭").assert(hasText("A · 남길 파일"))
        compose.onNodeWithTag("B 비교 탭").assert(hasText("B · 정리 후보")).performClick()
        compose.onNodeWithContentDescription("B 남길 파일로 지정").performScrollTo().performClick()
        compose.onNodeWithTag("B 비교 탭").assert(hasText("B · 남길 파일"))
        compose.onNodeWithTag("A 비교 탭").assert(hasText("A · 정리 후보")).performClick()
        compose.onNodeWithText("first.mp4").assertIsDisplayed()
        compose.onNodeWithContentDescription("A 정리 후보 선택").performScrollTo().assertIsEnabled()
    }

    @Test fun protectedCopiesHaveDistinctRoleTabsThatOpenTheirOwnFile() {
        val group = DuplicateGroup("protected-tabs", listOf(
            media(1, "first-protected.mp4").copy(isProtected = true),
            media(2, "second-protected.mp4").copy(isProtected = true),
        ))
        var chosenKeeper: String? = null
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(400.dp, 800.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, uri -> chosenKeeper = uri }, {}, { _, _ -> })
                }
            }
        } }
        compose.onNodeWithTag("A 비교 탭").assert(hasText("A · 남길 파일"))
        compose.onNodeWithTag("B 비교 탭").assert(hasText("B · 남길 파일"))
        compose.onNodeWithText("B · 남길 파일").performClick()
        compose.onNodeWithText("second-protected.mp4").assertIsDisplayed()
        compose.onNodeWithText("first-protected.mp4").assertDoesNotExist()
        compose.onNodeWithContentDescription("B 정리 후보 선택").assertDoesNotExist()
        compose.onNodeWithText("A · 남길 파일").performClick()
        compose.onNodeWithText("first-protected.mp4").assertIsDisplayed()
        compose.onNodeWithText("second-protected.mp4").assertDoesNotExist()
        compose.runOnIdle { assertNull("Switching between protected copies must not choose a keeper", chosenKeeper) }
    }

    @Test fun tabletPortraitCapsPreviewAndKeepsTheKeeperDecisionNearMetadata() {
        val group = DuplicateGroup("portrait", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.5f)) {
                Surface(Modifier.requiredSize(800.dp, 1100.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        val panel = compose.onNodeWithContentDescription("B 파일 비교 패널").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithContentDescription("B 영상 표시 영역").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val decision = compose.onNodeWithContentDescription("B 남길 파일로 지정").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Portrait preview must leave room for the file decision", preview.height <= 280f * .5f + 1f)
        assertTrue("Keeper decision must stay close to its preview and metadata", decision.top - panel.top < 600f * .5f)
        saveScreenshot("duplicate-compare-tablet-portrait")
    }

    @Test fun bothComparisonFilesShowTheirActualDateBasisAndUnknownFallback() {
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val group = DuplicateGroup("dates", listOf(
                media(1, "captured.mp4").copy(dateTakenMs = 1_704_067_200_000L),
                media(2, "stored.mp4").copy(dateTakenMs = 0, dateAddedMs = 1_704_153_600_000L),
                media(3, "unknown.mp4").copy(dateTakenMs = 0, dateAddedMs = 0),
            ))
            compose.setContent { GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(1000.dp, 640.dp)) {
                        DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                    }
                }
            } }
            compose.onNodeWithText("촬영일 2024.01.01 00:00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("저장일 2024.01.02 00:00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("B 비교 파일 변경").performClick()
            compose.onNodeWithText("unknown.mp4").performClick()
            compose.onNodeWithText("저장일 정보 없음").performScrollTo().assertIsDisplayed()
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun protectingACandidateKeepsItsPanelAndRemovesItsDeletionSelection() {
        val original = DuplicateGroup("protect", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val group = mutableStateOf(original)
        var chosenKeeper: String? = null
        var protectedUri: String? = null
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp)) {
                    DuplicateCompareContent(group.value, original.items.first().uri, setOf(original.items[1].uri), emptySet(), {}, {},
                        { _, uri -> chosenKeeper = uri }, {}, { item, protect ->
                            protectedUri = item.uri
                            group.value = group.value.copy(items = group.value.items.map { if (it.uri == item.uri) it.copy(isProtected = protect) else it })
                        })
                }
            }
        } }
        compose.onNodeWithContentDescription("B 정리 후보 선택").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("B 보호").performScrollTo().performClick()
        compose.onNodeWithText("second.mp4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("남길 파일 · 보호됨").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 정리 후보 선택").assertDoesNotExist()
        compose.onNodeWithContentDescription("B 보호 해제").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(original.items[1].uri, protectedUri); assertNull(chosenKeeper) }
    }

    @Test fun shortNarrowLargeTextAllowsRealSwipesToTheCandidateControls() {
        val group = DuplicateGroup("large-text", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.9f, fontScale = 1.6f)) {
                Surface(Modifier.requiredSize(320.dp, 280.dp)) { DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> }) }
            }
        } }
        compose.onNodeWithTag("B 비교 탭").performScrollTo().performClick()
        compose.onNodeWithContentDescription("B 영상 재생").performScrollTo().assertIsDisplayed()
        val candidate = compose.onNodeWithContentDescription("B 정리 후보 선택").performScrollTo()
        val body = compose.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("B 파일 비교 패널")))
        saveScreenshot("duplicate-compare-short-before-swipe")
        repeat(6) { if (runCatching { candidate.assertIsDisplayed() }.isFailure) body.performTouchInput { swipeUp() } }
        candidate.assertIsDisplayed().assertIsEnabled()
        saveScreenshot("duplicate-compare-short-after-swipe")
    }

    @Test fun pendingNoticeStaysVisibleWhenLargeTextComparisonActionsScroll() {
        val original = DuplicateGroup("pending-large-text", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val group = mutableStateOf(original)
        val pending = mutableStateOf(false)
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.9f, fontScale = 1.6f)) {
                Surface(Modifier.requiredSize(320.dp, 280.dp)) {
                    DuplicateCompareContent(group.value, original.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {},
                        { item, protect ->
                            group.value = group.value.copy(items = group.value.items.map { if (it.uri == item.uri) it.copy(isProtected = protect) else it })
                            pending.value = true
                        }, actionsEnabled = !pending.value)
                }
            }
        } }
        compose.onNodeWithTag("B 비교 탭").performScrollTo().performClick()
        compose.onNodeWithContentDescription("B 보호").performScrollTo().performClick()
        compose.onNodeWithContentDescription("비교 닫기").assertIsDisplayed()
        compose.onNodeWithContentDescription("중복 결과 확인 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 보호 해제").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("중복 결과 확인 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 정리 후보 선택").assertDoesNotExist()
        saveScreenshot("duplicate-compare-pending-short-large-text")
    }

    @Test fun shortWideFileDetailsKeepThePreviewActionBesideMetadata() {
        val item = media(1, "가로 화면에서도 전체 이름을 확인하는 영상.mp4")
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 320.dp)) { MediaDetail(item, {}, {}) }
            }
        } }
        val action = compose.onNodeWithText("앱에서 미리보기").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText(item.displayName).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(action.right <= name.left)
        compose.onNodeWithText("Download/").performScrollTo().assertIsDisplayed()
    }

    @Test fun boundaryWidthAndWideLargeTextKeepANonzeroVideoViewport() {
        val group = DuplicateGroup("boundary", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val window = mutableStateOf(Triple(560, 280, 1f))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f, fontScale = window.value.third)) {
                Surface(Modifier.requiredSize(window.value.first.dp, window.value.second.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        val scenarios = listOf(
            Triple(560, 280, 1f), Triple(1000, 280, 1.6f),
            Triple(320, 360, 1f), Triple(320, 420, 1f), Triple(800, 360, 1f),
        )
        scenarios.forEach { scenario ->
            compose.runOnIdle { window.value = scenario }
            val area = compose.onNodeWithContentDescription("A 영상 표시 영역")
            if (runCatching { area.assert(hasAnyAncestor(hasScrollAction())) }.isSuccess) area.performScrollTo()
            area.assertIsDisplayed().assertHeightIsAtLeast(80.dp)
            assertTrue("Visible media area must be at least 80dp in $scenario", area.fetchSemanticsNode().boundsInRoot.height >= 80f * .6f - 1f)
            saveScreenshot("duplicate-compare-${scenario.first}-${scenario.second}-${scenario.third}")
        }
    }

    @Test fun startingAPeerVideoPausesTheFirstAndLifecycleNeverAutoplays() {
        val clips = clips()
        val first = media(1, "first.mp4").copy(uri = Uri.fromFile(clips[0]).toString())
        val second = media(2, "second.mp4").copy(uri = Uri.fromFile(clips[1]).toString())
        val coordinator = PreviewPlaybackCoordinator()
        val views = mutableMapOf<String, VideoView>()
        val created = mutableListOf<VideoView>()
        lateinit var owner: TestOwner
        compose.runOnUiThread { owner = TestOwner() }
        try {
            compose.setContent { GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f), LocalLifecycleOwner provides owner) {
                    Row(Modifier.requiredSize(1000.dp, 500.dp)) {
                        VideoPreview(first, Modifier.weight(1f), createView = { VideoView(it).also { view -> views["A"] = view; created += view } }, coordinator = coordinator, controlLabel = "A")
                        VideoPreview(second, Modifier.weight(1f), createView = { VideoView(it).also { view -> views["B"] = view; created += view } }, coordinator = coordinator, controlLabel = "B")
                    }
                }
            } }
            waitForPlayers()
            compose.runOnIdle { assertTrue(views.values.none { it.isPlaying }) }
            compose.onNodeWithContentDescription("A 영상 재생").performClick()
            compose.waitUntil(20_000) { var playing = false; compose.runOnUiThread { playing = views["A"]?.isPlaying == true }; playing }
            compose.onNodeWithContentDescription("B 영상 재생").performClick()
            compose.runOnIdle { assertFalse(views.getValue("A").isPlaying); assertTrue(views.getValue("B").isPlaying) }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            compose.runOnIdle { assertTrue(created.none { it.isPlaying }) }
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(30_000) { created.size == 4 }
            compose.runOnIdle { assertTrue(created.none { it.isPlaying }) }
        } finally { clips.forEach(File::delete) }
    }

    @Test fun externalFileOpenPausesTheActiveVideoBeforeCallingTheResolver() {
        val clips = clips()
        val group = DuplicateGroup("external", clips.mapIndexed { i, f -> media(i.toLong(), "copy-$i.mp4").copy(uri = Uri.fromFile(f).toString()) })
        var pausedBeforeOpen = false
        var openedUri: String? = null
        try {
            compose.setContent { GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(1000.dp, 640.dp)) {
                        DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, { item ->
                            val players = videoViews(compose.activity.window.decorView)
                            pausedBeforeOpen = players.size == 2 && players.none { it.isPlaying }
                            openedUri = item.uri
                        }, { _, _ -> }, {}, { _, _ -> })
                    }
                }
            } }
            waitForPlayers()
            compose.onNodeWithContentDescription("A 영상 재생").performClick()
            compose.onNodeWithContentDescription("A 영상 일시정지").assertIsDisplayed()
            compose.onNodeWithContentDescription("B 외부 앱에서 파일 열기").performScrollTo().performClick()
            compose.runOnIdle { assertTrue(pausedBeforeOpen); assertEquals(group.items[1].uri, openedUri) }
        } finally { clips.forEach(File::delete) }
    }

    private fun waitForPlayers() = compose.waitUntil(30_000) {
        listOf("A 영상 재생", "B 영상 재생").all { label -> compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
    }

    private fun clips(): List<File> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return (1..2).map { index -> File(instrumentation.targetContext.cacheDir, "duplicate-$index-${System.nanoTime()}.mp4").also { file ->
            instrumentation.context.assets.open("preview-baseline.mp4").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        } }
    }
    private fun media(id: Long, name: String) = MediaItem("file:///missing-compare-$id.mp4", id, "test", name, MediaKind.VIDEO, "video/mp4", "Download/", 1_234_567, 1920, 1080, 65_000, id, id, id)
    private class TestOwner : LifecycleOwner { val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }; override val lifecycle get() = registry }
    private fun videoViews(view: View): List<VideoView> = when (view) { is VideoView -> listOf(view); is ViewGroup -> (0 until view.childCount).flatMap { videoViews(view.getChildAt(it)) }; else -> emptyList() }
    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: File(instrumentation.targetContext.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
