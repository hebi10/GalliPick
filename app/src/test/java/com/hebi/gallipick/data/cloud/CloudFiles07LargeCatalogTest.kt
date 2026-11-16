package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFiles07LargeCatalogTest {
    private fun local(index: Int) = MediaItem(
        uri = "content://media/external/images/media/$index",
        id = index.toLong(), volume = "external",
        displayName = "EXAMPLE_device_$index.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = "DCIM/Camera/",
        sizeBytes = 100_000L + index, width = 1200, height = 800,
        durationMs = 0, dateTakenMs = 1733000000000L + index * 1000L,
        dateAddedMs = 1733000000000L + index * 1000L,
        dateModifiedMs = 1733000000000L, generationModified = 1,
        volumeVersion = "qa-version",
    )

    private fun cloud(index: Int): CloudOriginal {
        val size = 1_000L + index
        val sha = index.toString(16).padStart(64, '0')
        return CloudOriginal(
            uploadId = "$sha-$size",
            displayName = "EXAMPLE_cloud_$index.jpg",
            mimeType = "image/jpeg", sizeBytes = size, sha256 = sha,
            verifiedAtMs = 1733000000000L + index * 1000L,
            capturedAtMs = 1733000000000L + index * 1000L,
            sourceFolders = listOf("Pictures/QA"),
        )
    }

    @Test fun searchAndSourceFiltersCoverAllRecordsWhileRenderingOnlyRequestedPages() {
        val localItems = (1..600).map(::local)
        val cloudItems = (1..250).map(::cloud)
        val snapshot = CloudGallerySnapshot("owner-qa", cloudItems)
        val catalog = combineGalleryCatalog(localItems, snapshot, "owner-qa")
        assertEquals(850, catalog.size)
        assertEquals(850, catalog.map { it.key }.toSet().size)
        assertFalse(catalog.any { it.source == GallerySource.BOTH })

        val all = GalleryFileQuery.apply(catalog, MediaFilter(), GallerySourceFilter.ALL)
        assertEquals(850, all.size)
        assertEquals(90, all.take(CloudGalleryPerformancePolicy.visibleCount(all.size, 1)).size)
        assertEquals(180, all.take(CloudGalleryPerformancePolicy.visibleCount(all.size, 2)).size)
        assertEquals(850, all.take(CloudGalleryPerformancePolicy.visibleCount(all.size, 15)).size)
        assertEquals(250, GalleryFileQuery.apply(catalog, MediaFilter(),
            GallerySourceFilter.CLOUD).size)
        assertEquals(600, GalleryFileQuery.apply(catalog, MediaFilter(),
            GallerySourceFilter.DEVICE).size)

        val queried = GalleryFileQuery.apply(catalog, MediaFilter(query = "EXAMPLE_cloud_249"),
            GallerySourceFilter.ALL)
        assertEquals(1, queried.size)
        assertEquals("EXAMPLE_cloud_249.jpg", queried.single().displayName)

        val switched = combineGalleryCatalog(localItems, snapshot, "another-owner")
        assertEquals(600, switched.size)
        assertTrue(switched.all { it.source == GallerySource.DEVICE })
    }

    @Test fun oneCloudOriginalSurvivesDeviceDisappearanceWithTheSameKey() {
        val local = local(40).copy(sizeBytes = 1_040L)
        val cloud = cloud(40)
        val proof = VerifiedGalleryMatch(
            "owner-qa", local.uri, local.fingerprint,
            local.volumeVersion, cloud.uploadId, cloud.sha256,
        )
        val snapshot = CloudGallerySnapshot("owner-qa", listOf(cloud))
        val before = combineGalleryCatalog(listOf(local), snapshot,
            "owner-qa", listOf(proof))
        assertEquals(GallerySource.BOTH, before.single().source)
        val after = combineGalleryCatalog(listOf(local.copy(isTrashed = true)), snapshot,
            "owner-qa", listOf(proof))
        assertEquals(GallerySource.CLOUD, after.single().source)
        assertEquals(before.single().key, after.single().key)
    }
}
