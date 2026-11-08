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
import com.hebi.gallipick.domain.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoftFocusResultsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun candidate(group: String? = null) = SoftFocusCandidate(
        MediaItem(
            uri = "content://media/external/images/media/71", id = 71, volume = "external_primary",
            displayName = "sample.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
            relativePath = "DCIM/Camera/", sizeBytes = 40_000,
            width = 1200, height = 900, durationMs = 0,
            dateTakenMs = 1_000, dateAddedMs = 1_000, dateModifiedMs = 1_000,
        ), 3.5, group,
    )

    @Test fun candidateWithoutReferenceOpensOriginalAndNeverOffersTrashByDefault() {
        var original = 0
        var reviews = 0
        render(listOf(candidate()), onOpenMedia = { original++ }, onReview = { reviews++ })
        compose.onNodeWithText("선명도 확인 후보 1장").assertExists()
        compose.onNodeWithText("비교할 비슷한 사진 없음").assertExists()
        compose.onNodeWithText("휴지통 이동 확인", substring = true).assertDoesNotExist()
        compose.onNodeWithText("원본 사진 열기").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, original)
            assertEquals(0, reviews)
        }
    }

    @Test fun candidateWithReferenceOpensSharedComparisonOnlyByExplicitClick() {
        var compared: String? = null
        render(listOf(candidate("compare-group")), onCompare = { compared = it })
        compose.onNodeWithText("비슷한 사진과 비교").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("compare-group", compared) }
    }

    @Test fun emptyStateDoesNotShowAnAction() {
        render(emptyList())
        compose.onNodeWithText("선명도 확인 후보가 없습니다").assertExists()
        compose.onNodeWithText("원본 사진 열기").assertDoesNotExist()
    }

    private fun render(
        candidates: List<SoftFocusCandidate>,
        onCompare: (String) -> Unit = {},
        onOpenMedia: (MediaItem) -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 780.dp)) {
                        SoftFocusResultsScreen(candidates, emptySet(), {}, onCompare, onOpenMedia, onReview)
                    }
                }
            }
        }
    }
}
