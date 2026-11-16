package com.hebi.gallipick.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudGalleryPerformancePolicyTest {
    @Test fun pagingAlwaysStartsWithOneBoundedPage() {
        assertEquals(0, CloudGalleryPerformancePolicy.visibleCount(0, 1))
        assertEquals(8, CloudGalleryPerformancePolicy.visibleCount(8, 1))
        assertEquals(90, CloudGalleryPerformancePolicy.visibleCount(1000, 1))
        assertEquals(180, CloudGalleryPerformancePolicy.visibleCount(1000, 2))
        assertEquals(1000, CloudGalleryPerformancePolicy.visibleCount(1000, 100))
        assertEquals(90, CloudGalleryPerformancePolicy.visibleCount(1000, -10))
    }

    @Test fun hugePageRequestsCannotOverflowTheVisibleCount() {
        assertEquals(Int.MAX_VALUE,
            CloudGalleryPerformancePolicy.visibleCount(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(123, CloudGalleryPerformancePolicy.visibleCount(123, Int.MAX_VALUE))
    }

    @Test fun mobileDataRequiresASeparateExplicitThumbnailPermission() {
        assertTrue(CloudGalleryPerformancePolicy.canLoadThumbnails(true, true, false))
        assertTrue(CloudGalleryPerformancePolicy.canLoadThumbnails(true, false, true))
        assertFalse(CloudGalleryPerformancePolicy.canLoadThumbnails(true, false, false))
        assertFalse(CloudGalleryPerformancePolicy.canLoadThumbnails(false, true, true))
        assertFalse(CloudGalleryPerformancePolicy.canLoadThumbnails(false, false, false))
    }
}
