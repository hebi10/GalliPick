package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimilarPhotoReviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = SimilarPhotoGroup("sample-group", listOf(
        photo("catA", "0000000000000000"), photo("catB", "0000000000000001"),
        photo("catC", "0000000000000003"),
    ))

    @Test fun groupListShowsGenuineFilesAndOnlyOpensCompareOnTap() {
        var opened: String? = null
        var trashCount = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        SimilarPhotoResultsScreen(listOf(group), emptySet(), {}, { opened = it }, { trashCount++ })
                    }
                }
            }
        }
        compose.onNodeWithText("1개 묶음", substring = true).assertExists()
        compose.onNodeWithContentDescription("3장 유사 사진 비교").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(group.id, opened); assertEquals(0, trashCount) }
        compose.onNodeWithText("선택한", substring = true).assertDoesNotExist()
    }

    @Test fun comparisonKeepsMultiplePhotosAndNeverSelectsLastSurvivor() {
        val chosen = mutableStateOf(setOf<String>())
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 850.dp)) {
                        SimilarPhotoCompareScreen(
                            group = group, groupIndex = 1, groupCount = 1,
                            selectedUris = chosen.value, protectedFolders = emptySet(),
                            onBack = {}, onToggleCandidate = { id -> chosen.value =
                                if (id in chosen.value) chosen.value - id else chosen.value + id
                            },
                            onOpenMedia = {}, onReview = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("정리 후보 선택").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("catA"), chosen.value) }
        compose.onNodeWithContentDescription("catB.jpg 미리보기 선택").performClick()
        compose.onNodeWithText("정리 후보 선택").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("catA", "catB"), chosen.value) }
        compose.onNodeWithContentDescription("catC.jpg 미리보기 선택").performClick()
        compose.onNodeWithText("정리 후보 선택").assertIsNotEnabled()
        compose.onNodeWithText("묶음에서 최소 한 장은 남겨야 합니다.").assertExists()
        compose.onNodeWithContentDescription("catA.jpg 미리보기 선택").performClick()
        compose.onNodeWithText("선택 취소 · 남길 사진으로 지정").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("catB"), chosen.value) }
    }

    @Test fun photoComparisonScrollsAsOnePageAndKeepsPhotoStripHorizontal() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(320.dp, 420.dp)) {
                        SimilarPhotoCompareScreen(
                            group = group, groupIndex = 1, groupCount = 1,
                            selectedUris = emptySet(), protectedFolders = emptySet(),
                            onBack = {}, onToggleCandidate = {}, onOpenMedia = {}, onReview = {},
                        )
                    }
                }
            }
        }
        val verticalScrolls = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes()
        assertEquals("Photo detail and metadata must share one vertical page scroll", 1, verticalScrolls.size)
        compose.onNodeWithContentDescription("유사 사진 가로 비교 목록").assertExists()
        compose.onNodeWithText("정리 후보 선택").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원본 사진 외부 앱으로 열기").performScrollTo().assertIsDisplayed()
    }

    @Test fun protectedFoldersAndFavoritesAreNeverSelectable() {
        val favoriteGroup = group.copy(photos = group.photos.mapIndexed { i, entry ->
            if (i == 0) entry.copy(item = entry.item.copy(isFavorite = true)) else entry
        })
        assertFalse(SimilarPhotoReviewPolicy.maySelect(favoriteGroup, emptySet(), "catA", emptySet()))
        assertFalse(SimilarPhotoReviewPolicy.maySelect(
            group, emptySet(), "catB", setOf("DCIM/")
        ))
    }

    private fun photo(uri: String, phash: String): SimilarPhoto = SimilarPhoto(
        MediaItem(uri = uri, id = uri.hashCode().toLong(), volume = "external_primary",
            displayName = "$uri.jpg", kind = MediaKind.PHOTO,
            mimeType = "image/jpeg", relativePath = "DCIM/",
            sizeBytes = 11L * 1024 * 1024, width = 300, height = 200, durationMs = 0,
            dateTakenMs = 1L, dateAddedMs = 1L, dateModifiedMs = 1L),
        phash,
    )
}
