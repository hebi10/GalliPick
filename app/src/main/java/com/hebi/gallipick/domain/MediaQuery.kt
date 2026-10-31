package com.hebi.gallipick.domain

/** Applies every active condition before sorting. MediaStore timestamps are milliseconds. */
object MediaQuery {
    fun apply(items: List<MediaItem>, filter: MediaFilter): List<MediaItem> {
        val query = filter.query.trim()
        val hasDurationBound = filter.minDurationMs != null || filter.maxDurationMs != null
        val matching = items.filter { item ->
            val date = when (filter.dateField) {
                DateField.CAPTURED -> item.capturedAt
                DateField.ADDED -> item.dateAddedMs
                DateField.MODIFIED -> item.dateModifiedMs
            }
            (filter.kind == null || item.kind == filter.kind) &&
                (query.isEmpty() || item.displayName.contains(query, ignoreCase = true) || item.relativePath.contains(query, ignoreCase = true)) &&
                (filter.folder.isEmpty() || item.relativePath == filter.folder) &&
                (filter.mimeType.isEmpty() || item.mimeType.equals(filter.mimeType, ignoreCase = true)) &&
                (filter.minSizeBytes == null || item.sizeBytes >= filter.minSizeBytes) &&
                (filter.maxSizeBytes == null || item.sizeBytes <= filter.maxSizeBytes) &&
                (!hasDurationBound || item.kind == MediaKind.VIDEO) &&
                (filter.minDurationMs == null || item.durationMs >= filter.minDurationMs) &&
                (filter.maxDurationMs == null || item.durationMs <= filter.maxDurationMs) &&
                (filter.fromDateMs == null || date >= filter.fromDateMs) &&
                (filter.toDateMs == null || date <= filter.toDateMs) &&
                (filter.minPixels == null || item.pixels >= filter.minPixels) &&
                matchesOrientation(item, filter.orientation) &&
                (!filter.favoritesOnly || item.isFavorite) &&
                (!filter.protectedOnly || item.isProtected)
        }
        return matching.sortedWith { first, second ->
            val primary = compareField(first, second, filter.sortField)
            val directed = if (filter.sortDirection == SortDirection.ASCENDING) primary else -primary
            if (directed != 0) directed else first.uri.compareTo(second.uri)
        }
    }

    private fun matchesOrientation(item: MediaItem, orientation: Orientation?): Boolean {
        if (orientation == null) return true
        if (item.width <= 0 || item.height <= 0) return false
        return when (orientation) {
            Orientation.LANDSCAPE -> item.width > item.height
            Orientation.PORTRAIT -> item.width < item.height
            Orientation.SQUARE -> item.width == item.height
        }
    }

    private fun compareField(first: MediaItem, second: MediaItem, field: SortField): Int = when (field) {
        SortField.CAPTURED -> first.capturedAt.compareTo(second.capturedAt)
        SortField.ADDED -> first.dateAddedMs.compareTo(second.dateAddedMs)
        SortField.MODIFIED -> first.dateModifiedMs.compareTo(second.dateModifiedMs)
        SortField.SIZE -> first.sizeBytes.compareTo(second.sizeBytes)
        SortField.DURATION -> first.durationMs.compareTo(second.durationMs)
        SortField.NAME -> first.displayName.compareTo(second.displayName, ignoreCase = true)
        SortField.PIXELS -> first.pixels.compareTo(second.pixels)
    }
}
