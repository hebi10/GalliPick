package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoExposureTest {
    @Test fun evenlyDistributedTonesReceiveHigherHintThanClippedTones() {
        val balanced = IntArray(PhotoSharpness.SIDE * PhotoSharpness.SIDE) { (it % 128) * 2 }
        val clipped = IntArray(PhotoSharpness.SIDE * PhotoSharpness.SIDE) {
            if (it % 2 == 0) 0 else 255
        }
        assertTrue(PhotoExposure.score(balanced)!! > PhotoExposure.score(clipped)!!)
    }

    @Test fun flatSceneDoesNotInventExposureQuality() {
        assertNull(PhotoExposure.score(IntArray(PhotoSharpness.SIDE * PhotoSharpness.SIDE) { 128 }))
    }
}
