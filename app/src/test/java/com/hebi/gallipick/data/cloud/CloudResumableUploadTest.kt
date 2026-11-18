package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudResumableUploadTest {
    private val uid = "synthetic-owner"
    private val hash = "a".repeat(64)
    private val transferId = "b".repeat(32)
    private val bucket = "fixture.firebasestorage.app"
    private val accountHash = MediaHasher.sha256(uid.byteInputStream())

    @Test
    fun geometryUsesTheWholeQuotaAndValidatesEveryOffsetBeforeOpeningMedia() {
        val m = 64L * 1024 * 1024
        val size = 100_000_000_000L
        val id = "$hash-$size"
        val upload = checkNotNull(cloudResumableUpload(id, hash, size, transferId, m, 1491))

        assertEquals(CloudResumablePart(0, 0L, m), upload.part(0))
        assertEquals(CloudResumablePart(1490, 99_992_207_360L, 7_792_640L), upload.part(1490))
        assertNull(upload.part(1491))
        assertNull(cloudResumableUpload(id, hash, size, transferId, m, 1490))
        assertNull(cloudResumableUpload(id, hash, size, "x".repeat(32), m, 1491))
        assertEquals(1490, cloudResumableUpload(id, hash, size, transferId, m, 1491, nextPartIndex = 1490)?.nextPartIndex)
        assertNull(cloudResumableUpload(id, hash, size, transferId, m, 1491, nextPartIndex = 1492))
    }

    @Test
    fun signedPartsCannotEscapeTheExactOwnerNamespaceAndByteRange() {
        val size = 67_108_922L
        val upload = checkNotNull(cloudResumableUpload("$hash-$size", hash, size, transferId, 67_108_864L, 2))
        val part = checkNotNull(upload.part(1))
        val prefix = "cloud02/license-test/users/$accountHash/v2/staging/${upload.id}/$transferId/parts"
        val url = "https://storage.googleapis.com/$bucket/$prefix/1?X-Goog-Signature=signature"
        val headers = mapOf("Content-Type" to "video/mp4", "X-Goog-Content-Length-Range" to "58,58",
            "X-Goog-If-Generation-Match" to "0")

        fun target(uri: String, requestHeaders: Map<String, String> = headers, privateVault: Boolean = false) = validateResumablePartUpload(
            uri, uid, upload, part, "video/mp4", requestHeaders, setOf(bucket), CloudBackupNamespace.LICENSE_TEST,
            privateVault,
        )

        assertNotNull(target(url))
        assertNull(target(url.replace("/parts/1?", "/parts/0?")))
        assertNull(target(url.replace("license-test", "production")))
        assertNull(target(url.replace(accountHash, "c".repeat(64))))
        assertNull(target(url.replace("storage.googleapis.com/", "storage.googleapis.com:443/")))
        assertNull(target(url, headers + ("X-Goog-Content-Length-Range" to "1,58")))
        val vaultUrl = url.replace("$accountHash/v2/", "$accountHash/secret/v2/")
        assertNull(target(vaultUrl))
        assertNull(target(url, privateVault = true))
        assertNotNull(target(vaultUrl, privateVault = true))
    }

    @Test
    fun mediaRangesResumeWithoutFullStagingAndRejectAShortOriginal() {
        val bytes = ByteArray(21) { it.toByte() }
        var openings = 0
        CloudResumableSource {
            openings++
            bytes.inputStream()
        }.use { source ->
            fun copy(offset: Long, count: Long): ByteArray = ByteArrayOutputStream().also { output ->
                source.copyPart(CloudResumablePart(0, offset, count), output) {}
            }.toByteArray()

            assertArrayEquals(bytes.sliceArray(9..12), copy(9L, 4L))
            assertArrayEquals(bytes.sliceArray(13..15), copy(13L, 3L))
            assertEquals(1, openings)
            assertArrayEquals(bytes.sliceArray(9..12), copy(9L, 4L))
            assertEquals(2, openings)
            val failure = assertThrows(CloudBackupException::class.java) { copy(19L, 3L) }
            assertEquals(CloudBackupResult.ORIGINAL_UNAVAILABLE, failure.result)
        }
    }

    @Test
    fun accountChangeDuringReadingStopsTheBytesBeforeNetworkWrite() {
        var signedIn = true
        val input = object : InputStream() {
            override fun read(): Int = -1

            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                buffer[offset] = 1
                signedIn = false
                return 1
            }
        }
        val output = ByteArrayOutputStream()
        CloudResumableSource { input }.use { source ->
            assertThrows(CloudBackupException::class.java) {
                source.copyPart(CloudResumablePart(0, 0, 1), output) {
                    if (!signedIn) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
                }
            }
        }
        assertEquals(0, output.size())
    }
}
