package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatePolicyTest {
    @Test fun chosenAlbumCopyCanBeKeptWhileProtectedCopiesRemainSafe() {
        val camera = media("camera").copy(relativePath = "DCIM/Camera/")
        val album = media("album").copy(relativePath = "Pictures/Album/")
        val protected = media("protected").copy(isProtected = true)
        val group = group(camera, album, protected)
        assertEquals(album, DuplicatePolicy.recommendedKeep(group, album.uri))
        assertEquals(listOf(camera), DuplicatePolicy.deletionCandidates(group, album.uri))
        assertEquals(camera.sizeBytes, DuplicatePolicy.reclaimableBytes(listOf(group), mapOf(group.hash to album.uri)))
    }

    @Test fun removedPreferredCopyFallsBackToSafeRecommendation() {
        val a = media("a")
        val b = media("b")
        assertEquals(a, DuplicatePolicy.recommendedKeep(group(a, b), "missing"))
        assertEquals(listOf(b), DuplicatePolicy.deletionCandidates(group(a, b), "missing"))
    }

    @Test
    fun cameraOriginalWinsOverCopiesRegardlessOfFileSizeOrPixels() {
        val original = media("camera").copy(relativePath = "DCIM/Camera/", sizeBytes = 1024, width = 100, height = 100)
        val copy = media("copy").copy(relativePath = "Download/", sizeBytes = 2048, width = 200, height = 200)

        assertEquals(original, DuplicatePolicy.recommendedKeep(group(copy, original)))
        assertEquals(listOf(copy), DuplicatePolicy.deletionCandidates(group(copy, original)))
    }

    @Test
    fun cameraPriorityMatchesAPathSegmentNotAnUnrelatedFolderSubstring() {
        val camera = media("camera").copy(relativePath = "DCIM/Camera/")
        val other = media("other").copy(relativePath = "Pictures/CameraRoll/", dateTakenMs = 1)

        assertEquals(camera, DuplicatePolicy.recommendedKeep(group(other, camera)))
    }

    @Test
    fun olderCaptureThenUriChooseKeeperDeterministically() {
        val olderB = media("b").copy(dateTakenMs = 100)
        val olderA = olderB.copy(uri = "a")
        val newer = media("c").copy(dateTakenMs = 200)

        assertEquals(olderA, DuplicatePolicy.recommendedKeep(group(newer, olderB, olderA)))
        assertEquals(olderA, DuplicatePolicy.recommendedKeep(group(olderA, olderB, newer)))
    }

    @Test
    fun unknownDateDoesNotWinOverAKnownOriginalDate() {
        val unknown = media("a").copy(dateTakenMs = 0, dateAddedMs = 0)
        val known = media("b").copy(dateTakenMs = 100)

        assertEquals(known, DuplicatePolicy.recommendedKeep(group(unknown, known)))
    }

    @Test
    fun protectedAndFavoriteItemsAreNeverDeletionCandidates() {
        val protected = media("protected").copy(isProtected = true)
        val favorite = media("favorite").copy(isFavorite = true)
        val plain = media("plain")
        val group = group(plain, protected, favorite)

        assertEquals(listOf(plain), DuplicatePolicy.deletionCandidates(group))
        assertFalse(DuplicatePolicy.deletionCandidates(group).any { it.isFavorite || it.isProtected })
        assertTrue(DuplicatePolicy.recommendedKeep(group).isProtected)
    }

    @Test
    fun allProtectedOrFavoriteGroupsHaveNoCandidates() {
        val group = group(media("a").copy(isProtected = true), media("b").copy(isFavorite = true))

        assertTrue(DuplicatePolicy.deletionCandidates(group).isEmpty())
        assertEquals(0L, DuplicatePolicy.reclaimableBytes(listOf(group)))
    }

    @Test
    fun singletonsAndEmptyGroupsCannotProduceDeletionCandidates() {
        assertTrue(DuplicatePolicy.deletionCandidates(group(media("one"))).isEmpty())
        assertTrue(DuplicatePolicy.deletionCandidates(group()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestingKeeperForEmptyGroupFailsClearly() {
        DuplicatePolicy.recommendedKeep(group())
    }

    @Test
    fun duplicateUrisCannotMakeTheKeeperADeletionCandidate() {
        val original = media("same").copy(relativePath = "DCIM/Camera/")
        val copy = media("copy")

        assertEquals(listOf(copy), DuplicatePolicy.deletionCandidates(group(original, original.copy(), copy)))
    }

    @Test
    fun aProtectedSnapshotPreventsDeletionOfEverySnapshotOfThatUri() {
        val keeper = media("keeper").copy(isProtected = true, relativePath = "DCIM/Camera/")
        val staleSnapshot = media("same")
        val protectedSnapshot = staleSnapshot.copy(isProtected = true)
        val plain = media("plain")

        assertEquals(listOf(plain), DuplicatePolicy.deletionCandidates(group(keeper, staleSnapshot, protectedSnapshot, plain)))
    }

    @Test
    fun reclaimableBytesUseLongArithmeticAndDeduplicateUrisAcrossGroups() {
        val keeper = media("keeper").copy(isProtected = true)
        val a = media("a").copy(sizeBytes = 5_000_000_000)
        val b = media("b").copy(sizeBytes = 6_000_000_000)

        assertEquals(11_000_000_000L, DuplicatePolicy.reclaimableBytes(listOf(group(keeper, a, b), group(keeper, a))))
    }

    @Test
    fun reclaimableBytesIgnoreNegativeUnknownSizesAndCannotOverflow() {
        val keeper = media("keeper").copy(isProtected = true)
        val unknown = media("unknown").copy(sizeBytes = -1)
        val huge = media("huge").copy(sizeBytes = Long.MAX_VALUE)
        val extra = media("extra").copy(sizeBytes = 100)

        assertEquals(0L, DuplicatePolicy.reclaimableBytes(listOf(group(keeper, unknown))))
        assertEquals(Long.MAX_VALUE, DuplicatePolicy.reclaimableBytes(listOf(group(keeper, huge, extra))))
    }

    private fun group(vararg items: MediaItem) = DuplicateGroup("same-sha256", items.toList())
}
