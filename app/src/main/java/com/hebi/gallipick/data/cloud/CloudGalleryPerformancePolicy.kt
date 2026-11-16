package com.hebi.gallipick.data.cloud

/** Keep UI memory stable when local media counts grow into the thousands. */
internal object CloudGalleryPerformancePolicy {
    const val PAGE_SIZE = 90

    fun visibleCount(total: Int, pages: Int): Int {
        if (total <= 0) return 0
        return (pages.coerceAtLeast(1).toLong() * PAGE_SIZE)
            .coerceAtMost(total.toLong()).toInt()
    }

    fun canLoadThumbnails(online: Boolean, unmetered: Boolean, allowMetered: Boolean): Boolean =
        online && (unmetered || allowMetered)
}
