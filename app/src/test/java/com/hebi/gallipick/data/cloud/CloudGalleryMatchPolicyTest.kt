package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGalleryMatchPolicyTest {
    private val hash = "a".repeat(64)

    private fun original(
        mime: String = "image/jpeg",
        bytes: Long = 100L,
        digest: String = hash,
    ) = CloudOriginal(
        uploadId = "$digest-$bytes", displayName = "클라우드-다른-이름.jpg",
        mimeType = mime, sizeBytes = bytes, sha256 = digest,
        verifiedAtMs = 1700000000000,
    )

    private fun device(
        id: Long = 42L, bytes: Long = 100L, mime: String = "image/jpeg",
    ) = MediaItem(
        uri = "content://media/external/images/media/$id",
        id = id, volume = "external", displayName = "device-original.jpg",
        kind = MediaKind.PHOTO, mimeType = mime, relativePath = "DCIM/Camera/",
        sizeBytes = bytes, width = 640, height = 480, durationMs = 0,
        dateTakenMs = 1700000000000, dateAddedMs = 1700000000000,
        dateModifiedMs = 1700000000000, generationModified = 1,
        volumeVersion = "version-one",
    )

    @Test fun onlySameSizedMediaTypeCandidatesAreHashed() {
        val local = device()
        val differentName = original()
        assertEquals(listOf(local), CloudGalleryMatchPolicy.candidates(
            listOf(local), listOf(differentName)))
        assertTrue(CloudGalleryMatchPolicy.candidates(
            listOf(local), listOf(original(mime = "video/mp4"))).isEmpty())
        assertTrue(CloudGalleryMatchPolicy.candidates(
            listOf(local), listOf(original(bytes = 101))).isEmpty())
        assertTrue(CloudGalleryMatchPolicy.candidates(
            listOf(local), listOf(original().copy(uploadId = "forged"))).isEmpty())
        assertTrue(CloudGalleryMatchPolicy.candidates(
            listOf(local), listOf(original().copy(sha256 = "invalid"))).isEmpty())
    }

    @Test fun onlyVisibleStableLocalOriginalsEnterVerification() {
        val local = device()
        val input = listOf(
            local, local, local.copy(isTrashed = true),
            device(id = 43).copy(volumeVersion = ""),
            device(id = 44).copy(generationModified = 0),
            device(id = 45).copy(sizeBytes = 0),
        )
        assertEquals(listOf(local), CloudGalleryMatchPolicy.candidates(input, listOf(original())))
    }

    @Test fun boundedBatchLimitsReadBytesAndNeverSkipsUnscannedFiles() {
        val miB = 1024L * 1024L
        val originals = listOf(original(bytes = 50 * miB))
        val files = listOf(
            device(1, 50 * miB), device(2, 50 * miB), device(3, 50 * miB),
        )
        val candidates = CloudGalleryMatchPolicy.candidates(files, originals)
        assertEquals(3, candidates.size)
        val first = CloudGalleryMatchPolicy.nextBatch(candidates, 0)
        assertEquals(listOf(files[0]), first)
        val second = CloudGalleryMatchPolicy.nextBatch(candidates, first.size)
        assertEquals(listOf(files[1]), second)
        assertEquals(listOf(files[2]), CloudGalleryMatchPolicy.nextBatch(candidates, 2))
        assertTrue(CloudGalleryMatchPolicy.nextBatch(candidates, 3).isEmpty())
        assertTrue(CloudGalleryMatchPolicy.nextBatch(candidates, -1).isEmpty())
    }

    @Test fun localMetadataChangesInvalidateAPreviouslyVerifiedLink() {
        val local = device()
        assertTrue(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy()))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local, null))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(generationModified = 2)))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(volumeVersion = "new")))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(displayName = "renamed.jpg")))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(relativePath = "Pictures/")))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(isTrashed = true)))
    }

    @Test fun favoriteAndProtectedCopiesCanBeLinkedWithoutAuthorizingDeletion() {
        val local = device()
        assertTrue(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(isFavorite = true)))
        assertTrue(CloudGalleryMatchPolicy.sameDeviceOriginal(local, local.copy(isProtected = true)))
        assertFalse(CloudGalleryMatchPolicy.sameDeviceOriginal(local.copy(volumeVersion = ""), local))
    }

    @Test fun verifiedCatalogPersistsAfterDeviceCopyIsTrashed() {
        val item = device()
        val record = original()
        val snapshot = CloudGallerySnapshot("owner-a", listOf(record))
        val proof = VerifiedGalleryMatch(
            "owner-a", item.uri, item.fingerprint,
            item.volumeVersion, record.uploadId, record.sha256,
        )
        val both = combineGalleryCatalog(listOf(item), snapshot, "owner-a", listOf(proof)).single()
        assertEquals(GallerySource.BOTH, both.source)
        val cloudOnly = combineGalleryCatalog(listOf(item.copy(isTrashed = true)),
            snapshot, "owner-a", listOf(proof)).single()
        assertEquals(GallerySource.CLOUD, cloudOnly.source)
        assertEquals(both.key, cloudOnly.key)
        assertTrue(cloudOnly.device == null)
        val accountChanged = combineGalleryCatalog(listOf(item.copy(isTrashed = true)),
            snapshot, "owner-b", listOf(proof))
        assertTrue(accountChanged.isEmpty())
    }
}
