package com.hebi.gallipick.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPhotoPreviewPolicyTest {
    private val sha = "b".repeat(64)

    private fun photo(size: Long = 5L * 1024 * 1024): CloudOriginal = CloudOriginal(
        uploadId = "$sha-$size", displayName = "IMG_100.jpg",
        mimeType = "image/jpeg", sizeBytes = size, sha256 = sha,
        verifiedAtMs = 1730000000000,
    )

    @Test fun thumbnailsRequireBoundedPhotoBytes() {
        assertTrue(cloudPhotoSupportsThumbnail(photo()))
        assertTrue(cloudPhotoSupportsThumbnail(photo(CLOUD_PREVIEW_MAX_BYTES)))
        assertFalse(cloudPhotoSupportsThumbnail(photo(CLOUD_PREVIEW_MAX_BYTES + 1)))
        assertFalse(cloudPhotoSupportsThumbnail(photo(0)))
        assertFalse(cloudPhotoSupportsThumbnail(photo().copy(mimeType = "video/mp4")))
    }

    @Test fun explicitViewerAcceptsVerifiedOriginalUpToBackupLimit() {
        assertTrue(cloudPhotoSupportsViewer(photo(CLOUD_PREVIEW_MAX_BYTES + 1)))
        assertTrue(cloudPhotoSupportsViewer(photo(CLOUD_BACKUP_MAX_BYTES)))
        assertFalse(cloudPhotoSupportsViewer(photo(CLOUD_BACKUP_MAX_BYTES + 1)))
        assertFalse(cloudPhotoSupportsViewer(photo().copy(mimeType = "video/mp4")))
    }

    @Test fun photoNavigationUsesStableUploadIdNotFilename() {
        val first = photo().copy(displayName = "same.jpg")
        val second = photo(6L * 1024 * 1024).copy(displayName = "same.jpg")
        val list = listOf(first, second)
        assertEquals(0, cloudPhotoPosition(list, first))
        assertEquals(1, cloudPhotoPosition(list, second))
        assertEquals(-1, cloudPhotoPosition(list, photo(7L * 1024 * 1024)))
    }
}
