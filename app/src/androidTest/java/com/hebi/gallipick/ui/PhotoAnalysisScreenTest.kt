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
import com.hebi.gallipick.domain.ScanPhase
import com.hebi.gallipick.domain.ScanState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoAnalysisScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun startButtonTriggersPhotoAnalysisOnly() {
        var started = 0
        screen(AppUiState(access = MediaAccess.FULL), start = { started++ })
        compose.onNodeWithText("사진 분석 시작").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, started) }
        compose.onNodeWithText("분석만으로 파일이 삭제되거나 휴지통으로 이동하지 않습니다.").assertExists()
    }

    @Test fun runningAndPausedStatesExposeHonestProgressAndControls() {
        var paused = 0
        screen(
            AppUiState(access = MediaAccess.FULL, photoScan = ScanState(
                phase = ScanPhase.RUNNING, completed = 3, skipped = 1, total = 10, message = "사진 분석 중",
            )),
            pause = { paused++ },
        )
        compose.onNodeWithText("처리 4 / 10", substring = true).assertIsDisplayed()
        compose.onNodeWithText("분석 일시정지").performClick()
        compose.runOnIdle { assertEquals(1, paused) }
    }

    @Test fun pausedStateCanResumeWithoutDeletingPhotos() {
        var resumed = 0
        screen(
            AppUiState(access = MediaAccess.FULL, photoScan = ScanState(
                phase = ScanPhase.PAUSED, completed = 2, total = 8,
            )),
            resume = { resumed++ },
        )
        compose.onNodeWithText("분석 재개").performClick()
        compose.runOnIdle { assertEquals(1, resumed) }
    }

    @Test fun completeStateDoesNotAdvertiseUnfinishedReviewOrTrashFlow() {
        screen(AppUiState(access = MediaAccess.FULL, photoScan = ScanState(
            phase = ScanPhase.COMPLETE, completed = 12, total = 12,
        )))
        compose.onNodeWithText("다시 분석하기").assertExists()
        compose.onNodeWithText("분석된 사진의 묶음 비교·보관 선택 기능은 다음 단계에서 제공됩니다.").assertExists()
        compose.onNodeWithText("정리 후보 선택").assertDoesNotExist()
    }

    @Test fun noPermissionHidesScanControlsAndAllowsBackNavigation() {
        var backs = 0
        screen(
            AppUiState(access = MediaAccess.NONE, photoScan = ScanState(
                phase = ScanPhase.COMPLETE, completed = 12, total = 12,
            )),
            back = { backs++ },
        )
        compose.onNodeWithText("사진 접근이 필요합니다").assertExists()
        compose.onNodeWithText("다시 분석하기").assertDoesNotExist()
        compose.onNodeWithContentDescription("사진 분석 닫기").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    private fun screen(
        state: AppUiState,
        back: () -> Unit = {},
        start: () -> Unit = {},
        pause: () -> Unit = {},
        resume: () -> Unit = {},
    ) {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        PhotoAnalysisScreen(state, back, start, pause, resume, onRequestAccess = {})
                    }
                }
            }
        }
    }
}
