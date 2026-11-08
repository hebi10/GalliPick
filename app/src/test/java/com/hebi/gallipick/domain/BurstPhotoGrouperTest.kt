package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstPhotoGrouperTest {
    @Test fun groupsOnlyPhotosWithinFiveSecondsAndSimilarAppearance() {
        val candidates = listOf(
            photo("a", time = 1_000L, hash = "0000000000000000"),
            photo("b", time = 3_000L, hash = "0000000000000001"),
            photo("other", time = 4_000L, hash = "ffffffffffffffff"),
            photo("late", time = 10_001L, hash = "0000000000000002"),
        )
        val results = BurstPhotoGrouper.groups(candidates)
        assertEquals(1, results.size)
        assertEquals(listOf("a", "b"), results.single().items.map { it.uri })
        assertTrue(results.single().id.startsWith("burst:"))
        assertEquals(results, BurstPhotoGrouper.groups(candidates.reversed()))
    }

    @Test fun exactlyFiveSecondsIsIncludedButLaterPhotoIsNot() {
        val photos = listOf(
            photo("a", time = 1_000L), photo("b", time = 6_000L),
            photo("c", time = 11_001L),
        )
        assertEquals(listOf("a", "b"), BurstPhotoGrouper.groups(photos).single().items.map { it.uri })
    }

    @Test fun captureTimestampMustExistAndAddedTimeIsNotSubstituted() {
        val photos = listOf(
            photo("a", time = 0L, added = 4_000L),
            photo("b", time = 0L, added = 4_500L),
            photo("c", time = -2L, added = 5_000L),
        )
        assertTrue(BurstPhotoGrouper.groups(photos).isEmpty())
    }

    @Test fun veryDissimilarScenesDoNotGroupEvenWithinOneSecond() {
        assertTrue(BurstPhotoGrouper.groups(listOf(
            photo("a", 2_000L, "0000000000000000"),
            photo("b", 2_100L, "ffffffffffffffff"),
        )).isEmpty())
        assertTrue(BurstPhotoGrouper.groups(listOf(
            photo("a", 2_000L, width = 400, height = 400),
            photo("b", 2_100L, width = 900, height = 400),
        )).isEmpty())
    }

    @Test fun doesNotJoinDifferentScenesThroughIntermediatePhoto() {
        val candidates = listOf(
            photo("a", 1_000L, "0000000000000000"),
            photo("b", 1_200L, "000000000000003f"),
            photo("c", 1_400L, "0000000000000fff"),
        )
        val results = BurstPhotoGrouper.groups(candidates)
        assertEquals(1, results.size)
        assertEquals(listOf("a", "b"), results.single().items.map { it.uri })
    }

    @Test fun oneSessionDoesNotChainAcrossMoreThanThirtySeconds() {
        val photos = (0..8).map { i -> photo("shot-$i", time = 1_000L + i * 4_000L) }
        val results = BurstPhotoGrouper.groups(photos)
        assertEquals(1, results.size)
        assertEquals(8, results.single().items.size)
        assertFalse(results.single().items.any { it.uri == "shot-8" })
    }

    @Test fun excludesVideoTrashAndDuplicatedUri() {
        val photos = listOf(
            photo("first", 1_000L),
            photo("first", 1_000L),
            photo("video", 1_050L).copy(item = photo("video", 1_050L).item.copy(kind = MediaKind.VIDEO)),
            photo("trashed", 1_100L).copy(item = photo("trashed", 1_100L).item.copy(isTrashed = true)),
            photo("second", 1_120L),
        )
        assertEquals(listOf("first", "second"), BurstPhotoGrouper.groups(photos).single().items.map { it.uri })
    }

    @Test fun separateSessionsAreSortedNewestFirstWithoutSharingPictures() {
        val photos = listOf(
            photo("early1", 1_000L), photo("early2", 1_500L),
            photo("late1", 70_000L), photo("late2", 70_200L),
        )
        val groups = BurstPhotoGrouper.groups(photos)
        assertEquals(2, groups.size)
        assertEquals(listOf("late1", "late2"), groups[0].items.map { it.uri })
        assertEquals(listOf("early1", "early2"), groups[1].items.map { it.uri })
        assertEquals(groups.flatMap { it.items }.size, groups.flatMap { it.items }.map { it.uri }.distinct().size)
    }

    private fun photo(
        uri: String, time: Long, hash: String = "0000000000000000",
        added: Long = 10_000L, width: Int = 400, height: Int = 300,
    ): SimilarPhoto = SimilarPhoto(
        MediaItem(
            uri = uri, id = uri.hashCode().toLong(), volume = "external_primary",
            displayName = "$uri.jpg", kind = MediaKind.PHOTO,
            mimeType = "image/jpeg", relativePath = "DCIM/Camera/",
            sizeBytes = 1024L, width = width, height = height, durationMs = 0L,
            dateTakenMs = time, dateAddedMs = added, dateModifiedMs = added,
        ), hash,
    )
}
