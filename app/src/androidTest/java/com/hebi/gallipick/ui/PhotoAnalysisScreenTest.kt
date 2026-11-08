package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoAnalysisScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val photos = listOf(MediaItem(
        uri = "content://media/external/images/media/410", id = 410,
        volume = "external_primary", displayName = "sample.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = "DCIM/Camera/", sizeBytes = 1024,
        width = 400, height = 300, durationMs = 0,
        dateTakenMs = 1, dateAddedMs = 1, dateModifiedMs = 1,
    ))

    @Test fun idleScreenStartsOnlyWhenPhotosAreAvailable() {
        var started = 0
        render(AppUiState(access = MediaAccess.FULL, items = photos), onStart = { started++ })
        compose.onNodeWithText("사진 분석 시작").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, started) }
        compose.onNodeWithText("기기 내부에서만", substring = true).assertExists()
    }

    @Test fun runningScreenShowsProgressAndPause() {
        var paused = 0
        render(AppUiState(
            access = MediaAccess.FULL, items = photos,
            photoAnalysis = PhotoAnalysisState(PhotoAnalysisPhase.RUNNING, completed = 4, total = 10, skipped = 2),
        ), onPause = { paused++ })
        compose.onNodeWithText("6 / 10개 확인", substring = true).assertExists()
        compose.onNodeWithText("일시정지").performClick()
        compose.runOnIdle { assertEquals(1, paused) }
    }

    @Test fun pausedSessionCanResume() {
        var resumed = 0
        render(AppUiState(
            access = MediaAccess.FULL, items = photos,
            photoAnalysis = PhotoAnalysisState(PhotoAnalysisPhase.PAUSED, completed = 1, total = 2),
        ), onResume = { resumed++ })
        compose.onNodeWithText("분석 재개").performClick()
        compose.runOnIdle { assertEquals(1, resumed) }
    }

    @Test fun accessDeniedMustNotExposeStaleAnalysisCounts() {
        render(AppUiState(
            access = MediaAccess.NONE, items = photos,
            photoAnalysis = PhotoAnalysisState(PhotoAnalysisPhase.COMPLETE, completed = 300),
        ))
        compose.onNodeWithText("사진에 접근할 수 없습니다.", substring = true).assertExists()
        compose.onNodeWithText("분석 성공 300개", substring = true).assertDoesNotExist()
    }

    @Test fun completeAnalysisDoesNotPretendPhotoComparisonIsAvailable() {
        render(AppUiState(
            access = MediaAccess.PARTIAL, items = photos,
            photoAnalysis = PhotoAnalysisState(PhotoAnalysisPhase.COMPLETE, completed = 1, total = 1),
        ))
        compose.onNodeWithText("특징 분석 완료").assertExists()
        compose.onNodeWithText("현재 확인된 유사 사진 묶음이 없습니다.", substring = true).assertExists()
        compose.onNodeWithText("유사 사진 결과 보기").assertDoesNotExist()
        compose.onNodeWithText("현재 허용된 사진만 분석합니다.").assertExists()
        compose.onNodeWithText("정리 후보 선택").assertDoesNotExist()
    }

    @Test fun completeAnalysisWithRealGroupsOpensResultList() {
        var opens = 0
        val group = SimilarPhotoGroup("group", listOf(
            SimilarPhoto(photos.first(), "0000000000000000"),
            SimilarPhoto(photos.first().copy(uri = "content://media/external/images/media/411", id = 411), "0000000000000001"),
        ))
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        PhotoAnalysisScreen(
                            state = AppUiState(
                                access = MediaAccess.FULL, items = group.items,
                                photoAnalysis = PhotoAnalysisState(PhotoAnalysisPhase.COMPLETE, completed = 2, total = 2),
                                similarGroups = listOf(group),
                            ),
                            onBack = {}, onStart = {}, onPause = {}, onResume = {},
                            requestAccess = {}, refresh = {}, onOpenResults = { opens++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("2장", substring = true).assertExists()
        compose.onNodeWithText("유사 사진 결과 보기").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    private fun render(
        state: AppUiState,
        onStart: () -> Unit = {},
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
    ) {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        PhotoAnalysisScreen(state, {}, onStart, onPause, onResume, {}, {})
                    }
                }
            }
        }
    }
}
