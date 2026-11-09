package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class BestCutRecommenderTest {
    @Test fun recommendsClearerLargerPhotoWithReadableReasons() {
        val a = photo("high", 4000, 3000, 150.0, 0.9)
        val b = photo("low", 1280, 960, 20.0, 0.5)
        val group = SimilarPhotoGroup("test", listOf(a, b))
        val decision = BestCutRecommender.assess(group)
        assertEquals("high", decision.recommendedUri)
        assertTrue(decision.reasons.any { it.contains("선명도") })
        assertTrue(decision.reasons.any { it.contains("해상도") })
        assertEquals(decision.recommendedUri,
            BestCutRecommender.assess(group.copy(photos = group.photos.reversed())).recommendedUri)
    }

    @Test fun equalScoresStayUndecided() {
        val a = photo("a", 2000, 1500, 90.0, 0.8)
        val b = photo("b", 2000, 1500, 90.0, 0.8)
        assertNull(BestCutRecommender.assess(SimilarPhotoGroup("same", listOf(a, b))).recommendedUri)
    }

    @Test fun missingMetricsNeverProduceRecommendationOrCandidates() {
        val a = photo("a", 4000, 3000, 200.0, null)
        val b = photo("b", 2000, 1500, 10.0, 0.5)
        val group = SimilarPhotoGroup("incomplete", listOf(a, b))
        assertNull(BestCutRecommender.assess(group).recommendedUri)
        assertTrue(SimilarPhotoReviewPolicy.retained(group, emptySet()).size == 2)
    }

    private fun photo(
        id: String, width: Int, height: Int, sharpness: Double?, exposure: Double?,
    ) = SimilarPhoto(
        item = MediaItem(
            id, id.hashCode().toLong(), "primary", "$id.jpg",
            MediaKind.PHOTO, "image/jpeg", "DCIM/", 1000,
            width, height, 0, 1, 1, 1,
        ),
        phash = "0000000000000000",
        sharpnessScore = sharpness,
        exposureScore = exposure,
    )
}
