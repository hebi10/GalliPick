package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFilterActionsTest {
    @Test fun sizeSortingPreservesTheActiveSearchConditions() {
        val filter = MediaFilter(kind = MediaKind.PHOTO, query = "trip", folder = "DCIM/Camera/", mimeType = "image/jpeg", minSizeBytes = 4096, favoritesOnly = true)
        assertEquals(filter.copy(sortField = SortField.SIZE, sortDirection = SortDirection.ASCENDING),
            MediaFilterActions.sort(filter, SortField.SIZE, SortDirection.ASCENDING))
    }

    @Test fun videoLengthSortingSwitchesTypeAndRemovesIncompatiblePhotoMime() {
        val filter = MediaFilter(kind = MediaKind.PHOTO, query = "trip", folder = "Download/", mimeType = "IMAGE/JPEG", minSizeBytes = 4096)
        assertEquals(filter.copy(kind = MediaKind.VIDEO, mimeType = "", sortField = SortField.DURATION, sortDirection = SortDirection.ASCENDING),
            MediaFilterActions.sort(filter, SortField.DURATION, SortDirection.ASCENDING))
    }

    @Test fun videoLengthSortingKeepsCompatibleMimeAndDurationRange() {
        val filter = MediaFilter(mimeType = "video/mp4", minDurationMs = 60_000, maxDurationMs = 600_000)
        assertEquals(filter.copy(kind = MediaKind.VIDEO, sortField = SortField.DURATION),
            MediaFilterActions.sort(filter, SortField.DURATION, SortDirection.DESCENDING))
    }

    @Test fun switchingToPhotosRemovesVideoOnlyConditionsAndRestoresDateSorting() {
        val filter = MediaFilter(kind = MediaKind.VIDEO, query = "trip", mimeType = "VIDEO/MP4", minDurationMs = 60_000, maxDurationMs = 600_000, sortField = SortField.DURATION, sortDirection = SortDirection.ASCENDING)
        val next = MediaFilterActions.kind(filter, MediaKind.PHOTO)
        assertEquals(filter.copy(kind = MediaKind.PHOTO, mimeType = "", minDurationMs = null, maxDurationMs = null, sortField = SortField.CAPTURED, sortDirection = SortDirection.DESCENDING), next)
    }

    @Test fun switchingToAllClearsDurationBoundsButKeepsOtherConditions() {
        val filter = MediaFilter(kind = MediaKind.VIDEO, query = "trip", minDurationMs = 60_000, sortField = SortField.SIZE)
        val next = MediaFilterActions.kind(filter, null)
        assertEquals(filter.copy(kind = null, minDurationMs = null), next)
        assertNull(next.maxDurationMs)
    }
}
