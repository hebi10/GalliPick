package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.Orientation
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFileQueryTest {
    private val hash = "b".repeat(64)

    private fun device() = MediaItem(
        uri = "content://media/external/images/20", id = 20, volume = "external",
        displayName = "봄꽃.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = "DCIM/Camera/", sizeBytes = 300, width = 300, height = 200,
        durationMs = 0, dateTakenMs = 1731000000000, dateAddedMs = 1732000000000,
        dateModifiedMs = 1732100000000, volumeVersion = "v1",
    )

    private fun cloud() = CloudOriginal(
        uploadId = "$hash-400", displayName = "바다.mp4", mimeType = "video/mp4",
        sizeBytes = 400, sha256 = hash, verifiedAtMs = 1733000000000,
        sourceFolders = listOf("Movies/Trips"), capturedAtMs = 1730000000000,
        width = 1920, height = 1080, durationMs = 100_000,
    )

    private fun entries() = combineGalleryCatalog(
        listOf(device()), CloudGallerySnapshot("owner", listOf(cloud())), "owner",
    )

    @Test
    fun sourceFiltersDoNotChangeLocalMediaIdentity() {
        val all = GalleryFileQuery.apply(entries(), MediaFilter(), GallerySourceFilter.ALL)
        assertEquals(2, all.size)
        assertEquals(1, GalleryFileQuery.apply(entries(), MediaFilter(), GallerySourceFilter.DEVICE).size)
        val onlyCloud = GalleryFileQuery.apply(entries(), MediaFilter(), GallerySourceFilter.CLOUD)
        assertEquals(listOf(GallerySource.CLOUD), onlyCloud.map { it.source })
        assertTrue(onlyCloud.single().device == null)
    }

    @Test
    fun cloudSupportsTypeSearchFolderAndSizeFiltering() {
        val catalog = entries()
        assertEquals("바다.mp4", GalleryFileQuery.apply(catalog,
            MediaFilter(query = "Trips"), GallerySourceFilter.ALL).single().displayName)
        assertEquals("바다.mp4", GalleryFileQuery.apply(catalog,
            MediaFilter(kind = MediaKind.VIDEO), GallerySourceFilter.CLOUD).single().displayName)
        assertEquals("바다.mp4", GalleryFileQuery.apply(catalog,
            MediaFilter(folder = "Movies/Trips/"), GallerySourceFilter.CLOUD).single().displayName)
        assertTrue(GalleryFileQuery.apply(catalog, MediaFilter(minSizeBytes = 401),
            GallerySourceFilter.CLOUD).isEmpty())
        assertEquals(1, GalleryFileQuery.apply(catalog,
            MediaFilter(minDurationMs = 90_000, orientation = Orientation.LANDSCAPE),
            GallerySourceFilter.CLOUD).size)
        assertTrue(GalleryFileQuery.apply(catalog,
            MediaFilter(minDurationMs = 110_000), GallerySourceFilter.CLOUD).isEmpty())
    }

    @Test
    fun unsupportedLocalOnlyFlagsCannotPretendCloudMetadataExists() {
        for (filter in listOf(MediaFilter(favoritesOnly = true),
            MediaFilter(protectedOnly = true), MediaFilter(downloadsOnly = true))) {
            assertTrue(GalleryFileQuery.apply(entries(), filter, GallerySourceFilter.CLOUD).isEmpty())
        }
    }

    @Test
    fun missingCaptureOrModificationTimeIsNotInvented() {
        val old = cloud().copy(capturedAtMs = null, durationMs = null)
        val records = combineGalleryCatalog(emptyList(), CloudGallerySnapshot("owner", listOf(old)), "owner")
        assertTrue(GalleryFileQuery.apply(records,
            MediaFilter(fromDateMs = 1L), GallerySourceFilter.CLOUD).isEmpty())
        assertTrue(GalleryFileQuery.apply(records,
            MediaFilter(dateField = DateField.MODIFIED, toDateMs = Long.MAX_VALUE),
            GallerySourceFilter.CLOUD).isEmpty())
        assertEquals("정보 없음", GalleryFileQuery.groupByDate(records, DateField.CAPTURED, true).single().date)
        assertEquals(1, GalleryFileQuery.apply(records,
            MediaFilter(dateField = DateField.ADDED, fromDateMs = 1L), GallerySourceFilter.CLOUD).size)
    }

    @Test
    fun sortAndDateGroupingWorkAcrossBothSources() {
        val byDate = GalleryFileQuery.apply(entries(),
            MediaFilter(sortField = SortField.CAPTURED), GallerySourceFilter.ALL)
        assertEquals(listOf("봄꽃.jpg", "바다.mp4"), byDate.map { it.displayName })
        val ascending = GalleryFileQuery.apply(entries(), MediaFilter(
            sortField = SortField.CAPTURED, sortDirection = SortDirection.ASCENDING),
            GallerySourceFilter.ALL)
        assertEquals(listOf("바다.mp4", "봄꽃.jpg"), ascending.map { it.displayName })
        assertEquals(2, GalleryFileQuery.groupByDate(entries(), DateField.CAPTURED, true).size)
    }

    @Test
    fun unknownDatesStayLastForBothDirections() {
        val known = cloud()
        val unknown = known.copy(uploadId = "${"c".repeat(64)}-500",
            sha256 = "c".repeat(64), displayName = "모름.mp4", capturedAtMs = null)
        val records = combineGalleryCatalog(emptyList(),
            CloudGallerySnapshot("owner", listOf(known, unknown)), "owner")
        for (direction in listOf(SortDirection.ASCENDING, SortDirection.DESCENDING)) {
            val sorted = GalleryFileQuery.apply(records,
                MediaFilter(sortField = SortField.CAPTURED, sortDirection = direction),
                GallerySourceFilter.CLOUD)
            assertEquals("모름.mp4", sorted.last().displayName)
            assertFalse(sorted.first().displayName == "모름.mp4")
        }
    }
}
