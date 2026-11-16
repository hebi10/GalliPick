package com.hebi.gallipick.data.cloud

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVideoStreamPolicyTest {
    private val digest = "b".repeat(64)

    private fun original(
        mime: String = "video/mp4", bytes: Long = 2048L,
    ): CloudOriginal = CloudOriginal(
        uploadId = "$digest-$bytes",
        displayName = "family-video.mp4",
        mimeType = mime,
        sizeBytes = bytes,
        sha256 = digest,
        verifiedAtMs = 1740000000000,
        durationMs = 120_000,
    )

    @Test
    fun onlyVerifiedVideoSizedForThePilotCanBeStreamed() {
        assertTrue(isCloudVideoStreamable(original()))
        assertTrue(isCloudVideoStreamable(original(bytes = CLOUD_BACKUP_MAX_BYTES)))
        assertFalse(isCloudVideoStreamable(original(mime = "image/jpeg")))
        assertFalse(isCloudVideoStreamable(original(bytes = 0)))
        assertFalse(isCloudVideoStreamable(original(bytes = CLOUD_BACKUP_MAX_BYTES + 1)))
        assertFalse(isCloudVideoStreamable(original().copy(sha256 = "invalid")))
        assertFalse(isCloudVideoStreamable(original().copy(uploadId = "spoofed")))
    }

    @Test
    fun shortLivedCapabilityIsNotLoggedAndRenewsBeforeTheServerExpiry() {
        val secret = "X-Goog-Signature=" + "a".repeat(512)
        val capability = CloudVideoStreamCapability(
            signedUrl = URI("https://storage.googleapis.com/verified-object?$secret"),
            generation = "1740000000000001",
            obtainedAtElapsedMs = 2000L,
        )
        assertFalse(capability.shouldRenew(3000L))
        assertFalse(capability.shouldRenew(91_999L))
        assertTrue(capability.shouldRenew(92_000L))
        assertTrue(capability.shouldRenew(1999L))
        assertFalse(capability.toString().contains(secret))
        assertFalse(capability.toString().contains("1740000000000001"))
    }

    @Test
    fun seekPositionCannotGoOutsideTheKnownDuration() {
        assertEquals(0, safeCloudVideoPosition(-100, 100_000))
        assertEquals(12_345, safeCloudVideoPosition(12_345, 100_000))
        assertEquals(99_999, safeCloudVideoPosition(Int.MAX_VALUE, 100_000))
        assertEquals(0, safeCloudVideoPosition(1000, 0))
    }
}
