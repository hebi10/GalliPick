package com.hebi.gallipick.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.provider.MediaStore
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
import com.hebi.gallipick.Stage7aFixtureMedia
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

    @Test fun wideComparisonShowsKeeperFirstAndProtectedCopyCannotBeSelectedForTrash() {
        val group = DuplicateGroup("wide", listOf(media(1, "first.mp4"), media(2, "protected.mp4").copy(isProtected = true)))
        compose.setContent { GalliPickTheme(AppThemeMode.DARK) {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp), color = MaterialTheme.colorScheme.surface) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        compose.onNodeWithContentDescription("중복 파일 가로 비교 목록").assertIsDisplayed()
        compose.onNodeWithContentDescription("선택 파일 파일 비교 패널").assertIsDisplayed()
        focusComparisonFile("protected.mp4")
        compose.onNode(hasText("남길 파일 · 보호됨") and hasAnyAncestor(hasContentDescription("선택 파일 파일 비교 패널"))).assertIsDisplayed()
        compose.onNodeWithContentDescription("선택 파일 정리 후보 선택").assertDoesNotExist()
        saveScreenshot("duplicate-compare-wide-dark")
    }

    @Test fun changingKeeperMovesItToTheFirstGalleryPositionWithoutSelectingFiles() {
        val group = DuplicateGroup("keeper-change", listOf(media(1, "first.mp4"), media(2, "second.mp4"), media(3, "third.mp4")))
        val keeper = mutableStateOf(group.items.first().uri)
        var moved = false
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp)) {
                    DuplicateCompareContent(group, keeper.value, emptySet(), emptySet(), {}, {}, { _, uri -> keeper.value = uri }, { moved = true }, { _, _ -> })
                }
            }
        } }
        focusComparisonFile("third.mp4")
        scrollComparisonControlIntoView("선택 파일 남길 파일로 지정").performClick()
        compose.runOnIdle { assertEquals(group.items[2].uri, keeper.value); assertFalse(moved) }
        val movedCard = compose.onNodeWithContentDescription("third.mp4 비교 대상").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val previousCard = compose.onNodeWithContentDescription("first.mp4 비교 대상").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("New keeper should be first", movedCard.left < previousCard.left)
        compose.onNode(hasText("남길 파일 · 직접 지정") and hasAnyAncestor(hasContentDescription("선택 파일 파일 비교 패널"))).assertIsDisplayed()
        compose.onNodeWithContentDescription("선택 파일 정리 후보 선택").assertDoesNotExist()
    }

    @Test fun shortWideWindowSwitchesTheSinglePreviewByTappingTheStrip() {
        val group = DuplicateGroup("short", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 280.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        compose.onNodeWithContentDescription("선택 파일 파일 비교 패널").assertExists()
        compose.onNodeWithTag("B 비교 탭").assertDoesNotExist()
        focusComparisonFile("second.mp4")
        compose.onNode(hasText("second.mp4") and hasAnyAncestor(hasContentDescription("선택 파일 파일 비교 패널"))).assertExists()
    }

    @Test fun compactPhotoComparisonUsesOnlyThePageVerticalScroll() {
        val context = compose.activity
        val now = System.currentTimeMillis()
        val samples = (1..2).map { index ->
            Stage7aFixtureMedia.insert(
                context, "EXAMPLE_compare_scroll_${index}.jpg", "image/jpeg",
                Stage7aFixtureMedia.sceneJpeg(index), now,
                "Pictures/GalliPick_Compare_Scroll_Test/",
            )
        }
        try {
            val files = samples.mapIndexed { index, sample ->
                media(index + 1L, sample.name).copy(
                    uri = sample.uri.toString(),
                    id = ContentUris.parseId(sample.uri),
                    volume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    kind = MediaKind.PHOTO,
                    mimeType = "image/jpeg",
                )
            }
            val first = files[0]
            val group = DuplicateGroup("photo-one-scroll", files)
            compose.setContent {
                GalliPickTheme {
                    CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                        Surface(Modifier.requiredSize(360.dp, 420.dp)) {
                            DuplicateCompareContent(
                                group, first.uri, emptySet(), emptySet(),
                                {}, {}, { _, _ -> }, {}, { _, _ -> },
                            )
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("중복 파일 가로 비교 목록").assertIsDisplayed()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("사진 미리보기: ${first.displayName}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val verticalScrolls = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).fetchSemanticsNodes()
            assertEquals("Loaded photo comparison must have one vertical scroller", 1, verticalScrolls.size)
            focusComparisonFile(files[1].displayName)
            scrollComparisonControlIntoView("선택 파일 남길 파일로 지정")
                .assertIsDisplayed().assertIsEnabled()
            scrollComparisonControlIntoView("선택 파일 보호")
                .assertIsDisplayed().assertIsEnabled()
        } finally {
            samples.forEach { sample -> context.contentResolver.delete(sample.uri, null, null) }
        }
    }

    @Test fun galleryCanBrowseFiveCopiesWithoutChangingKeeperOrTrashState() {
        val group = DuplicateGroup("five", (1L..5L).map { media(it, "copy-$it.mp4") })
        var keeperChanged = false
        var selected = false
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(400.dp, 800.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {},
                        { _, _ -> keeperChanged = true }, { selected = true }, { _, _ -> })
                }
            }
        } }
        focusComparisonFile("copy-5.mp4")
        compose.onNode(hasText("copy-5.mp4") and hasAnyAncestor(hasContentDescription("선택 파일 파일 비교 패널"))).assertIsDisplayed()
        compose.runOnIdle { assertFalse(keeperChanged); assertFalse(selected) }
    }

    @Test fun protectedCopiesRemainReviewableWithoutTrashCheckbox() {
        val group = DuplicateGroup("protected", listOf(
            media(1, "first-protected.mp4").copy(isProtected = true),
            media(2, "second-protected.mp4").copy(isProtected = true),
        ))
        var changed = false
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(400.dp, 800.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> changed = true }, {}, { _, _ -> })
                }
            }
        } }
        focusComparisonFile("second-protected.mp4")
        compose.onNode(hasText("남길 파일 · 보호됨") and hasAnyAncestor(hasContentDescription("선택 파일 파일 비교 패널"))).assertIsDisplayed()
        compose.onNodeWithContentDescription("선택 파일 정리 후보 선택").assertDoesNotExist()
        focusComparisonFile("first-protected.mp4")
        compose.runOnIdle { assertFalse(changed) }
    }

    @Test fun tabletPortraitPreviewKeepsSelectedFileMetadataAccessible() {
        val group = DuplicateGroup("portrait", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.5f)) {
                Surface(Modifier.requiredSize(800.dp, 1100.dp)) {
                    DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> })
                }
            }
        } }
        focusComparisonFile("second.mp4")
        compose.onNodeWithContentDescription("선택 파일 영상 표시 영역").assertHeightIsAtLeast(80.dp)
        scrollComparisonControlIntoView("선택 파일 남길 파일로 지정").assertIsEnabled()
        saveScreenshot("duplicate-compare-tablet-portrait")
    }

    @Test fun focusedFilesShowTheirOwnCaptureOrAddedDateBasis() {
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
            focusComparisonFile("stored.mp4")
            compose.onNodeWithText("저장일 2024.01.02 00:00").performScrollTo().assertIsDisplayed()
            focusComparisonFile("unknown.mp4")
            compose.onNodeWithText("저장일 정보 없음").performScrollTo().assertIsDisplayed()
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun protectingAFocusedCandidateRemovesItsTrashCheckbox() {
        val original = DuplicateGroup("protect", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val group = mutableStateOf(original)
        var changed: String? = null
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(1000.dp, 640.dp)) {
                    DuplicateCompareContent(group.value, original.items.first().uri, setOf(original.items[1].uri), emptySet(), {}, {},
                        { _, _ -> }, {}, { item, protect ->
                            changed = item.uri
                            group.value = group.value.copy(items = group.value.items.map { if (it.uri == item.uri) it.copy(isProtected = protect) else it })
                        })
                }
            }
        } }
        focusComparisonFile("second.mp4")
        scrollComparisonControlIntoView("선택 파일 정리 후보 선택").assertIsOn()
        scrollComparisonControlIntoView("선택 파일 보호").performClick()
        compose.onNode(hasText("남길 파일 · 보호됨") and hasAnyAncestor(hasContentDescription("선택 파일 파일 비교 패널"))).assertIsDisplayed()
        compose.onNodeWithContentDescription("선택 파일 정리 후보 선택").assertDoesNotExist()
        compose.onNodeWithContentDescription("선택 파일 보호 해제").assertIsEnabled()
        compose.runOnIdle { assertEquals(original.items[1].uri, changed) }
    }

    @Test fun shortNarrowLargeTextCanReachCandidateControlsByScrolling() {
        val group = DuplicateGroup("large-text", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.9f, fontScale = 1.6f)) {
                Surface(Modifier.requiredSize(320.dp, 280.dp)) { DuplicateCompareContent(group, group.items.first().uri, emptySet(), emptySet(), {}, {}, { _, _ -> }, {}, { _, _ -> }) }
            }
        } }
        focusComparisonFile("second.mp4")
        compose.onNodeWithContentDescription("선택 파일 영상 재생").performScrollTo().assertExists()
        val control = scrollComparisonControlIntoView("선택 파일 정리 후보 선택")
        control.assertIsDisplayed().assertIsEnabled()
        saveScreenshot("duplicate-compare-short-after-swipe")
    }

    @Test fun pendingRevalidationDisablesProtectionWithoutClosingComparison() {
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
        focusComparisonFile("second.mp4")
        scrollComparisonControlIntoView("선택 파일 보호").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(pending.value) }
        compose.onNodeWithContentDescription("비교 닫기").assertIsDisplayed()
        compose.onNodeWithContentDescription("중복 결과 확인 중").assertIsDisplayed()
        scrollComparisonControlIntoView("선택 파일 보호 해제").assertIsNotEnabled()
        compose.onNodeWithContentDescription("선택 파일 정리 후보 선택").assertDoesNotExist()
        saveScreenshot("duplicate-compare-pending-short-large-text")
    }

    private fun focusComparisonFile(name: String) {
        compose.onNodeWithContentDescription("중복 파일 가로 비교 목록")
            .performScrollToNode(hasContentDescription("$name 비교 대상"))
        compose.onNodeWithContentDescription("$name 비교 대상").performClick()
    }

    @Test fun portraitFileDetailsKeepOpenAndProtectionActionsBesideEachOther() {
        val item = media(91, "detail-preview.jpg").copy(kind = MediaKind.PHOTO, mimeType = "image/jpeg")
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                Surface(Modifier.requiredSize(420.dp, 740.dp)) {
                    MediaDetail(item, {}, {})
                }
            }
        } }
        compose.onNodeWithText("파일 열기").performScrollTo().assertIsDisplayed()
        val open = compose.onNodeWithText("파일 열기").fetchSemanticsNode().boundsInRoot
        val protect = compose.onNodeWithText("보호").fetchSemanticsNode().boundsInRoot
        assertTrue("Portrait detail should show related actions on one row", kotlin.math.abs(open.top - protect.top) <= 2f)
        compose.onNodeWithText("앱에서 미리보기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("파일 형식").performScrollTo().assertIsDisplayed()
    }

    @Test fun narrowLargeTextFileDetailsStackActionsAndKeepMetadataReachable() {
        val item = media(92, "very-long-filename.jpg").copy(kind = MediaKind.PHOTO, mimeType = "image/jpeg")
        compose.setContent { GalliPickTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                Surface(Modifier.requiredSize(320.dp, 560.dp)) {
                    MediaDetail(item, {}, {})
                }
            }
        } }
        compose.onNodeWithText("파일 열기").performScrollTo().assertIsDisplayed()
        val open = compose.onNodeWithText("파일 열기").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("보호").performScrollTo().assertIsDisplayed()
        val protect = compose.onNodeWithText("보호").fetchSemanticsNode().boundsInRoot
        assertTrue("Large text should keep file actions vertically reachable", protect.top > open.top)
        compose.onNodeWithText("폴더").performScrollTo().assertIsDisplayed()
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
            val area = compose.onNodeWithContentDescription("선택 파일 영상 표시 영역")
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

    @Test fun externalFileOpenPausesTheFocusedVideoBeforeCallingTheResolver() {
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
                            pausedBeforeOpen = players.isNotEmpty() && players.none { it.isPlaying }
                            openedUri = item.uri
                        }, { _, _ -> }, {}, { _, _ -> })
                    }
                }
            } }
            compose.onNodeWithContentDescription("선택 파일 영상 재생").assertExists()
            focusComparisonFile("copy-1.mp4")
            scrollComparisonControlIntoView("선택 파일 외부 앱에서 파일 열기").performClick()
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
    private fun scrollComparisonControlIntoView(description: String): SemanticsNodeInteraction {
        return compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: File(instrumentation.targetContext.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
