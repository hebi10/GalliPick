package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind

/**
 * A presentation catalog only. MediaStore remains authoritative for device actions;
 * cloud originals are never represented by synthetic device URIs.
 */
internal enum class GallerySource { DEVICE, CLOUD, BOTH }

internal sealed interface GalleryEntryKey {
    data class Device(val uri: String) : GalleryEntryKey
    data class Cloud(val accountUid: String, val uploadId: String) : GalleryEntryKey
}

internal data class CloudGallerySnapshot(
    val accountUid: String,
    val originals: List<CloudOriginal>,
)

/** A match is usable only after a separate full-byte hash verification. */
internal data class VerifiedGalleryMatch(
    val accountUid: String,
    val localUri: String,
    val localFingerprint: String,
    val localVolumeVersion: String,
    val cloudUploadId: String,
    val sha256: String,
)

internal data class GalleryEntry(
    val key: GalleryEntryKey,
    val device: MediaItem? = null,
    val cloud: CloudOriginal? = null,
) {
    init { require(device != null || cloud != null) }

    val source: GallerySource get() = when {
        device != null && cloud != null -> GallerySource.BOTH
        device != null -> GallerySource.DEVICE
        else -> GallerySource.CLOUD
    }
    val displayName: String get() = device?.displayName ?: checkNotNull(cloud).displayName
    val kind: MediaKind get() = device?.kind ?: if (checkNotNull(cloud).mimeType.startsWith("video/")) {
        MediaKind.VIDEO
    } else MediaKind.PHOTO
    val sizeBytes: Long get() = device?.sizeBytes ?: checkNotNull(cloud).sizeBytes
    // A verified upload timestamp is not a capture timestamp.
    val capturedAtMs: Long? get() = device?.dateTakenMs?.takeIf { it > 0 } ?: cloud?.capturedAtMs
    val sortTimeMs: Long get() = capturedAtMs ?: device?.dateAddedMs?.takeIf { it > 0 }
        ?: cloud?.verifiedAtMs ?: 0L
    val sourceFolders: List<String> get() = (
        listOfNotNull(device?.relativePath?.let(::normalizeCloudFolderPath)) +
            cloud?.sourceFolders.orEmpty()
    ).distinct()
}

/**
 * Builds a read-only combined view. An account-specific snapshot must match the
 * active account; a stale async response is never shown for a new login.
 *
 * Links are proofs from the verifier, not guesses based on names or timestamps.
 * A cloud key survives BOTH -> CLOUD when the owner removes a device copy.
 */
internal fun combineGalleryCatalog(
    deviceItems: List<MediaItem>,
    cloudSnapshot: CloudGallerySnapshot?,
    activeAccountUid: String?,
    verifiedMatches: List<VerifiedGalleryMatch> = emptyList(),
): List<GalleryEntry> {
    val validSnapshot = cloudSnapshot?.takeIf {
        activeAccountUid != null && activeAccountUid.isNotBlank() && it.accountUid == activeAccountUid
    }
    val originals = validSnapshot?.originals.orEmpty().associateBy { it.uploadId }
    val accounted = mutableSetOf<String>()
    val proofsByUri = verifiedMatches.groupBy { it.localUri }

    val devices = deviceItems.asSequence().filterNot { it.isTrashed }.map { device ->
        val cloud = proofsByUri[device.uri].orEmpty().firstNotNullOfOrNull { proof ->
            val original = originals[proof.cloudUploadId]
            original?.takeIf {
                proof.accountUid == validSnapshot?.accountUid &&
                    device.volumeVersion.isNotBlank() &&
                    proof.localFingerprint == device.fingerprint &&
                    proof.localVolumeVersion == device.volumeVersion &&
                    proof.sha256 == it.sha256 &&
                    device.sizeBytes == it.sizeBytes &&
                    device.mimeType.equals(it.mimeType, ignoreCase = true) &&
                    it.uploadId !in accounted
            }
        }
        if (cloud != null) {
            accounted += cloud.uploadId
            GalleryEntry(GalleryEntryKey.Cloud(checkNotNull(activeAccountUid), cloud.uploadId), device, cloud)
        } else {
            GalleryEntry(GalleryEntryKey.Device(device.uri), device)
        }
    }.toList()

    val remaining = validSnapshot?.originals.orEmpty().asSequence()
        .filterNot { it.uploadId in accounted }
        .distinctBy { it.uploadId }
        .map { GalleryEntry(GalleryEntryKey.Cloud(validSnapshot!!.accountUid, it.uploadId), cloud = it) }
        .toList()
    return devices + remaining
}

/** Keeps verified BOTH entries available when paging cloud photos in CLOUD mode. */
internal fun cloudGalleryPhotos(entries: List<GalleryEntry>, selected: CloudOriginal): List<CloudOriginal> {
    val photos = entries.asSequence()
        .mapNotNull { it.cloud }
        .filter { it.mimeType.startsWith("image/") }
        .distinctBy { it.uploadId }
        .toList()
    return if (photos.any { it.uploadId == selected.uploadId }) photos else listOf(selected)
}
