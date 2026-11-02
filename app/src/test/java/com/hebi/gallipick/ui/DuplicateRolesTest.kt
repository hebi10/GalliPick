package com.hebi.gallipick.ui

import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.*
import org.junit.Test

class DuplicateRolesTest {
    @Test fun protectedAndFavoriteCopiesAreCountedAsFilesToKeepWithoutSelectionControls() {
        val keeper = item(1, "DCIM/Camera/")
        val protected = item(2).copy(isProtected = true)
        val favorite = item(3).copy(isFavorite = true)
        val candidate = item(4)
        val group = DuplicateGroup("same-content", listOf(keeper, protected, favorite, candidate))
        val roles = duplicateRoles(group, keeper.uri, emptySet())
        assertEquals(listOf(keeper.uri, protected.uri, favorite.uri), roles.kept.map { it.uri })
        assertEquals(listOf(candidate.uri), roles.candidates.map { it.uri })
        assertEquals("남길 파일 · 직접 지정", roles.label(keeper, emptySet()))
        assertEquals("남길 파일 · 보호됨", roles.label(protected, emptySet()))
        assertEquals("남길 파일 · 즐겨찾기", roles.label(favorite, emptySet()))
        assertEquals("정리 후보", roles.label(candidate, emptySet()))
        assertEquals("이동 대상 · 선택됨", roles.label(candidate, setOf(candidate.uri)))
    }

    @Test fun folderProtectionAndTrashNeverBecomeCleanupCandidates() {
        val keeper = item(1, "DCIM/Camera/")
        val protected = item(2, "Download/private/")
        val trashed = item(3).copy(isTrashed = true)
        val candidate = item(4)
        val roles = duplicateRoles(DuplicateGroup("same", listOf(keeper, protected, trashed, candidate)), keeper.uri, setOf("Download/private/"))
        assertEquals(listOf(candidate.uri), roles.candidates.map { it.uri })
        assertTrue(roles.reason(protected).contains("폴더"))
    }

    @Test fun selectionCountTextNeverClaimsEveryCandidateWhenOnlyFiveHundredCanBeSelected() {
        assertEquals("정리 후보 24개 선택", duplicateSelectionLabel(24))
        assertEquals("정리 후보 500개 선택", duplicateSelectionLabel(842))
        assertEquals("정리 후보 없음", duplicateSelectionLabel(0))
    }

    @Test fun duplicateMetadataCannotOverflowTheDisplayedSelectionSize() {
        assertEquals(Long.MAX_VALUE, duplicateBytes(listOf(item(1).copy(sizeBytes = Long.MAX_VALUE), item(2).copy(sizeBytes = 1024))))
    }

    @Test fun groupSelectionLabelStatesOnlyTheNumberThatCanActuallyBeAdded() {
        assertEquals("이 묶음의 후보 1개 추가 선택", duplicateGroupSelectionLabel(2, 0, 499))
        assertEquals("이 묶음의 후보 1개 추가 선택", duplicateGroupSelectionLabel(2, 1, 1))
        assertEquals("이 묶음의 후보 2개 선택됨", duplicateGroupSelectionLabel(2, 2, 2))
        assertEquals("선택 한도 500개", duplicateGroupSelectionLabel(2, 0, 500))
    }

    private fun item(id: Long, path: String = "Download/") = MediaItem(
        "content://fixture/$id", id, "external_primary", "same-name.mp4", MediaKind.VIDEO, "video/mp4", path,
        1024, 1920, 1080, 60_000, id, id, id,
    )
}
