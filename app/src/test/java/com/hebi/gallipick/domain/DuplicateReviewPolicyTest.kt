package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class DuplicateReviewPolicyTest {
    @Test fun keepsEveryProtectedFavoriteAndChosenCopyOutOfCandidates() {
        val items = listOf(item(1), item(2).copy(isFavorite = true), item(3).copy(isProtected = true), item(4))
        val group = DuplicateGroup("same", items)
        assertEquals(listOf(items[3]), DuplicateReviewPolicy.candidates(listOf(group), items, mapOf("same" to items[0].uri), emptySet()))
    }

    @Test fun reusedUriWithAnotherVolumeVersionCannotKeepAGroupAlive() {
        val copies = listOf(item(1), item(2))
        val changed = copies[1].copy(volumeVersion = "replacement")
        assertTrue(DuplicateReviewPolicy.liveGroups(listOf(DuplicateGroup("same", copies)), listOf(copies[0], changed)).isEmpty())
    }

    @Test fun usesLiveProtectionAndRejectsTrashedOrChangedCopies() {
        val copies = (1L..5L).map(::item)
        val current = listOf(copies[0], copies[1].copy(isProtected = true), copies[2].copy(isTrashed = true), copies[3].copy(generationModified = 4), copies[4])
        val group = DuplicateGroup("same", copies)
        assertEquals(listOf(copies[4]), DuplicateReviewPolicy.candidates(listOf(group), current, mapOf("same" to copies[0].uri), emptySet()))
    }

    @Test fun folderProtectionHonorsBoundariesAndRootOnly() {
        val copies = listOf(item(1).copy(relativePath = "Camera/"), item(2).copy(relativePath = "Download/private/"), item(3).copy(relativePath = "Download/private-copy/"), item(4).copy(relativePath = ""))
        assertEquals(listOf(copies[2]), DuplicateReviewPolicy.candidates(listOf(DuplicateGroup("same", copies)), copies, mapOf("same" to copies[0].uri), setOf("Download/private/", "")))
    }

    @Test fun keeperChangeClearsOnlyTheAffectedGroupWithoutSelectingItsFormerKeeper() {
        val first = listOf(item(1), item(2), item(3))
        val other = item(4)
        assertEquals(listOf(other), DuplicateReviewPolicy.afterKeeperChange(listOf(first[1], first[2], other), DuplicateGroup("first", first)))
    }

    @Test fun groupSelectionDropsHiddenSelectionsPreservesOtherGroupsAndCapsAt500() {
        val eligible = (1L..502L).map(::item)
        val hidden = item(999)
        val selected = eligible.take(499) + hidden
        val group = DuplicateGroup("target", eligible.takeLast(3))
        val result = DuplicateReviewPolicy.selectGroup(selected, eligible, group)
        assertEquals(500, result.size)
        assertEquals(eligible.take(499), result.take(499))
        assertEquals(eligible[499], result.last())
        assertFalse(result.any { it.uri == hidden.uri })
    }

    private fun item(id: Long) = MediaItem("content://fixture/$id", id, "external", "$id.mp4", MediaKind.VIDEO,
        "video/mp4", "Movies/", 1024, 1920, 1080, 30_000, 10, 10, 10, volumeVersion = "epoch")
}
