package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoSharpnessTest {
    @Test fun smoothFlatImagesAreNotClassifiedAsBlurry() {
        assertNull(PhotoSharpness.score(IntArray(PhotoSharpness.SIDE * PhotoSharpness.SIDE) { 128 }))
        assertFalse(PhotoSharpness.isReviewCandidate(PhotoSharpness.UNKNOWN_SCORE))
        assertFalse(PhotoSharpness.isReviewCandidate(null))
        assertFalse(PhotoSharpness.isReviewCandidate(Double.NaN))
    }

    @Test fun smoothGradientIsFlaggedOnlyAsReviewCandidate() {
        val pixels = IntArray(PhotoSharpness.SIDE * PhotoSharpness.SIDE) { i ->
            (i % PhotoSharpness.SIDE) * 2
        }
        val score = PhotoSharpness.score(pixels)
        assertNotNull(score)
        assertTrue(PhotoSharpness.isReviewCandidate(score))
    }

    @Test fun highFrequencyEdgesAreNotFlagged() {
        val pixels = IntArray(PhotoSharpness.SIDE * PhotoSharpness.SIDE) { i ->
            val x = i % PhotoSharpness.SIDE
            val y = i / PhotoSharpness.SIDE
            if ((x / 8 + y / 8) % 2 == 0) 20 else 235
        }
        val score = PhotoSharpness.score(pixels)
        assertNotNull(score)
        assertTrue(requireNotNull(score) > PhotoSharpness.LOW_SCORE_THRESHOLD)
        assertFalse(PhotoSharpness.isReviewCandidate(score))
    }

    @Test fun invalidPixelCountIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoSharpness.score(IntArray(100))
        }
    }
}
