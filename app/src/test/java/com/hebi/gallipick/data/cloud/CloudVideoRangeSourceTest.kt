package com.hebi.gallipick.data.cloud

import java.io.IOException
import java.net.URI
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVideoRangeSourceTest {
    @Test
    fun sequentialReadsReuseTheResponseAndSeekingOpensAVerifiedRange() {
        val connections = mutableListOf<RangeConnection>()
        val bytes = ByteArray(3)
        val source = CloudVideoRangeSource(capability(), 8) {
            RangeConnection().also(connections::add)
        }
        source.use {
            assertEquals(3, it.readAt(0, bytes, 0, bytes.size))
            assertEquals("abc", bytes.decodeToString())
            assertEquals(3, it.readAt(3, bytes, 0, bytes.size))
            assertEquals("def", bytes.decodeToString())
            assertEquals(1, connections.size)
            assertEquals(3, it.readAt(2, bytes, 0, bytes.size))
            assertEquals("cde", bytes.decodeToString())
            assertEquals(listOf("bytes=0-7", "bytes=2-7"), connections.map { it.getRequestProperty("Range") })
            assertTrue(connections.first().disconnected)
            assertFalse(connections.last().instanceFollowRedirects)
            assertFalse(connections.last().useCaches)
            assertEquals(-1, it.readAt(8, bytes, 0, bytes.size))
        }
        assertTrue(connections.last().disconnected)
    }

    @Test
    fun aRevokedAccountCannotReadAnotherByteOrOpenAnotherRequest() {
        var allowed = true
        var connections = 0
        val response = RangeConnection()
        CloudVideoRangeSource(capability { allowed }, 8) { connections++; response }.use {
            assertEquals(1, it.readAt(0, ByteArray(1), 0, 1))
            allowed = false
            assertThrows(IOException::class.java) { it.readAt(1, ByteArray(1), 0, 1) }
            assertEquals(1, connections)
            assertTrue(response.disconnected)
        }
    }

    @Test
    fun rejectedRangesAndNetworkErrorsDoNotExposeTheSignedUrl() {
        val failures = listOf(
            { RangeConnection(generation = "903") },
            { RangeConnection(status = 403) },
            { throw IOException("Could not open $signedUri") },
        )
        failures.forEach { open ->
            CloudVideoRangeSource(capability(), 8, open).use {
                val error = assertThrows(IOException::class.java) { it.readAt(0, ByteArray(1), 0, 1) }
                assertEquals("Could not read the cloud video.", error.message)
                assertNull(error.cause)
            }
        }
    }

    private fun capability(mayRead: () -> Boolean = { true }) = CloudVideoStreamCapability(
        signedUri, "902", obtainedAtElapsedMs = 0, mayRead = mayRead,
    )

    private val signedUri = URI("https://storage.googleapis.com/test?X-Goog-Signature=fixture")

    private class RangeConnection(private val generation: String = "902", private val status: Int = 206) :
        HttpsURLConnection(URI("https://storage.googleapis.com/fixture").toURL()) {
        var disconnected = false
        private val start get() = getRequestProperty("Range").substringAfter("bytes=").substringBefore('-').toInt()
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = when (name) {
            "Content-Range" -> "bytes $start-7/8"
            "Content-Length" -> (8 - start).toString()
            "x-goog-generation" -> generation
            else -> null
        }
        override fun getInputStream() = "abcdefgh".substring(start).byteInputStream()
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
    }
}
