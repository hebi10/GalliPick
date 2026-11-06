package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.domain.TrashOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupResultDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun verifiedResultShowsConfirmedCountsAndNavigatesToNextCleanup() {
        var home = 0
        var next = 0
        val result = CleanupOperationResult.verified(TrashOutcome(2, 1, 0), false, 3, 4_096L)
        compose.setContent { GalliPickTheme {
            CleanupResultDialog(result, {}, { home++ }, { next++ })
        } }
        compose.onNodeWithText("일부 파일만 처리 확인").assertIsDisplayed()
        compose.onNodeWithText("요청 3개 · 확인 완료 2개").assertIsDisplayed()
        compose.onNodeWithText("미이동 1개").assertIsDisplayed()
        compose.onNodeWithText("다음 정리 후보 확인").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, home)
            assertEquals(1, next)
        }
    }

    @Test fun cancelledRestoreUsesAccurateStatusAndKeepsActionsAvailableWithLargeText() {
        var dismissed = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                    Box(Modifier.requiredSize(320.dp, 280.dp)) {
                        CleanupResultDialog(
                            CleanupOperationResult.cancelled(true, 1, 2_048L),
                            { dismissed++ }, {}, {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("작업 취소").assertExists()
        compose.onNodeWithText("휴지통 확인").assertIsDisplayed()
        compose.onNodeWithContentDescription("결과 닫기").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun allFiveOutcomesExposeAccurateStatusAndDoNotClaimSavedSpace() {
        val result = mutableStateOf(CleanupOperationResult.verified(TrashOutcome(2, 0, 0), false, 2, 8_192L))
        compose.setContent { GalliPickTheme {
            CleanupResultDialog(result.value, {}, {}, {})
        } }
        compose.onNodeWithText("휴지통 이동 완료").assertIsDisplayed()
        compose.onNodeWithText("2개", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("요청 2개 · 확인 완료 2개").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("요청한 파일 크기의 합계", substring = true).performScrollTo().assertIsDisplayed()

        compose.runOnIdle { result.value = CleanupOperationResult.verified(TrashOutcome(1, 1, 0), false, 2, 8_192L) }
        compose.onNodeWithText("일부 파일만 처리 확인").assertIsDisplayed()
        compose.onNodeWithText("미이동 1개").assertIsDisplayed()
        compose.onNodeWithText("휴지통 이동 완료").assertDoesNotExist()

        compose.runOnIdle { result.value = CleanupOperationResult.cancelled(false, 2, 8_192L) }
        compose.onNodeWithText("작업 취소").assertIsDisplayed()
        compose.onNodeWithText("휴지통 이동 완료").assertDoesNotExist()

        compose.runOnIdle { result.value = CleanupOperationResult.failed(false, 2, 8_192L) }
        compose.onNodeWithText("작업 완료 실패").assertIsDisplayed()
        compose.onNodeWithText("휴지통 이동 완료").assertDoesNotExist()

        compose.runOnIdle { result.value = CleanupOperationResult.unknown(false, 2, 8_192L) }
        compose.onNodeWithText("파일 상태 확인 불가").assertIsDisplayed()
        compose.onNodeWithText("확인 불가 2개").assertIsDisplayed()
        compose.onNodeWithText("휴지통 이동 완료").assertDoesNotExist()
    }

    @Test fun unknownResultNeverPresentsSuccessMessage() {
        compose.setContent { GalliPickTheme {
            CleanupResultDialog(CleanupOperationResult.unknown(false, 1, 1_024L), {}, {}, {})
        } }
        compose.onNodeWithText("파일 상태 확인 불가").assertIsDisplayed()
        compose.onNodeWithText("휴지통 이동 완료").assertDoesNotExist()
        compose.onNodeWithText("확인 불가 1개").assertIsDisplayed()
    }
}
