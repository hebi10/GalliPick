package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class SimilarPhotoGrouperTest {
    @Test fun makesOneStableGroupOfSimilarImagesAndIgnoresInvalidRecords() {
        val photos = listOf(
            item("1", "0000000000000000", width = 300, height = 200),
            item("2", "0000000000000001", width = 600, height = 400),
            item("3", "ffffffffffffffff", width = 300, height = 200),
            item("video", "0000000000000000", kind = MediaKind.VIDEO),
            item("lost", "bad"),
        )
        val groups = SimilarPhotoGrouper.groups(photos.reversed())
        assertEquals(1, groups.size)
        assertEquals(listOf("1", "2"), groups.single().items.map { it.uri })
        assertEquals(groups, SimilarPhotoGrouper.groups(photos))
    }

    @Test fun aspectMismatchAndTooDistantHashesStaySeparate() {
        val photos = listOf(
            item("1", "0000000000000000", width = 200, height = 200),
            item("2", "0000000000000000", width = 400, height = 150),
            item("3", "ffffffffffffffff", width = 200, height = 200),
        )
        assertTrue(SimilarPhotoGrouper.groups(photos).isEmpty())
    }

    @Test fun doesNotTransitivelyLinkUnrelatedEndPoints() {
        val photos = listOf(
            item("a", "0000000000000000"),
            item("b", "000000000000003f"),
            item("c", "0000000000000fff"),
        )
        // a~b is six bits but a~c is twelve; no group of all three.
        val groups = SimilarPhotoGrouper.groups(photos)
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().items.map { it.uri })
    }

    @Test fun selectionIsExplicitAndAtLeastOnePhotoSurvives() {
        val group = SimilarPhotoGrouper.groups(listOf(
            item("a","0000000000000000"), item("b","0000000000000001"),
            item("c","0000000000000003"),
        )).single()
        assertTrue(SimilarPhotoReviewPolicy.maySelect(group, emptySet(), "a", emptySet()))
        assertTrue(SimilarPhotoReviewPolicy.maySelect(group, setOf("a"), "b", emptySet()))
        assertFalse(SimilarPhotoReviewPolicy.maySelect(group, setOf("a", "b"), "c", emptySet()))
        assertEquals(listOf("c"), SimilarPhotoReviewPolicy.retained(group, setOf("a", "b")).map { it.uri })
    }

    @Test fun favoritesAndProtectedFoldersCannotBecomeCandidates() {
        val group = SimilarPhotoGrouper.groups(listOf(
            item("a","0000000000000000", favorite = true),
            item("b","0000000000000001", folder = "DCIM/Protected/"),
            item("c","0000000000000003"),
        )).single()
        assertFalse(SimilarPhotoReviewPolicy.maySelect(group, emptySet(), "a", emptySet()))
        assertFalse(SimilarPhotoReviewPolicy.maySelect(group, emptySet(), "b", setOf("DCIM/Protected/")))
        assertTrue(SimilarPhotoReviewPolicy.maySelect(group, emptySet(), "c", setOf("DCIM/Protected/")))
    }

    private fun item(
        id: String, hash: String, width: Int = 300, height: Int = 200,
        kind: MediaKind = MediaKind.PHOTO, favorite: Boolean = false, folder: String = "DCIM/",
    ) = SimilarPhoto(
        MediaItem(id,id.hashCode().toLong(),"primary","$id.jpg",kind,"image/jpeg",folder,10, width,height,0,1,1,1,isFavorite=favorite),
        hash
    )
}
