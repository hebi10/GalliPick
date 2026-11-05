package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceHomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val exampleState = AppUiState(
        access = MediaAccess.FULL,
        totalStorageBytes = 128L * 1024 * 1024 * 1024,
        freeStorageBytes = 32L * 1024 * 1024 * 1024,
    )

    @Test fun homeFourReferenceTilesUseExistingFeaturesAndHonestFilters() {
        val filters = mutableListOf<MediaFilter>()
        var duplicateOpens = 0
        var cleanupOpens = 0
        compose.setContent {
            GalliPickTheme {
                HomeScreen(exampleState, {}, { filters.add(it) }, { duplicateOpens++ }, { cleanupOpens++ })
            }
        }

        compose.onNode(hasText("사진·영상 정리") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("중복 파일 찾기") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("용량 큰 파일") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("오래된 파일") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("정리 시작하기").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(1, duplicateOpens)
            assertEquals(1, cleanupOpens)
            assertEquals(3, filters.size)
            assertEquals(MediaFilter(), filters[0])
            assertEquals(100L * 1024 * 1024, filters[1].minSizeBytes)
            assertEquals(SortField.SIZE, filters[1].sortField)
            assertEquals(1L, filters[2].fromDateMs)
            assertEquals(SortField.CAPTURED, filters[2].sortField)
            assertEquals(SortDirection.ASCENDING, filters[2].sortDirection)
            assertTrue((filters[2].toDateMs ?: 0L) > 0L)
            assertTrue((filters[2].toDateMs ?: Long.MAX_VALUE) <= System.currentTimeMillis())
        }
    }

    @Test fun storageSummaryUsesDeviceUsageRatherThanMediaEstimate() {
        compose.setContent {
            GalliPickTheme { HomeScreen(exampleState, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("저장공간 사용량").assertIsDisplayed()
        compose.onNodeWithText("96.0 GB / 128.0 GB (75%)").assertIsDisplayed()
        compose.onNodeWithText("접근 가능한 미디어 0개").assertIsDisplayed()
    }

    @Test fun noMediaPermissionKeepsSelectionActionReachable() {
        var requests = 0
        compose.setContent {
            GalliPickTheme {
                HomeScreen(AppUiState(access = MediaAccess.NONE), { requests++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("기기 저장공간 확인 중").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("사진·동영상 선택").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
