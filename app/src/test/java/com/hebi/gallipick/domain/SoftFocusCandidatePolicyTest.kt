package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class SoftFocusCandidatePolicyTest {
    private fun photo(uri: String, favorite: Boolean = false, folder: String = "DCIM/Camera/",
        width: Int = 1200, height: Int = 900, protected: Boolean = false) = SimilarPhoto(
        MediaItem(
            uri = uri, id = uri.hashCode().toLong(), volume = "external_primary",
            displayName = "$" + "uri.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
            relativePath = folder, sizeBytes = 10_000,
            width = width, height = height, durationMs = 0,
            dateTakenMs = 1_000, dateAddedMs = 1_000, dateModifiedMs = 1_000,
            isFavorite = favorite, isProtected = protected,
        ), "0123456789abcdef"
    )

    @Test fun aSingletonCanBeReviewedButNeverGivenAFakeComparisonGroup() {
        val photo = photo("single")
        val found = SoftFocusCandidatePolicy.candidates(
            listOf(photo), mapOf("single" to 2.0), emptyList(), emptySet()
        )
        assertEquals(1, found.size)
        assertNull(found.single().comparisonGroupId)
    }

    @Test fun existingVerifiedSimilarGroupMayBeUsedForManualComparison() {
        val a = photo("a")
        val b = photo("b")
        val group = SimilarPhotoGroup("same-scene", listOf(a, b))
        val found = SoftFocusCandidatePolicy.candidates(
            listOf(a, b), mapOf("a" to 5.0, "b" to 120.0), listOf(group), emptySet()
        )
        assertEquals(listOf("a"), found.map { it.item.uri })
        assertEquals(group.id, found.single().comparisonGroupId)
    }

    @Test fun protectionFavoritesAndInvalidScoresAreExcluded() {
        val entries = listOf(
            photo("favorite", favorite = true),
            photo("protected", protected = true),
            photo("folder", folder = "DCIM/Safe/Sub/"),
            photo("small", width = 320),
            photo("unknown"),
            photo("failed"),
            photo("sharp"),
            photo("valid"),
        )
        val scores: Map<String, Double?> = mapOf(
            "favorite" to 1.0, "protected" to 1.0, "folder" to 1.0,
            "small" to 1.0, "unknown" to null, "failed" to -1.0,
            "sharp" to 200.0, "valid" to 5.0,
        )
        val found = SoftFocusCandidatePolicy.candidates(entries, scores, emptyList(), setOf("DCIM/Safe/"))
        assertEquals(listOf("valid"), found.map { it.item.uri })
    }

    @Test fun candidatesAreOrderedByRiskThenRecentCaptureTime() {
        val a = photo("a").copy(item = photo("a").item.copy(dateTakenMs = 2_000))
        val b = photo("b").copy(item = photo("b").item.copy(dateTakenMs = 3_000))
        val c = photo("c")
        val candidates = SoftFocusCandidatePolicy.candidates(
            listOf(a, b, c), mapOf("a" to 4.0, "b" to 4.0, "c" to 1.0),
            emptyList(), emptySet()
        )
        assertEquals(listOf("c", "b", "a"), candidates.map { it.item.uri })
    }
}
