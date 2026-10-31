package com.hebi.gallipick.domain

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaHasherTest {
    @Test
    fun hashesKnownEmptyAndAsciiStreams() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", MediaHasher.sha256(ByteArrayInputStream(byteArrayOf())))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MediaHasher.sha256(ByteArrayInputStream("abc".toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun hashesAcrossBufferBoundariesWithoutLoadingWholeFile() {
        val stream = RepeatingStream(2 * 1024 * 1024 + 7)

        assertEquals("9894227c2ddb433377d6e71695ea85c203eaabb67f7dadedf4ae462d41e715e6", MediaHasher.sha256(stream))
        assertTrue(stream.largestRead <= 1024 * 1024)
        assertTrue(stream.bulkReadCount >= 3)
        assertFalse(stream.closed)
    }

    @Test
    fun callerRetainsOwnershipOfStreamAfterSuccess() {
        val stream = RepeatingStream(10)

        MediaHasher.sha256(stream)

        assertFalse(stream.closed)
    }

    @Test
    fun cancellationIsCheckedBeforeReadingEachBuffer() {
        val stream = RepeatingStream(4 * 1024 * 1024)
        var cancellationChecks = 0
        val cancelled = IllegalStateException("cancelled")
        try {
            MediaHasher.sha256(stream) {
                cancellationChecks++
                if (cancellationChecks == 3) throw cancelled
            }
            throw AssertionError("Expected cancellation")
        } catch (failure: IllegalStateException) {
            assertTrue(failure === cancelled)
        }

        assertEquals(2, stream.bulkReadCount)
        assertFalse(stream.closed)
    }

    @Test
    fun readFailuresPropagateWithoutClosingCallerStream() {
        val failure = IOException("unreadable")
        val stream = object : InputStream() {
            var closed = false
            override fun read(): Int = throw failure
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            override fun close() { closed = true }
        }
        try {
            MediaHasher.sha256(stream)
            throw AssertionError("Expected read error")
        } catch (actual: IOException) {
            assertTrue(actual === failure)
        }
        assertFalse(stream.closed)
    }

    @Test
    fun shortReadsDoNotChangeHash() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
        }

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MediaHasher.sha256(stream))
    }

    private class RepeatingStream(private var remaining: Int) : InputStream() {
        var largestRead = 0
        var bulkReadCount = 0
        var closed = false

        override fun read(): Int {
            if (remaining == 0) return -1
            remaining--
            return 97
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return -1
            bulkReadCount++
            largestRead = maxOf(largestRead, length)
            val count = minOf(length, remaining)
            buffer.fill(97, offset, offset + count)
            remaining -= count
            return count
        }

        override fun close() { closed = true }
    }
}
