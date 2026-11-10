package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.Stage7aFixtureMedia
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual production Compose component with app-private test-owned JPEGs; no catalog mutation. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ABDesignAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var selectionCalls = 0

    private fun render(width: Int? = null, fontScale: Float = 1f) {
        val context = compose.activity
        val photos = (0..1).map { index ->
            val file = File(context.cacheDir, "design-alignment-ab-$index.jpg")
            file.writeBytes(Stage7aFixtureMedia.sceneJpeg(index + 3))
            MediaItem(
                uri = Uri.fromFile(file).toString(), id = index.toLong(), volume = "test-private",
                displayName = "EXAMPLE_${if (index == 0) "A" else "B"}.jpg",
                kind = MediaKind.PHOTO, mimeType = "image/jpeg", relativePath = "EXAMPLE/",
                sizeBytes = file.length(), width = 1200, height = 900, durationMs = 0,
                dateTakenMs = 1794222000000L, dateAddedMs = 1794222000000L,
                dateModifiedMs = 1794222000000L,
            )
        }
        val theme = if (InstrumentationRegistry.getArguments().getString("captureTheme") == "dark")
            AppThemeMode.DARK else AppThemeMode.LIGHT
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GalliPickTheme(theme) {
                    SideEffect {
                        WindowCompat.getInsetsController(context.window, context.window.decorView).apply {
                            isAppearanceLightStatusBars = theme != AppThemeMode.DARK
                            isAppearanceLightNavigationBars = theme != AppThemeMode.DARK
                        }
                    }
                    Surface(Modifier.fillMaxSize(), color = AppBackground) {
                        Column(Modifier.statusBarsPadding().navigationBarsPadding()
                            .then(if (width != null) Modifier.requiredWidth(width.dp) else Modifier.fillMaxWidth())
                            .verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("사진 비교", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            ABPhotoComparison(photos[0], photos[1], emptySet(), { true },
                                { selectionCalls++ }, {})
                        }
                    }
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("A 사진: EXAMPLE_A.jpg")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun captureActualComparisonBeforeAndAfterWithoutChangingCatalog() {
        render()
        save("side")
        compose.onNodeWithContentDescription("슬라이더 비교 모드").performScrollTo().performClick()
        compose.onNodeWithTag("AB-slider-preview").performScrollTo()
        save("slider")
        compose.onNodeWithText("A 정리 후보 선택").performScrollTo()
        save("information")
        compose.runOnIdle { assertEquals(0, selectionCalls) }
    }

    @Test fun labeledControlsAndAccessibleSliderRemainIndependentOfCleanupSelection() {
        render()
        compose.onNodeWithText("동기화 켜짐").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("A/B 확대 동기화").performClick().assertIsOff()
        compose.onNodeWithText("동기화 꺼짐").assertIsDisplayed()
        compose.onNodeWithContentDescription("A/B 확대").performScrollTo().performClick()
        compose.onNodeWithContentDescription("A 사진 미리보기").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150% 확대"))
        compose.onNodeWithContentDescription("B 사진 미리보기").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100% 확대"))
        compose.onNodeWithContentDescription("A/B 화면 맞춤").performScrollTo().performClick()
        compose.onNodeWithContentDescription("슬라이더 비교 모드").performScrollTo().performClick()
        compose.onNodeWithContentDescription("A/B 슬라이더 위치").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.7f) }
        compose.onNodeWithContentDescription("A/B 슬라이더 위치").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(.7f, 0f..1f)))
        compose.onNodeWithContentDescription("A/B 슬라이더 위치").performTouchInput { swipeLeft() }
        val progress = compose.onNodeWithContentDescription("A/B 슬라이더 위치")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        org.junit.Assert.assertTrue("Dragging the comparison changes its divider", progress < .7f)
        compose.onNodeWithContentDescription("A/B 슬라이더 위치")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.MoveEnd) }
        compose.onNodeWithContentDescription("A/B 슬라이더 위치").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(1f, 0f..1f)))
        compose.runOnIdle { assertEquals(0, selectionCalls) }
    }

    @Test fun narrowLargeTextKeepsEveryComparisonControlReachable() {
        render(width = 320, fontScale = 1.6f)
        listOf("나란히 비교 모드", "슬라이더 비교 모드", "A/B 확대 동기화", "A/B 축소",
            "A/B 확대", "A/B 화면 맞춤").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("동기화 켜짐").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A 정리 후보 선택").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("B 원본 열기").performScrollTo().assertIsDisplayed()
        save("large-text-actions")
        compose.runOnIdle { assertEquals(0, selectionCalls) }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("alignmentPhase") ?: "actual"
        val theme = args.getString("captureTheme") ?: "light"
        val directory = File(compose.activity.filesDir, "design-alignment-captures").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$phase-$theme-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
