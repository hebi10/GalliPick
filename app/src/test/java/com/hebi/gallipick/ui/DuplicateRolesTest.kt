package com.hebi.gallipick.ui

import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        assertEquals("전체 후보 없음", duplicateSelectionLabel(0))
    }

    @Test fun recommendationShowsTheTakenDateThatActuallyWinsOverAnEarlierAddedDate() {
        val earlierTaken = item(1).copy(dateTakenMs = 1_700_000_000_000, dateAddedMs = 1_720_000_000_000)
        val earlierAdded = item(2).copy(dateTakenMs = 1_710_000_000_000, dateAddedMs = 1_690_000_000_000)
        val roles = duplicateRoles(DuplicateGroup("taken-date", listOf(earlierAdded, earlierTaken)), null, emptySet())
        assertEquals(earlierTaken.uri, roles.keeper.uri)
        assertTrue(roles.reason(roles.keeper).contains("촬영일 ${formatted(earlierTaken.dateTakenMs)}"))
        assertFalse(roles.reason(roles.keeper).contains("저장일"))
    }

    @Test fun missingTakenDateNamesTheAddedDateUsedByTheRecommendation() {
        val fallback = item(1).copy(dateTakenMs = 0, dateAddedMs = 1_700_000_000_000)
        val later = item(2).copy(dateTakenMs = 1_710_000_000_000, dateAddedMs = 1_690_000_000_000)
        val roles = duplicateRoles(DuplicateGroup("fallback-date", listOf(later, fallback)), null, emptySet())
        assertEquals(fallback.uri, roles.keeper.uri)
        assertTrue(roles.reason(roles.keeper).contains("저장일 ${formatted(fallback.dateAddedMs)}"))
        assertFalse(roles.reason(roles.keeper).contains("촬영일"))
    }

    @Test fun cameraPriorityDoesNotPretendAnEarlierDownloadDateWon() {
        val camera = item(1, "DCIM/Camera/").copy(dateTakenMs = 1_710_000_000_000)
        val download = item(2).copy(dateTakenMs = 1_700_000_000_000)
        val roles = duplicateRoles(DuplicateGroup("camera-first", listOf(download, camera)), null, emptySet())
        assertEquals(camera.uri, roles.keeper.uri)
        assertEquals("카메라 폴더의 파일을 우선 추천했습니다.", roles.reason(camera))
    }

    @Test fun equalCameraPriorityStillShowsTheDateEvidenceAndMissingDatesDoNotInventOne() {
        val camera = item(1, "DCIM/Camera/").copy(dateTakenMs = 1_700_000_000_000)
        val laterCamera = item(2, "DCIM/Camera/").copy(dateTakenMs = 1_710_000_000_000)
        val dated = duplicateRoles(DuplicateGroup("camera-date", listOf(laterCamera, camera)), null, emptySet())
        assertTrue(dated.reason(camera).contains("촬영일 ${formatted(camera.dateTakenMs)}"))
        val missing = listOf(item(1), item(2)).map { it.copy(dateTakenMs = 0, dateAddedMs = 0) }
        val undated = duplicateRoles(DuplicateGroup("no-date", missing), null, emptySet())
        assertFalse(undated.reason(undated.keeper).contains("촬영일"))
        assertFalse(undated.reason(undated.keeper).contains("저장일"))
    }

    @Test fun equalDateRanksDoNotClaimThatTheDateResolvedTheRecommendation() {
        val tied = listOf(item(1), item(2)).map { it.copy(dateTakenMs = 1_700_000_000_000) }
        val roles = duplicateRoles(DuplicateGroup("same-date", tied.reversed()), null, emptySet())
        assertEquals(tied.first().uri, roles.keeper.uri)
        assertEquals("같은 내용의 사본 중 한 개를 남기도록 추천했습니다.", roles.reason(roles.keeper))
    }

    @Test fun aSharedEarliestDateExplainsTheTieWithoutCallingOneFileEarlier() {
        val first = item(1).copy(dateTakenMs = 1_700_000_000_000)
        val tied = item(2).copy(dateTakenMs = first.dateTakenMs)
        val later = item(3).copy(dateTakenMs = first.dateTakenMs + 86_400_000)
        val roles = duplicateRoles(DuplicateGroup("shared-earliest", listOf(later, tied, first)), null, emptySet())
        assertEquals(first.uri, roles.keeper.uri)
        assertTrue(roles.reason(roles.keeper).contains("가장 이른 날짜가 같은 사본 중 한 개"))
        assertTrue(roles.reason(roles.keeper).contains("촬영일 ${formatted(first.dateTakenMs)}"))
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

    private fun formatted(ms: Long) = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(ms))
}
