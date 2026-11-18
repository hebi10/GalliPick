package com.hebi.gallipick.data.cloud

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudLibraryProtocolTest {
    @Test
    fun restoreUrlMustTargetTheAuthenticatedAccountAndVerifiedGeneration() {
        assertNotNull(validateDownload(url))
        assertNull(validateDownload(url.replace("storage.googleapis.com", "storage.googleapis.com.invalid")))
        assertNull(validateDownload(url.replace("test-bucket", "unrelated-bucket")))
        assertNull(validateDownload(url.replace("3001f754", "4001f754")))
        assertNull(validateDownload(url.replace("generation=902", "generation=903")))
        assertNull(validateDownload(url.replace("&generation=902", "")))
        assertNull(validateDownload("$url&generation=903"))
        assertNull(validateDownload(url.replace("X-Goog-Expires=120", "X-Goog-Expires=301")))
    }

    @Test
    fun largeVerifiedOriginalsAreCopiedWithoutBufferingTheWholeFile() {
        val size = (CLOUD_BACKUP_MAX_BYTES + 8_192L).toInt()
        val chunk = ByteArray(8_192)
        val hash = MessageDigest.getInstance("SHA-256")
        repeat(size / chunk.size) { hash.update(chunk) }
        val expected = hash.digest().joinToString("") { "%02x".format(it) }
        val original = object : InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (remaining <= 0) return -1
                val length = minOf(count, remaining)
                bytes.fill(0, offset, offset + length)
                remaining -= length
                return length
            }
        }
        copyAndVerifyCloudOriginal(original, OutputStream.nullOutputStream(), size.toLong(), expected) {}
        assertThrows(CloudBackupException::class.java) {
            copyAndVerifyCloudOriginal(
                original, OutputStream.nullOutputStream(), CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES + 1, expected,
            ) {}
        }
    }

    @Test
    fun acceptsOnlyTheExactVerifiedBytesBeforePublishing() {
        val source = "abc".toByteArray()
        val output = ByteArrayOutputStream()
        copyAndVerifyCloudOriginal(source.inputStream(), output, 3, sha256) {}
        assertArrayEquals(source, output.toByteArray())

        for (differentBytes in listOf("abd", "ab", "abcd")) {
            val error = assertThrows(CloudBackupException::class.java) {
                copyAndVerifyCloudOriginal(differentBytes.byteInputStream(), ByteArrayOutputStream(), 3, sha256) {}
            }
            assertEquals(CloudBackupResult.INTEGRITY_ERROR, error.result)
        }
    }

    private fun validateDownload(url: String) = validateBackupDownload(
        downloadUrl = url,
        uploadId = "$sha256-3",
        uid = "test-account",
        generation = "902",
        allowedBuckets = setOf("test-bucket"),
    )

    private val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val url = "https://storage.googleapis.com/test-bucket/cloud02/users/" +
        "3001f754dd606e290b8f3c77e4246d6c2293435f9bf1cf970059e0f7dfb90b2e/$sha256-3" +
        "?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Credential=robot%2F20261115%2Fauto%2Fstorage%2Fgoog4_request" +
        "&X-Goog-Date=20261115T120000Z&X-Goog-Expires=120&X-Goog-SignedHeaders=host" +
        "&X-Goog-Signature=${"a".repeat(512)}&generation=902"
}
