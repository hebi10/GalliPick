package com.hebi.gallipick.data.cloud

import java.net.URI

/**
 * An in-memory capability for a verified cloud original. Never persist or log
 * the bearer URL: it grants access to the object until the signature expires.
 */
internal class CloudVideoStreamCapability(
    val signedUrl: URI,
    val generation: String,
    private val obtainedAtElapsedMs: Long,
) {
    fun shouldRenew(nowElapsedMs: Long): Boolean =
        nowElapsedMs < obtainedAtElapsedMs || nowElapsedMs - obtainedAtElapsedMs >= 90_000L

    override fun toString(): String = "CloudVideoStreamCapability(redacted)"
}

internal fun isCloudVideoStreamable(original: CloudOriginal): Boolean =
    original.mimeType.startsWith("video/") &&
        original.sizeBytes in 1..CLOUD_BACKUP_MAX_BYTES &&
        original.sha256.matches(Regex("[a-f0-9]{64}")) &&
        original.uploadId == "${original.sha256}-${original.sizeBytes}"

internal fun safeCloudVideoPosition(requestedMs: Int, durationMs: Int): Int =
    requestedMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))

/** A 1-byte partial GET proves that the signed capability supports seeking. */
internal fun validCloudVideoRange(
    status: Int,
    contentRange: String?,
    contentLength: String?,
    responseGeneration: String?,
    contentEncoding: String?,
    expectedGeneration: String,
    sizeBytes: Long,
): Boolean = status == 206 &&
    contentRange == "bytes 0-0/$sizeBytes" &&
    contentLength?.toLongOrNull() == 1L &&
    responseGeneration == expectedGeneration &&
    (contentEncoding == null || contentEncoding.equals("identity", ignoreCase = true))
