package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {
    @Test fun identicalPixelsHaveIdenticalHash() {
        val pixels = IntArray(1024) { i -> ((i % 32) * 7 + i / 32 * 2).coerceAtMost(255) }
        val hash = PerceptualHash.fromLuminance(pixels)
        assertEquals(16, hash.length)
        assertEquals(hash, PerceptualHash.fromLuminance(pixels.copyOf()))
        assertEquals(0, PerceptualHash.distance(hash, hash))
    }
    @Test fun uniformBrightnessShiftDoesNotChangePatterns() {
        val pixels = IntArray(1024) { i -> i % 32 * 3 + i / 32 }
        val brighter = pixels.map { it + 20 }.toIntArray()
        val distance = PerceptualHash.distance(
            PerceptualHash.fromLuminance(pixels), PerceptualHash.fromLuminance(brighter)
        )
        assertTrue("A uniform brightness shift should retain perceptual similarity: $distance", distance <= 8)
    }
    @Test fun bitDistanceIsSymmetricAndBounded() {
        val a = "0000000000000000"
        val b = "ffffffffffffffff"
        assertEquals(64, PerceptualHash.distance(a,b))
        assertEquals(PerceptualHash.distance(a,b), PerceptualHash.distance(b,a))
        assertEquals(1, PerceptualHash.distance(a, "0000000000000001"))
    }
    @Test fun invalidInputsAreRejected() {
        assertTrue(runCatching { PerceptualHash.fromLuminance(IntArray(1023)) }.isFailure)
        assertTrue(runCatching { PerceptualHash.distance("bad", "0000000000000000") }.isFailure)
    }
}
