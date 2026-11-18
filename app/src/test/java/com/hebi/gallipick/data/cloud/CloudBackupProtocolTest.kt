package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudBackupProtocolTest {
    @Test
    fun acceptsOnlyTheExpectedObjectAndSignedConstraints() {
        assertNotNull(allowedTarget(url))
        assertNull(allowedTarget(url.replace("storage.googleapis.com", "storage.googleapis.com.example.org")))
        assertNull(allowedTarget(url.replace("https://", "http://")))
        assertNull(allowedTarget(url.replace("test-bucket", "unrelated-bucket")))
        assertNull(allowedTarget(url.replace("/cloud02/users/", "/other/")))
        assertNull(allowedTarget(url, headers + ("Authorization" to "not-a-header")))
        assertNull(allowedTarget(url, headers + ("x-goog-content-length-range" to "2,64")))
        assertNull(allowedTarget(url, headers - "x-goog-if-generation-match"))
    }

    @Test
    fun productionAndLicenseTestUrlsUseSeparateObjectPrefixes() {
        val live = url.replace("cloud02/users", "cloud02/production/users")
        val trial = url.replace("cloud02/users", "cloud02/license-test/users")
        assertNotNull(allowedTarget(live, namespace = CloudBackupNamespace.PRODUCTION))
        assertNotNull(allowedTarget(trial, namespace = CloudBackupNamespace.LICENSE_TEST))
        assertNull(allowedTarget(live, namespace = CloudBackupNamespace.LICENSE_TEST))
        assertNull(allowedTarget(trial, namespace = CloudBackupNamespace.PRODUCTION))
        assertNull(allowedTarget(trial))
    }

    @Test
    fun secretUploadsCannotUseTheOrdinaryObjectPath() {
        val secret = url.replace("/$hash-2?", "/secret/$hash-2?")
        assertNotNull(allowedTarget(secret, privateVault = true))
        assertNull(allowedTarget(secret))
        assertNull(allowedTarget(url, privateVault = true))
    }

    @Test
    fun stagingHashesAndRetainsExactlyTheApprovedBytes() {
        val output = ByteArrayOutputStream()
        val original = byteArrayOf(97, 98, 99)

        val digest = MediaHasher.sha256(BackupStagingInput(original.inputStream(), output, 3))

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
        assertArrayEquals(original, output.toByteArray())
    }

    @Test
    fun stagingRejectsAFileThatGrowsOrShrinksAfterSelection() {
        val output = ByteArrayOutputStream()
        for (bytes in listOf(byteArrayOf(97), byteArrayOf(97, 98, 99))) {
            val error = assertThrows(CloudBackupException::class.java) {
                MediaHasher.sha256(BackupStagingInput(bytes.inputStream(), output, 2))
            }
            assertEquals(CloudBackupResult.ORIGINAL_UNAVAILABLE, error.result)
            output.reset()
        }
    }

    private fun allowedTarget(
        location: String, signedHeaders: Map<String, String> = headers,
        namespace: CloudBackupNamespace = CloudBackupNamespace.DEVELOPMENT,
        privateVault: Boolean = false,
    ) =
        validateBackupUpload(
            uploadUrl = location,
            uploadId = "$hash-2",
            uid = "test-account",
            sha256 = hash,
            sizeBytes = 2,
            mimeType = "image/jpeg",
            headers = signedHeaders,
            allowedBuckets = setOf("test-bucket"),
            namespace = namespace,
            privateVault = privateVault,
        )

    private val hash = "c4ffcd6e373236bc4d9a6962b928d68a958ddfdd33e9bbe89386c15388c69cd0"
    private val headers = mapOf(
        "Content-Type" to "image/jpeg",
        "x-goog-content-length-range" to "2,2",
        "x-goog-if-generation-match" to "0",
    )
    private val url = "https://storage.googleapis.com/test-bucket/cloud02/users/" +
        "3001f754dd606e290b8f3c77e4246d6c2293435f9bf1cf970059e0f7dfb90b2e/$hash-2?X-Goog-Signature=fixture"
}
