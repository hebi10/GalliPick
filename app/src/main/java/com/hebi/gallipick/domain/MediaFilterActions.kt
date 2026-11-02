package com.hebi.gallipick.domain

object MediaFilterActions {
    fun sort(filter: MediaFilter, field: SortField, direction: SortDirection): MediaFilter =
        filter.copy(
            kind = if (field == SortField.DURATION) MediaKind.VIDEO else filter.kind,
            mimeType = if (field == SortField.DURATION && !filter.mimeType.startsWith("video/", ignoreCase = true)) "" else filter.mimeType,
            sortField = field,
            sortDirection = direction,
        )

    fun kind(filter: MediaFilter, kind: MediaKind?): MediaFilter {
        val resetDuration = kind != MediaKind.VIDEO
        val incompatibleMime = (kind == MediaKind.PHOTO && filter.mimeType.startsWith("video/", ignoreCase = true)) ||
            (kind == MediaKind.VIDEO && filter.mimeType.startsWith("image/", ignoreCase = true))
        val resetSort = resetDuration && filter.sortField == SortField.DURATION
        return filter.copy(
            kind = kind,
            mimeType = if (incompatibleMime) "" else filter.mimeType,
            minDurationMs = if (resetDuration) null else filter.minDurationMs,
            maxDurationMs = if (resetDuration) null else filter.maxDurationMs,
            sortField = if (resetSort) SortField.CAPTURED else filter.sortField,
            sortDirection = if (resetSort) SortDirection.DESCENDING else filter.sortDirection,
        )
    }
}
