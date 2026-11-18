package com.hebi.gallipick.data.cloud

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

internal const val CLOUD_RESUMABLE_PROTOCOL_VERSION = 2
private const val PART_SIZE_BYTES = 64L * 1024L * 1024L
private val RESUMABLE_SHA256 = Regex("[a-f0-9]{64}")
private val TRANSFER_ID = Regex("[a-f0-9]{32}")

internal data class CloudBackupProgress(val uploadedBytes: Long, val totalBytes: Long)

internal enum class CloudResumableState { RESUMABLE, VERIFYING, VERIFIED, FAILED, CANCELLED }

internal data class CloudResumablePart(val index: Int, val offsetBytes: Long, val sizeBytes: Long)

internal data class CloudResumableUpload(
    val id: String,
    val transferId: String,
    val sizeBytes: Long,
    val partSizeBytes: Long,
    val partCount: Int,
    val nextPartIndex: Int,
) {
    fun part(index: Int): CloudResumablePart? {
        if (index !in 0 until partCount) return null
        val offset = index * partSizeBytes
        return CloudResumablePart(index, offset, minOf(partSizeBytes, sizeBytes - offset))
    }
}

internal fun cloudResumableUpload(
    id: String,
    hash: String,
    sizeBytes: Long,
    transferId: String,
    partSizeBytes: Long,
    partCount: Int,
    nextPartIndex: Int = 0,
): CloudResumableUpload? {
    if (sizeBytes !in 1..CLOUD_RESUMABLE_ORIGINAL_MAX_BYTES || !RESUMABLE_SHA256.matches(hash) ||
        id != "$hash-$sizeBytes" || !TRANSFER_ID.matches(transferId) || partSizeBytes != PART_SIZE_BYTES ||
        partCount.toLong() != (sizeBytes - 1) / PART_SIZE_BYTES + 1 || nextPartIndex !in 0..partCount
    ) return null
    return CloudResumableUpload(id, transferId, sizeBytes, partSizeBytes, partCount, nextPartIndex)
}

internal fun validateResumablePartUpload(
    url: String,
    uid: String,
    upload: CloudResumableUpload,
    part: CloudResumablePart,
    mimeType: String,
    headers: Map<String, String>,
    allowedBuckets: Set<String>,
    namespace: CloudBackupNamespace,
    privateVault: Boolean = false,
): URI? {
    if (part != upload.part(part.index) || !validBackupUploadHeaders(headers, mimeType, part.sizeBytes)) return null
    val prefix = backupOwnerObjectPrefix(uid, namespace, privateVault)
    val objectKey = "$prefix/v2/staging/${upload.id}/${upload.transferId}/parts/${part.index}"
    return backupStorageUri(url, objectKey, allowedBuckets)
}

/** Streams exact ranges from a single descriptor. A retry can reopen and reposition it. */
internal class CloudResumableSource(private val openOriginal: () -> InputStream) : Closeable {
    private val buffer = ByteArray(64 * 1024)
    private var source: InputStream? = null
    private var position = 0L
    private var canSkip = true

    fun copyPart(part: CloudResumablePart, output: OutputStream, check: () -> Unit) {
        if (source == null || part.offsetBytes < position) {
            check()
            close()
            source = try { openOriginal() } catch (_: IOException) {
                failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            } catch (_: SecurityException) {
                failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            }
            position = 0L
            canSkip = true
        }
        val input = checkNotNull(source)
        while (position < part.offsetBytes) {
            check()
            val remaining = part.offsetBytes - position
            val skipped = if (canSkip) try { input.skip(minOf(remaining, 8L * 1024 * 1024)) } catch (_: IOException) {
                canSkip = false
                0L
            } else 0L
            if (skipped > remaining) failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            position += if (skipped > 0L) skipped else read(input, remaining).toLong()
        }
        val end = part.offsetBytes + part.sizeBytes
        while (position < end) {
            check()
            val count = read(input, end - position)
            position += count
            check()
            output.write(buffer, 0, count)
        }
    }

    private fun read(input: InputStream, remaining: Long): Int = try {
        input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            .takeIf { it > 0 } ?: failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
    } catch (_: IOException) {
        failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
    }

    override fun close() {
        val open = source
        source = null
        open?.close()
    }
}
