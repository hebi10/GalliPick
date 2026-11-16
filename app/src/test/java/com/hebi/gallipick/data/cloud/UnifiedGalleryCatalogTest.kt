package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedGalleryCatalogTest {
    private val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun device(uri: String = "content://media/external/images/11") = MediaItem(
        uri = uri, id = 11, volume = "external", displayName = "IMG_11.jpg", kind = MediaKind.PHOTO,
        mimeType = "image/jpeg", relativePath = "DCIM/Camera/", sizeBytes = 3, width = 1920,
        height = 1080, durationMs = 0, dateTakenMs = 12345, dateAddedMs = 12346,
        dateModifiedMs = 12347, generationModified = 10, volumeVersion = "v2",
    )

    private fun original() = CloudOriginal(
        uploadId = "$digest-3", displayName = "IMG_11.jpg", mimeType = "image/jpeg",
        sizeBytes = 3, sha256 = digest, verifiedAtMs = 13333,
        sourceFolders = listOf("DCIM/Camera"), capturedAtMs = 12345, width = 1920, height = 1080,
    )

    private fun proof(device: MediaItem) = VerifiedGalleryMatch(
        accountUid = "owner-a", localUri = device.uri, localFingerprint = device.fingerprint,
        localVolumeVersion = device.volumeVersion, cloudUploadId = "$digest-3", sha256 = digest,
    )

    @Test
    fun doesNotExposeCloudItemsFromAnOldAccount() {
        val local = device()
        val stale = CloudGallerySnapshot("owner-a", listOf(original()))
        val result = combineGalleryCatalog(listOf(local), stale, "owner-b", listOf(proof(local)))
        assertEquals(1, result.size)
        assertEquals(GallerySource.DEVICE, result.single().source)
        assertEquals(GalleryEntryKey.Device(local.uri), result.single().key)
        assertTrue(combineGalleryCatalog(emptyList(), stale, null).isEmpty())
    }

    @Test
    fun matchingNeedsAFullByteProofAndUnchangedDeviceVersion() {
        val local = device()
        val snapshot = CloudGallerySnapshot("owner-a", listOf(original()))
        val unmatched = combineGalleryCatalog(listOf(local), snapshot, "owner-a")
        assertEquals(listOf(GallerySource.DEVICE, GallerySource.CLOUD), unmatched.map { it.source })
        val stale = combineGalleryCatalog(listOf(local.copy(generationModified = 11)), snapshot, "owner-a", listOf(proof(local)))
        assertEquals(2, stale.size)
        assertFalse(stale.any { it.source == GallerySource.BOTH })
        val valid = combineGalleryCatalog(listOf(local), snapshot, "owner-a", listOf(proof(local)))
        assertEquals(listOf(GallerySource.BOTH), valid.map { it.source })
        assertEquals(GalleryEntryKey.Cloud("owner-a", original().uploadId), valid.single().key)
        assertEquals("DCIM/Camera", valid.single().sourceFolders.single())
    }

    @Test
    fun cloudIdentitySurvivesRemovingTheVerifiedDeviceCopy() {
        val local = device()
        val snapshot = CloudGallerySnapshot("owner-a", listOf(original()))
        val both = combineGalleryCatalog(listOf(local), snapshot, "owner-a", listOf(proof(local))).single()
        val cloudOnly = combineGalleryCatalog(emptyList(), snapshot, "owner-a").single()
        assertEquals(GallerySource.CLOUD, cloudOnly.source)
        assertEquals(both.key, cloudOnly.key)
        assertEquals(local.displayName, cloudOnly.displayName)
        assertEquals(local.dateTakenMs, cloudOnly.capturedAtMs)
    }

    @Test
    fun noGuessworkFromMatchingFileNamesOrDifferentHashes() {
        val local = device()
        val snapshot = CloudGallerySnapshot("owner-a", listOf(original()))
        val wrongHash = proof(local).copy(sha256 = "a".repeat(64))
        val wrongOwner = proof(local).copy(accountUid = "owner-b")
        for (link in listOf(wrongHash, wrongOwner)) {
            val entries = combineGalleryCatalog(listOf(local), snapshot, "owner-a", listOf(link))
            assertFalse(entries.any { it.source == GallerySource.BOTH })
        }
        assertEquals(2, combineGalleryCatalog(listOf(local), snapshot, "owner-a").size)
    }

    @Test
    fun mismatchedMimeTypesNeverMergeDespiteEqualBytes() {
        val local = device()
        val cloud = original().copy(mimeType = "video/mp4")
        val entries = combineGalleryCatalog(listOf(local), CloudGallerySnapshot("owner-a", listOf(cloud)),
            "owner-a", listOf(proof(local)))
        assertEquals(listOf(GallerySource.DEVICE, GallerySource.CLOUD), entries.map { it.source })
    }

    @Test
    fun cloudPhotoNavigationIncludesVerifiedBothEntriesAndUsesUploadIds() {
        val local = device()
        val selected = original()
        val next = selected.copy(
            uploadId = "${"b".repeat(64)}-4",
            sha256 = "b".repeat(64),
            sizeBytes = 4,
            displayName = "NEXT.jpg",
        )
        val video = selected.copy(
            uploadId = "${"c".repeat(64)}-4",
            sha256 = "c".repeat(64),
            sizeBytes = 4,
            mimeType = "video/mp4",
        )
        val snapshot = CloudGallerySnapshot("owner-a", listOf(selected, next, video))
        val entries = combineGalleryCatalog(listOf(local), snapshot, "owner-a", listOf(proof(local)))
        assertEquals(GallerySource.BOTH, entries.first().source)
        assertEquals(listOf(selected.uploadId, next.uploadId),
            cloudGalleryPhotos(entries, selected).map { it.uploadId })
        assertEquals(listOf(next.uploadId),
            cloudGalleryPhotos(entries, next).filter { it.uploadId == next.uploadId }.map { it.uploadId })
        val removed = combineGalleryCatalog(emptyList(), snapshot, "owner-a")
        assertEquals(
            cloudGalleryPhotos(entries, selected).map { it.uploadId },
            cloudGalleryPhotos(removed, selected).map { it.uploadId },
        )
        val absent = selected.copy(uploadId = "${"d".repeat(64)}-8")
        assertEquals(listOf(absent), cloudGalleryPhotos(entries, absent))
    }

    @Test
    fun folderMetadataIsNormalizedWithoutAcceptingTraversal() {
        assertEquals("DCIM/Camera", normalizeCloudFolderPath("DCIM/Camera/"))
        assertEquals("여행/부산", normalizeCloudFolderPath("여행/부산/"))
        for (invalid in listOf("../DCIM", "DCIM/../Camera", "/DCIM/Camera", "DCIM//Camera",
                "DCIM\\Camera", "DCIM/./Camera", "", "a".repeat(161))) {
            assertNull(normalizeCloudFolderPath(invalid))
        }
        val metadata = CloudUploadMetadata.from(device())
        assertEquals("DCIM/Camera", metadata.sourceFolder)
        assertEquals(12345L, metadata.capturedAtMs)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
        assertNull(metadata.durationMs)
        val legacy = original().copy(sourceFolders = emptyList(), capturedAtMs = null)
        assertTrue(legacy.sourceFolders.isEmpty())
        val restored = combineGalleryCatalog(emptyList(), CloudGallerySnapshot("owner-a", listOf(legacy)), "owner-a").single()
        assertNull(restored.capturedAtMs)
        assertEquals(13333L, restored.sortTimeMs)
    }
}
