package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaItem
import java.util.Locale

/** Verification reads original media bytes only for plausible account-owned originals. */
internal object CloudGalleryMatchPolicy {
    const val INITIAL_BATCHES = 1
    const val MAX_FILES_PER_BATCH = 6
    const val MAX_BYTES_PER_BATCH = 96L * 1024L * 1024L

    private val sha256 = Regex("[0-9a-f]{64}")

    fun validOriginals(originals: List<CloudOriginal>): List<CloudOriginal> =
        originals.filter { original ->
            original.sizeBytes in 1..CLOUD_BACKUP_MAX_BYTES &&
                original.sha256.matches(sha256) &&
                original.uploadId == "${original.sha256}-${original.sizeBytes}" &&
                CLOUD_MEDIA_MIME_TYPE.matches(original.mimeType)
        }

    fun candidates(deviceItems: List<MediaItem>, originals: List<CloudOriginal>): List<MediaItem> {
        val allowed = validOriginals(originals)
            .mapTo(hashSetOf()) { it.sizeBytes to it.mimeType.lowercase(Locale.ROOT) }
        if (allowed.isEmpty()) return emptyList()
        return deviceItems.asSequence()
            .filter {
                !it.isTrashed && it.volumeVersion.isNotBlank() && it.generationModified > 0 &&
                    it.sizeBytes in 1..CLOUD_BACKUP_MAX_BYTES &&
                    (it.sizeBytes to it.mimeType.lowercase(Locale.ROOT)) in allowed
            }
            .distinctBy { it.uri }
            .sortedWith(compareBy<MediaItem> { it.sizeBytes }.thenBy { it.uri })
            .toList()
    }

    fun nextBatch(
        candidates: List<MediaItem>,
        completed: Int,
        maxFiles: Int = MAX_FILES_PER_BATCH,
        maxBytes: Long = MAX_BYTES_PER_BATCH,
    ): List<MediaItem> {
        if (completed !in 0 until candidates.size || maxFiles <= 0 || maxBytes <= 0) return emptyList()
        var total = 0L
        return buildList {
            for (item in candidates.drop(completed)) {
                if (size >= maxFiles || item.sizeBytes > maxBytes - total) break
                add(item)
                total += item.sizeBytes
            }
        }
    }

    /**
     * The MediaStore provider must still expose exactly the same original after
     * SHA-256 verification. Favorite/protected files can be linked for viewing;
     * existing Android trash protections decide whether they may be removed.
     */
    fun sameDeviceOriginal(expected: MediaItem, current: MediaItem?): Boolean =
        current != null && !expected.isTrashed && !current.isTrashed &&
            expected.uri == current.uri && expected.id == current.id &&
            expected.volume == current.volume && expected.volumeVersion.isNotBlank() &&
            expected.volumeVersion == current.volumeVersion &&
            expected.generationModified > 0 && expected.generationModified == current.generationModified &&
            expected.fingerprint == current.fingerprint &&
            expected.sizeBytes == current.sizeBytes &&
            expected.mimeType == current.mimeType && expected.kind == current.kind &&
            expected.relativePath == current.relativePath &&
            expected.displayName == current.displayName &&
            expected.width == current.width && expected.height == current.height &&
            expected.durationMs == current.durationMs
}
