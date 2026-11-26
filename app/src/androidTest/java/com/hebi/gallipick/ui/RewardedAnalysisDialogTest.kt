package com.hebi.gallipick.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RewardedAnalysisDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun watchingRequiresAnExplicitActionAndCancellationRemainsAvailable() {
        val loading = mutableStateOf(false)
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                    RewardedAnalysisDialog(
                        status = AdRemovalStatus.NOT_PURCHASED,
                        canWatch = true,
                        loading = loading.value,
                        retry = false,
                        onDismiss = { dismissed++ },
                        onConfirm = { confirmed++; loading.value = true },
                    )
                }
            }
        }
        compose.onNodeWithText("시험 광고를 완료하면 선명도 후보를 한 번 볼 수 있습니다.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText("광고 시청하고 이용").performClick()
        compose.onNodeWithText("광고 시청하고 이용").assertDoesNotExist()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test fun checkingEntitlementAndUnavailableAdsNeverOfferWatching() {
        val status = mutableStateOf(AdRemovalStatus.VERIFYING)
        compose.setContent {
            GalliPickTheme {
                RewardedAnalysisDialog(
                    status = status.value,
                    canWatch = false,
                    loading = false,
                    retry = false,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        compose.onNodeWithText("혜택 다시 확인").assertIsDisplayed()
        compose.onNodeWithText("광고 시청하고 이용").assertDoesNotExist()
        compose.runOnIdle { status.value = AdRemovalStatus.NOT_PURCHASED }
        compose.onNodeWithText("다시 확인").assertIsDisplayed()
        compose.onNodeWithText("광고 시청하고 이용").assertDoesNotExist()
        compose.runOnIdle { status.value = AdRemovalStatus.VERIFIED_AD_FREE }
        compose.onNodeWithText("결과 보기").assertIsDisplayed()
        compose.onNodeWithText("광고 시청하고 이용").assertDoesNotExist()
    }
}
