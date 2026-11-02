package com.hebi.gallipick.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicateClarityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keptFilesHaveNoCheckboxAndOnlyCheckedCandidatesReachTheTrashAction() {
        val keeper = media(1, "camera.mp4").copy(relativePath = "DCIM/Camera/")
        val protected = media(2, "protected.mp4").copy(isProtected = true)
        val favorite = media(3, "favorite.mp4").copy(isFavorite = true)
        val candidate = media(4, "candidate.mp4")
        val group = DuplicateGroup("same-content", listOf(keeper, protected, favorite, candidate))
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = group.items, duplicates = listOf(group), duplicateKeepers = mapOf(group.hash to keeper.uri)))
        var requested = emptyList<MediaItem>()
        compose.setContent { GalliPickTheme {
            DuplicatesScreenContent(state.value, { requested = it }, {}, {}, actions(
                toggle = { uri -> state.value = state.value.copy(selected = if (uri in state.value.selected) state.value.selected - uri else state.value.selected + uri) },
                clear = { state.value = state.value.copy(selected = emptySet()) },
            ))
        } }
        val list = compose.onNodeWithContentDescription("중복 파일 목록")
        list.performScrollToNode(hasText("남김 3개 · 정리 후보 1개"))
        compose.onNodeWithText("남김 3개 · 정리 후보 1개").assertIsDisplayed()
        for (kept in listOf(keeper, protected, favorite)) {
            list.performScrollToNode(hasText(kept.displayName))
            compose.onNodeWithContentDescription("${kept.displayName}, ${kept.relativePath} 정리 후보 선택").assertDoesNotExist()
        }
        list.performScrollToNode(hasContentDescription("${candidate.displayName}, ${candidate.relativePath} 정리 후보 선택"))
        compose.onNodeWithContentDescription("${candidate.displayName}, ${candidate.relativePath} 정리 후보 선택").performClick().assertIsOn()
        list.performScrollToNode(hasText("이동 대상 · 선택됨"))
        compose.onNodeWithText("이동 대상 · 선택됨").assertIsDisplayed()
        compose.runOnIdle { assertTrue(requested.isEmpty()) }
        compose.onNodeWithText("선택한 1개 휴지통으로 이동").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(candidate.uri), requested.map { it.uri }) }
        compose.onNodeWithText("선택 해제").performClick()
        compose.onNodeWithText("선택한 1개 휴지통으로 이동").assertDoesNotExist()
    }

    @Test fun globalCandidateSelectionNamesItsScopeAndDoesNotMoveFiles() {
        val all = (0..842).map { media(it.toLong(), "copy-$it.mp4") }
        val group = DuplicateGroup("many-copies", all)
        var selected = false
        var moved = false
        compose.setContent { GalliPickTheme {
            DuplicatesScreenContent(AppUiState(access = MediaAccess.FULL, items = all, duplicates = listOf(group), duplicateKeepers = mapOf(group.hash to all.first().uri)),
                { moved = true }, {}, {}, actions(recommended = { selected = true }))
        } }
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasText("정리 후보 500개 선택"))
        compose.onNodeWithText("정리 후보 500개 선택").performClick()
        compose.onNodeWithText("전체 842개 중 한 번에 최대 500개를 선택합니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("추천 선택").assertDoesNotExist()
        compose.runOnIdle { assertTrue(selected); assertFalse(moved) }
    }

    @Test fun deepGroupPositionSurvivesCatalogRevalidation() {
        val groups = (0 until 40).map { index -> DuplicateGroup("group-$index", listOf(media(index * 2L, "keep-$index.mp4"), media(index * 2L + 1, "copy-$index.mp4"))) }
        val populated = AppUiState(access = MediaAccess.FULL, items = groups.flatMap { it.items }, duplicates = groups)
        val state = mutableStateOf(populated)
        compose.setContent { GalliPickTheme { DuplicatesScreenContent(state.value, {}, {}, {}, actions()) } }
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasText("copy-35.mp4"))
        compose.onNodeWithText("copy-35.mp4").assertIsDisplayed()
        compose.runOnIdle { state.value = populated.copy(loading = true, items = emptyList(), duplicates = emptyList()) }
        compose.onNodeWithText("미디어를 불러오고 있습니다").assertIsDisplayed()
        compose.runOnIdle { state.value = populated }
        compose.onNodeWithText("copy-35.mp4").assertIsDisplayed()
    }

    @Test fun protectionRevalidationKeepsComparisonOpenButDisablesChanges() {
        val keeper = media(1, "first.mp4")
        val copy = media(2, "second.mp4")
        val group = DuplicateGroup("same", listOf(keeper, copy))
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = group.items, duplicates = listOf(group), duplicateKeepers = mapOf(group.hash to keeper.uri)))
        compose.setContent { GalliPickTheme { DuplicatesScreenContent(state.value, {}, {}, {}, actions(protect = { item, protected ->
            state.value = state.value.copy(duplicates = emptyList(), items = state.value.items.map { if (it.uri == item.uri) it.copy(isProtected = protected) else it })
        })) } }
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasContentDescription("중복 묶음 1 비교"))
        compose.onNodeWithContentDescription("중복 묶음 1 비교").performClick()
        if (compose.onAllNodesWithText("B").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("B").performClick()
        compose.onNodeWithContentDescription("B 보호").performScrollTo().performClick()
        compose.onNodeWithContentDescription("비교 닫기").assertIsDisplayed()
        compose.onNodeWithContentDescription("중복 결과 확인 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("B 보호 해제").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("B 정리 후보 선택").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(duplicates = listOf(group.copy(items = state.value.items))) }
        compose.onNodeWithContentDescription("중복 결과 확인 중").assertDoesNotExist()
        compose.onNodeWithContentDescription("B 보호 해제").assertIsEnabled()
    }

    @Test fun changedFileIdentityClosesAnUnverifiedComparison() {
        val group = DuplicateGroup("old-version", listOf(media(1, "first.mp4"), media(2, "second.mp4")))
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = group.items, duplicates = listOf(group)))
        compose.setContent { GalliPickTheme { DuplicatesScreenContent(state.value, {}, {}, {}, actions()) } }
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasContentDescription("중복 묶음 1 비교"))
        compose.onNodeWithContentDescription("중복 묶음 1 비교").performClick()
        compose.onNodeWithContentDescription("비교 닫기").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(duplicates = emptyList(), items = group.items.mapIndexed { index, item -> if (index == 1) item.copy(generationModified = 1) else item }) }
        compose.onNodeWithContentDescription("비교 닫기").assertDoesNotExist()
    }

    @Test fun closingComparisonDuringProtectionRevalidationKeepsTheDeepGroupPosition() {
        val groups = (0 until 40).map { index -> DuplicateGroup("position-$index", listOf(media(index * 2L, "keep-$index.mp4"), media(index * 2L + 1, "copy-$index.mp4"))) }
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = groups.flatMap { it.items }, duplicates = groups))
        compose.setContent { GalliPickTheme { DuplicatesScreenContent(state.value, {}, {}, {}, actions(protect = { item, protected ->
            state.value = state.value.copy(duplicates = emptyList(), items = state.value.items.map { if (it.uri == item.uri) it.copy(isProtected = protected) else it })
        })) } }
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasContentDescription("중복 묶음 36 비교"))
        compose.onNodeWithContentDescription("중복 묶음 36 비교").performClick()
        if (compose.onAllNodesWithText("B").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("B").performClick()
        compose.onNodeWithContentDescription("B 보호").performScrollTo().performClick()
        compose.onNodeWithContentDescription("중복 결과 확인 중").assertIsDisplayed()
        compose.onNodeWithContentDescription("비교 닫기").performClick()
        compose.onNodeWithText("중복 결과를 확인하고 있습니다").assertIsDisplayed()
        compose.runOnIdle {
            val live = state.value.items.associateBy { it.uri }
            state.value = state.value.copy(duplicates = groups.map { it.copy(items = it.items.map { old -> live.getValue(old.uri) }) })
        }
        compose.onNodeWithContentDescription("중복 묶음 36 비교").assertIsDisplayed()
    }

    private fun actions(recommended: () -> Unit = {}, toggle: (String) -> Unit = {}, clear: () -> Unit = {}, protect: (MediaItem, Boolean) -> Unit = { _, _ -> }) =
        DuplicatesScreenActions({}, {}, {}, recommended, toggle, clear, { _, _ -> }, protect, {})

    private fun media(id: Long, name: String) = MediaItem("file:///missing-duplicate-$id.mp4", id, "test", name, MediaKind.VIDEO, "video/mp4", "Download/",
        1024, 1920, 1080, 60_000, id, id, id)
}
