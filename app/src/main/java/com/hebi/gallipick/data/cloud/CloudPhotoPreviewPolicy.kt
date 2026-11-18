package com.hebi.gallipick.data.cloud

/**
 * Automatic gallery thumbnails use a conservative transfer budget until
 * the server offers independently stored, verified thumbnail objects.
 * Larger photos remain available on explicit tap via a private temporary file.
 */
internal fun cloudPhotoSupportsThumbnail(original: CloudOriginal): Boolean =
    original.mimeType.startsWith("image/") &&
        original.sizeBytes in 1..CLOUD_PREVIEW_MAX_BYTES

internal fun cloudPhotoSupportsViewer(original: CloudOriginal, maximumBytes: Long = CLOUD_BACKUP_MAX_BYTES): Boolean =
    original.mimeType.startsWith("image/") &&
        original.sizeBytes in 1..maximumBytes

internal fun cloudPhotoPosition(photos: List<CloudOriginal>, current: CloudOriginal): Int =
    photos.indexOfFirst { it.uploadId == current.uploadId }
