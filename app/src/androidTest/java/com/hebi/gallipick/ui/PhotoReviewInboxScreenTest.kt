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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoReviewInboxScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val group = SimilarPhotoGroup("similar:demo", listOf(
        photo("a"), photo("b"), photo("c"),
    ))

    @Test fun decisionsAreSavedButNeverAutomaticallySendPhotosToTrash() {
        val entries = mutableStateOf(emptyList<PhotoReviewEntry>())
        var confirmation = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 850.dp)) {
                        PhotoReviewInboxScreen(
                            groups = listOf(group), entries = entries.value,
                            protectedFolders = emptySet(), onBack = {}, onAnalyze = {},
                            onMark = { groupId, uri, decision ->
                                val item = group.photos.first { it.item.uri == uri }.item
                                entries.value = entries.value.filterNot { it.uri == uri && it.groupId == groupId } +
                                    PhotoReviewEntry(groupId, uri, item.fingerprint, item.volumeVersion,
                                        decision, 1L)
                            },
                            onCompare = { _, _ -> }, onConfirmCandidates = { confirmation++ },
                            onClear = { entries.value = emptyList() },
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("유사 사진 3장 검토").performClick()
        compose.onAllNodesWithContentDescription("정리 후보 상태 선택").onFirst().performClick()
        compose.runOnIdle {
            assertEquals(1, entries.value.size)
            assertEquals(PhotoReviewDecision.CANDIDATE, entries.value.single().decision)
            assertEquals(0, confirmation)
        }
        compose.onNodeWithText("정리 후보 1장 확인").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, confirmation) }
    }

    @Test fun tabletMasterDetailAndResetRequireExplicitConfirmation() {
        val original = group.photos.first().item
        val entries = mutableStateOf(listOf(PhotoReviewEntry(
            group.id, original.uri, original.fingerprint, original.volumeVersion,
            PhotoReviewDecision.DEFER, 1L)))
        var cleared = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(1200.dp, 850.dp)) {
                        PhotoReviewInboxScreen(
                            listOf(group), entries.value, emptySet(), {}, {},
                            { _, _, _ -> }, { _, _ -> }, {},
                            onClear = { entries.value = emptyList(); cleared++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("review-inbox-groups").assertExists()
        compose.onNodeWithContentDescription("유사 사진 3장 검토").performClick()
        compose.onNodeWithTag("review-inbox-detail").assertExists()
        compose.onNodeWithText("사진 A/B 비교").assertExists()
        compose.onNodeWithText("기록 초기화").performClick()
        compose.onNodeWithText("원본 사진과 실제 휴지통 기록은 변경하지 않습니다.", substring = true)
            .assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithText("기록 초기화").performClick()
        compose.onAllNodesWithText("기록 초기화").onLast().performClick()
        compose.runOnIdle { assertEquals(1, cleared); assertTrue(entries.value.isEmpty()) }
    }

    @Test fun protectedOrFavoritePhotoCannotBeMarkedAsCandidate() {
        val protectedGroup = group.copy(photos = group.photos.mapIndexed { index, photo ->
            if (index == 0) photo.copy(item = photo.item.copy(isFavorite = true)) else photo
        })
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 850.dp)) {
                        PhotoReviewInboxScreen(
                            listOf(protectedGroup), emptyList(), emptySet(), {}, {},
                            { _, _, _ -> fail("protected photo must not become a candidate") },
                            { _, _ -> }, {}, {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("유사 사진 3장 검토").performClick()
        compose.onAllNodesWithContentDescription("정리 후보 상태 선택").onFirst().assertIsNotEnabled()
        compose.onNodeWithText("보호·즐겨찾기는 정리 후보에서 제외됩니다.").assertExists()
    }

    private fun photo(id: String): SimilarPhoto = SimilarPhoto(
        MediaItem(id, id.hashCode().toLong(), "primary", id + ".jpg",
            MediaKind.PHOTO, "image/jpeg", "DCIM/", 1024L, 3000, 2000, 0, 1L, 1L, 1L),
        "0000000000000000",
    )
}
