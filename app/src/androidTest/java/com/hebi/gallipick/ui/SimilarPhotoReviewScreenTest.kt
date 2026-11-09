package com.hebi.gallipick.ui

import android.content.ContentUris
import android.provider.MediaStore
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
import com.hebi.gallipick.Stage7aFixtureMedia
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

    @Test fun tabletComparisonShowsLargePreviewAndReadableMetadataWithoutNestedScroll() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(1200.dp, 850.dp)) {
                        SimilarPhotoCompareScreen(
                            group = group, groupIndex = 1, groupCount = 1,
                            selectedUris = emptySet(), protectedFolders = emptySet(),
                            onBack = {}, onToggleCandidate = {}, onOpenMedia = {}, onReview = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("비교 사진 미리보기").assertIsDisplayed().assertHeightIsAtLeast(360.dp)
        compose.onNodeWithContentDescription("유사 사진 가로 비교 목록").assertExists()
        compose.onNodeWithText("선택한 사진 정보").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("정리 후보 선택").performScrollTo().assertIsDisplayed()
        val verticalScrolls = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes()
        assertEquals("Tablet comparison must retain one vertical scroller", 1, verticalScrolls.size)
    }

    @Test fun abModePreservesManualSelectionAndAllowsSwitchingBack() {
        var selectionCalls = 0
        val selected = mutableStateOf(setOf<String>())
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 820.dp)) {
                        SimilarPhotoCompareScreen(
                            group = group, groupIndex = 1, groupCount = 1,
                            selectedUris = selected.value, protectedFolders = emptySet(),
                            onBack = {}, onToggleCandidate = {
                                selectionCalls++
                                selected.value = if (it in selected.value) selected.value - it else selected.value + it
                            }, onOpenMedia = {}, onReview = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("A/B 사진 비교 전환").performClick()
        compose.onNodeWithContentDescription("A 사진 미리보기").assertExists()
        compose.onNodeWithContentDescription("B 사진 미리보기").assertExists()
        compose.onNodeWithContentDescription("catC.jpg 미리보기 선택").performClick()
        compose.runOnIdle { assertEquals(0, selectionCalls) }
        compose.onNodeWithText("A 정리 후보 선택").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("catA"), selected.value) }
        compose.onNodeWithContentDescription("슬라이더 비교 모드").performScrollTo().performClick()
        compose.onNodeWithContentDescription("A/B 슬라이더 위치").assertExists()
        compose.onNodeWithContentDescription("catB.jpg 미리보기 선택").performScrollTo().performClick()
        compose.onNodeWithContentDescription("A/B 슬라이더 위치").assertExists()
        compose.onNodeWithContentDescription("A/B 사진 비교 전환").performClick()
        compose.onNodeWithText("선택 취소 · 남길 사진으로 지정")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf("catA"), selected.value) }
    }

    @Test fun abZoomSyncAndTabletPreviewUseRealDecodableMedia() {
        val context = compose.activity
        val now = System.currentTimeMillis()
        val samples = (1..2).map { index ->
            Stage7aFixtureMedia.insert(
                context, "EXAMPLE_AB_compare_${index}.jpg", "image/jpeg",
                Stage7aFixtureMedia.sceneJpeg(index), now, "Pictures/GalliPick_AB_Test/",
            )
        }
        try {
            val photos = samples.mapIndexed { index, sample ->
                val item = photo("real${index}", "000000000000000${index}").item.copy(
                    uri = sample.uri.toString(), id = ContentUris.parseId(sample.uri),
                    volume = MediaStore.VOLUME_EXTERNAL_PRIMARY, displayName = sample.name,
                )
                SimilarPhoto(item, "000000000000000${index}")
            }
            val actual = SimilarPhotoGroup("ab-fixture", photos)
            compose.setContent {
                GalliPickTheme {
                    CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                        Surface(Modifier.requiredSize(1100.dp, 820.dp)) {
                            SimilarPhotoCompareScreen(
                                group = actual, groupIndex = 1, groupCount = 1,
                                selectedUris = emptySet(), protectedFolders = emptySet(),
                                onBack = {}, onToggleCandidate = {}, onOpenMedia = {}, onReview = {},
                            )
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("A/B 사진 비교 전환").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("A 사진: ${samples[0].name}")
                    .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription("B 사진: ${samples[1].name}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("AB-A-preview").assertIsDisplayed().assertHeightIsAtLeast(250.dp)
            compose.onNodeWithContentDescription("A/B 확대").performScrollTo().performClick()
            compose.onNodeWithContentDescription("A 사진 미리보기")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150% 확대"))
            compose.onNodeWithContentDescription("B 사진 미리보기")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150% 확대"))
            compose.onNodeWithContentDescription("A/B 확대 동기화")
                .performScrollTo().performClick().assertIsOff()
            compose.onNodeWithContentDescription("A/B 확대")
                .performScrollTo().performClick()
            compose.onNodeWithContentDescription("A 사진 미리보기")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "225% 확대"))
            compose.onNodeWithContentDescription("B 사진 미리보기")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "150% 확대"))
            compose.onNodeWithContentDescription("B 확대 대상")
                .performScrollTo().performClick()
            compose.onNodeWithContentDescription("A/B 확대")
                .performScrollTo().performClick()
            compose.onNodeWithContentDescription("A 사진 미리보기")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "225% 확대"))
            compose.onNodeWithContentDescription("B 사진 미리보기")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "225% 확대"))
            val scrollers = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).fetchSemanticsNodes()
            assertEquals("A/B tablet comparison must retain a single page scroller", 1, scrollers.size)
        } finally {
            samples.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
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
