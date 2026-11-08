package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoPerceptualHashTest {
    @Test fun identicalThumbnailsHaveIdenticalHashes() {
        val image = IntArray(1024) { i ->
            val channel = (i * 13 + (i / 32) * 41) and 255
            0xff000000.toInt() or (channel shl 16) or (channel shl 8) or channel
        }
        val a = PhotoPerceptualHash.fromArgb(image)
        val b = PhotoPerceptualHash.fromArgb(image.copyOf())
        assertEquals(16, a.length)
        assertEquals(a, b)
        assertEquals(0, PhotoPerceptualHash.distance(a, b))
    }

    @Test fun solidBrightAndDarkHaveSameShapeHash() {
        val black = IntArray(1024) { 0xff000000.toInt() }
        val white = IntArray(1024) { 0xffffffff.toInt() }
        assertEquals(0, PhotoPerceptualHash.distance(
            PhotoPerceptualHash.fromArgb(black),
            PhotoPerceptualHash.fromArgb(white),
        ))
    }

    @Test fun differentShapesAreNotExactMatches() {
        val horizontal = IntArray(1024) { i ->
            if ((i / 32) < 16) 0xffffffff.toInt() else 0xff000000.toInt()
        }
        val vertical = IntArray(1024) { i ->
            if ((i % 32) < 16) 0xffffffff.toInt() else 0xff000000.toInt()
        }
        assertTrue(PhotoPerceptualHash.distance(
            PhotoPerceptualHash.fromArgb(horizontal),
            PhotoPerceptualHash.fromArgb(vertical),
        ) > 0)
    }

    @Test fun rejectsMalformedInputAndHash() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoPerceptualHash.fromArgb(IntArray(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoPerceptualHash.distance("abc", "0000000000000000")
        }
    }
}
