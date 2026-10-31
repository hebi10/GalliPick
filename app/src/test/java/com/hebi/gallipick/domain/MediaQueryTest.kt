package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQueryTest {
    @Test
    fun filtersIntersectAndKeepInclusiveBoundaries() {
        val matching = media("match").copy(
            kind = MediaKind.VIDEO,
            displayName = "Summer WALK.mp4",
            relativePath = "DCIM/Camera/",
            mimeType = "video/mp4",
            sizeBytes = 5_000_000_000,
            durationMs = 60_000,
            dateTakenMs = 500,
            width = 4000,
            height = 3000,
            isFavorite = true,
            isProtected = true,
        )
        val filter = MediaFilter(
            kind = MediaKind.VIDEO,
            query = "walk",
            folder = "DCIM/Camera/",
            mimeType = "video/mp4",
            minSizeBytes = 5_000_000_000,
            maxSizeBytes = 5_000_000_000,
            minDurationMs = 60_000,
            maxDurationMs = 60_000,
            fromDateMs = 500,
            toDateMs = 500,
            minPixels = 12_000_000,
            orientation = Orientation.LANDSCAPE,
            favoritesOnly = true,
            protectedOnly = true,
        )
        val rejected = listOf(
            matching.copy(uri = "wrong-kind", kind = MediaKind.PHOTO),
            matching.copy(uri = "wrong-query", displayName = "Winter.mp4"),
            matching.copy(uri = "wrong-folder", relativePath = "Pictures/Camera/"),
            matching.copy(uri = "wrong-mime", mimeType = "video/webm"),
            matching.copy(uri = "too-small", sizeBytes = 4_999_999_999),
            matching.copy(uri = "too-large", sizeBytes = 5_000_000_001),
            matching.copy(uri = "too-short", durationMs = 59_999),
            matching.copy(uri = "too-long", durationMs = 60_001),
            matching.copy(uri = "too-old", dateTakenMs = 499),
            matching.copy(uri = "too-new", dateTakenMs = 501),
            matching.copy(uri = "too-few-pixels", width = 3999),
            matching.copy(uri = "wrong-orientation", width = 3000, height = 4000),
            matching.copy(uri = "not-favorite", isFavorite = false),
            matching.copy(uri = "not-protected", isProtected = false),
        )

        assertEquals(listOf("match"), uris(MediaQuery.apply(rejected + matching, filter)))
    }

    @Test
    fun searchMatchesNameOrFullPathWithoutCaseSensitivity() {
        val items = listOf(
            media("name").copy(displayName = "SunSET.JPG"),
            media("folder").copy(relativePath = "Pictures/Sunset/"),
            media("other"),
        )

        assertEquals(setOf("name", "folder"), uris(MediaQuery.apply(items, MediaFilter(query = " SUNset "))).toSet())
    }

    @Test
    fun folderFilterDistinguishesIdenticalLeafFolderNames() {
        val items = listOf(
            media("dcim").copy(relativePath = "DCIM/Camera/"),
            media("pictures").copy(relativePath = "Pictures/Camera/"),
            media("nested").copy(relativePath = "DCIM/Camera/Trip/"),
        )

        assertEquals(listOf("dcim"), uris(MediaQuery.apply(items, MediaFilter(folder = "DCIM/Camera/"))))
    }

    @Test
    fun durationBoundsExcludePhotosEvenWhenTheMinimumIsZero() {
        val photo = media("photo").copy(durationMs = 0)
        val zeroVideo = media("zero-video").copy(kind = MediaKind.VIDEO, durationMs = 0)
        val video = media("video").copy(kind = MediaKind.VIDEO, durationMs = 1000)

        assertEquals(setOf("zero-video", "video"), uris(MediaQuery.apply(listOf(photo, zeroVideo, video), MediaFilter(minDurationMs = 0))).toSet())
        assertEquals(listOf("zero-video"), uris(MediaQuery.apply(listOf(photo, zeroVideo, video), MediaFilter(maxDurationMs = 0))))
    }

    @Test
    fun selectedDateFieldsUseIndependentInclusiveBounds() {
        val item = media("item").copy(dateTakenMs = 100, dateAddedMs = 200, dateModifiedMs = 300)

        for ((field, timestamp) in listOf(DateField.CAPTURED to 100L, DateField.ADDED to 200L, DateField.MODIFIED to 300L)) {
            assertEquals(listOf(item), MediaQuery.apply(listOf(item), MediaFilter(dateField = field, fromDateMs = timestamp, toDateMs = timestamp)))
            assertTrue(MediaQuery.apply(listOf(item), MediaFilter(dateField = field, fromDateMs = timestamp + 1)).isEmpty())
            assertTrue(MediaQuery.apply(listOf(item), MediaFilter(dateField = field, toDateMs = timestamp - 1)).isEmpty())
        }
    }

    @Test
    fun missingCapturedDateFallsBackToAddedDate() {
        val item = media("item").copy(dateTakenMs = 0, dateAddedMs = 250)

        assertEquals(listOf(item), MediaQuery.apply(listOf(item), MediaFilter(fromDateMs = 250, toDateMs = 250)))
    }

    @Test
    fun unknownDimensionsNeverCountAsSquareOrLandscapeOrPortrait() {
        val unknown = media("unknown").copy(width = 0, height = 0)
        val missingHeight = media("height").copy(width = 3000, height = 0)
        val square = media("square").copy(width = 100, height = 100)

        for (orientation in Orientation.values()) {
            val result = MediaQuery.apply(listOf(unknown, missingHeight, square), MediaFilter(orientation = orientation))
            assertEquals(if (orientation == Orientation.SQUARE) listOf(square) else emptyList<MediaItem>(), result)
        }
        assertEquals(listOf(square), MediaQuery.apply(listOf(unknown, missingHeight, square), MediaFilter(minPixels = 1)))
    }

    @Test
    fun metadataWithZeroValuesCanBeFilteredWithoutCrashing() {
        val unknown = media("unknown").copy(sizeBytes = 0, dateTakenMs = 0, dateAddedMs = 0, dateModifiedMs = 0, width = 0, height = 0)

        assertEquals(listOf(unknown), MediaQuery.apply(listOf(unknown), MediaFilter(maxSizeBytes = 0, toDateMs = 0, minPixels = 0)))
        assertTrue(MediaQuery.apply(listOf(unknown), MediaFilter(minSizeBytes = 1)).isEmpty())
    }

    @Test
    fun numericSortsSupportBothDirectionsAndAlwaysBreakTiesByUri() {
        val low = media("z").copy(sizeBytes = 1, durationMs = 1, dateTakenMs = 1, dateAddedMs = 1, dateModifiedMs = 1, width = 1, height = 1)
        val highB = media("b").copy(sizeBytes = 5_000_000_000, durationMs = 5_000_000_000, dateTakenMs = 5_000_000_000, dateAddedMs = 5_000_000_000, dateModifiedMs = 5_000_000_000, width = 100_000, height = 100_000)
        val highA = highB.copy(uri = "a")
        val fields = SortField.values().filter { it != SortField.NAME }

        for (field in fields) {
            assertEquals(listOf("z", "a", "b"), uris(MediaQuery.apply(listOf(highB, low, highA), MediaFilter(sortField = field, sortDirection = SortDirection.ASCENDING))))
            assertEquals(listOf("a", "b", "z"), uris(MediaQuery.apply(listOf(low, highB, highA), MediaFilter(sortField = field, sortDirection = SortDirection.DESCENDING))))
        }
    }

    @Test
    fun nameSortIgnoresCaseAndUsesUriForTies() {
        val items = listOf(media("b").copy(displayName = "ALPHA"), media("z").copy(displayName = "beta"), media("a").copy(displayName = "alpha"))

        assertEquals(listOf("a", "b", "z"), uris(MediaQuery.apply(items, MediaFilter(sortField = SortField.NAME, sortDirection = SortDirection.ASCENDING))))
        assertEquals(listOf("z", "a", "b"), uris(MediaQuery.apply(items, MediaFilter(sortField = SortField.NAME, sortDirection = SortDirection.DESCENDING))))
    }

    private fun uris(items: List<MediaItem>) = items.map { it.uri }
}

internal fun media(uri: String): MediaItem = MediaItem(
    uri = uri,
    id = 1,
    volume = "external_primary",
    displayName = "photo.jpg",
    kind = MediaKind.PHOTO,
    mimeType = "image/jpeg",
    relativePath = "Pictures/",
    sizeBytes = 1024,
    width = 1920,
    height = 1080,
    durationMs = 0,
    dateTakenMs = 1000,
    dateAddedMs = 1000,
    dateModifiedMs = 1000,
)
