package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
class BurstPhotoResultsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val group = SimilarPhotoGroup("burst:pair", listOf(
        photo("shot-1", 1_700_000_000_000L),
        photo("shot-2", 1_700_000_002_000L),
    ))

    @Test fun burstListShowsCaptureTimeAndOpensSelectedGroup() {
        var opened: String? = null
        var reviews = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        BurstPhotoResultsScreen(
                            groups = listOf(group), selectedUris = emptySet(),
                            onBack = {}, onCompare = { opened = it }, onReview = { reviews++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("연속 촬영 사진").assertIsDisplayed()
        compose.onNodeWithText("연속 촬영 묶음").assertExists()
        compose.onNodeWithText(dateLabel(group.photos.first().item.dateTakenMs), substring = true)
            .assertExists()
        compose.onNodeWithContentDescription("2장 연속 촬영 묶음 비교")
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(group.id, opened)
            assertEquals(0, reviews)
        }
        compose.onNodeWithText("휴지통 이동 확인", substring = true).assertDoesNotExist()
    }

    @Test fun burstEmptyStateClarifiesThatMissingCaptureTimeIsExcluded() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        BurstPhotoResultsScreen(emptyList(), emptySet(), {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("연속 촬영 사진이 없습니다").assertExists()
        compose.onNodeWithText("촬영 시각이 기록되지 않았거나", substring = true).assertExists()
        compose.onNodeWithText("사진 비교").assertDoesNotExist()
    }

    @Test fun burstComparisonUsesSharedSafeSelectionAndKeepsOriginalsByDefault() {
        val selected = mutableStateOf(emptySet<String>())
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 900.dp)) {
                        SimilarPhotoCompareScreen(
                            group = group, groupIndex = 1, groupCount = 1,
                            selectedUris = selected.value, protectedFolders = emptySet(),
                            onBack = {},
                            onToggleCandidate = { uri ->
                                selected.value = if (uri in selected.value) selected.value - uri else selected.value + uri
                            },
                            onOpenMedia = {}, onReview = {},
                            title = "연속 촬영 사진 비교", groupTitle = "연속 촬영 묶음",
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("연속 촬영 사진 비교").assertExists()
        compose.onNodeWithText("연속 촬영 묶음 1 / 1").assertExists()
        compose.onNodeWithText("정리 후보 선택").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("shot-1"), selected.value) }
        compose.onNodeWithContentDescription("shot-2.jpg 미리보기 선택").performClick()
        compose.onNodeWithText("정리 후보 선택").assertIsNotEnabled()
        compose.onNodeWithText("묶음에서 최소 한 장은 남겨야 합니다.").assertExists()
    }

    private fun photo(id: String, captured: Long): SimilarPhoto = SimilarPhoto(
        MediaItem(
            uri = id, id = id.hashCode().toLong(), volume = "external_primary",
            displayName = "$id.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
            relativePath = "DCIM/Camera/", sizeBytes = 6L * 1024 * 1024,
            width = 300, height = 200, durationMs = 0L,
            dateTakenMs = captured, dateAddedMs = captured + 100_000L,
            dateModifiedMs = captured + 100_000L,
        ),
        "0000000000000000",
    )
}
