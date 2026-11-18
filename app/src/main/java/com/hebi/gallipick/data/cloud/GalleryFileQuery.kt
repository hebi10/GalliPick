package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.MediaQuery
import com.hebi.gallipick.domain.Orientation
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class GallerySourceFilter { ALL, DEVICE, CLOUD }

internal data class GalleryDateGroup(val date: String, val items: List<GalleryEntry>)

/**
 * A read-only query. MediaStore-only actions continue using MediaQuery and never
 * receive CloudOriginal records or synthetic device URIs.
 */
internal object GalleryFileQuery {
    fun apply(entries: List<GalleryEntry>, filter: MediaFilter, source: GallerySourceFilter): List<GalleryEntry> {
        val selectedFolder = normalizedGalleryFolder(filter.folder)
        val localFilter = if (selectedFolder.isNotEmpty()) filter.copy(folder = "") else filter
        val localMatches = MediaQuery.apply(entries.mapNotNull { it.device }, localFilter)
            .filter { selectedFolder.isEmpty() ||
                normalizedGalleryFolder(it.relativePath) == selectedFolder }
            .mapTo(hashSetOf()) { it.uri }
        val result = entries.filter { entry ->
            val deviceMatch = entry.device?.uri in localMatches
            val cloudMatch = entry.cloud?.let { matchesCloud(it, filter) } == true
            when (source) {
                GallerySourceFilter.ALL -> deviceMatch || cloudMatch
                GallerySourceFilter.DEVICE -> entry.device != null && deviceMatch
                GallerySourceFilter.CLOUD -> entry.cloud != null && cloudMatch
            }
        }
        return result.sortedWith { first, second ->
            val primary = compareEntries(first, second, filter.sortField, filter.sortDirection)
            if (primary != 0) primary else stableKey(first).compareTo(stableKey(second))
        }
    }

    fun groupByDate(entries: List<GalleryEntry>, field: DateField, ascending: Boolean): List<GalleryDateGroup> {
        val formatter = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        val dates = entries.groupBy { entry ->
            dateOf(entry, field)?.let { formatter.format(Date(it)) } ?: "정보 없음"
        }
        val known = dates.keys.filter { it != "정보 없음" }.sorted()
        val ordered = if (ascending) known else known.reversed()
        return (ordered + listOfNotNull("정보 없음".takeIf { it in dates }))
            .map { GalleryDateGroup(it, dates.getValue(it)) }
    }

    fun dateOf(entry: GalleryEntry, field: DateField): Long? {
        val local = entry.device
        if (local != null) return MediaQuery.dateOf(local, field).takeIf { it > 0 }
        val original = entry.cloud ?: return null
        return when (field) {
            DateField.CAPTURED -> original.capturedAtMs
            DateField.ADDED -> original.verifiedAtMs.takeIf { it > 0 }
            DateField.MODIFIED -> null
        }
    }

    private fun normalizedGalleryFolder(path: String): String =
        normalizeCloudFolderPath(path)?.trimEnd('/') ?: path.trimEnd('/')

    private fun matchesCloud(original: CloudOriginal, filter: MediaFilter): Boolean {
        val kind = if (original.mimeType.startsWith("video/")) MediaKind.VIDEO else MediaKind.PHOTO
        val query = filter.query.trim()
        val date = when (filter.dateField) {
            DateField.CAPTURED -> original.capturedAtMs
            DateField.ADDED -> original.verifiedAtMs
            DateField.MODIFIED -> null
        }
        if (filter.favoritesOnly || filter.protectedOnly || filter.downloadsOnly) return false
        val folder = normalizedGalleryFolder(filter.folder)
        if (filter.kind != null && filter.kind != kind) return false
        if (query.isNotEmpty() && !original.displayName.contains(query, ignoreCase = true) &&
            original.sourceFolders.none { it.contains(query, ignoreCase = true) }
        ) return false
        if (folder.isNotEmpty() &&
            original.sourceFolders.none { normalizedGalleryFolder(it) == folder }) return false
        if (filter.mimeType.isNotEmpty() && !original.mimeType.equals(filter.mimeType, ignoreCase = true)) return false
        if (filter.minSizeBytes?.let { original.sizeBytes < it } == true ||
            filter.maxSizeBytes?.let { original.sizeBytes > it } == true
        ) return false
        if (filter.minDurationMs != null || filter.maxDurationMs != null) {
            val duration = original.durationMs ?: return false
            if (kind != MediaKind.VIDEO || filter.minDurationMs?.let { duration < it } == true ||
                filter.maxDurationMs?.let { duration > it } == true
            ) return false
        }
        if (filter.fromDateMs != null || filter.toDateMs != null) {
            if (date == null || date <= 0 ||
                filter.fromDateMs?.let { date < it } == true ||
                filter.toDateMs?.let { date > it } == true
            ) return false
        }
        if (filter.minPixels != null || filter.orientation != null) {
            val width = original.width ?: return false
            val height = original.height ?: return false
            if (filter.minPixels?.let { width.toLong() * height < it } == true) return false
            if (filter.orientation != null && when (filter.orientation) {
                    Orientation.LANDSCAPE -> width <= height
                    Orientation.PORTRAIT -> width >= height
                    Orientation.SQUARE -> width != height
                }
            ) return false
        }
        return true
    }

    private fun compareEntries(a: GalleryEntry, b: GalleryEntry, sort: SortField, direction: SortDirection): Int {
        if (sort == SortField.NAME) {
            val compared = a.displayName.compareTo(b.displayName, ignoreCase = true)
            return if (direction == SortDirection.ASCENDING) compared else -compared
        }
        val av = sortNumber(a, sort)
        val bv = sortNumber(b, sort)
        if (av == null && bv == null) return 0
        if (av == null) return 1
        if (bv == null) return -1
        val compared = av.compareTo(bv)
        return if (direction == SortDirection.ASCENDING) compared else -compared
    }

    private fun sortNumber(entry: GalleryEntry, sort: SortField): Long? = when (sort) {
        SortField.CAPTURED -> dateOf(entry, DateField.CAPTURED)
        SortField.ADDED -> dateOf(entry, DateField.ADDED)
        SortField.MODIFIED -> dateOf(entry, DateField.MODIFIED)
        SortField.SIZE -> entry.sizeBytes
        SortField.DURATION -> entry.device?.durationMs?.takeIf { it > 0 } ?: entry.cloud?.durationMs
        SortField.PIXELS -> entry.device?.pixels?.takeIf { it > 0 }
            ?: entry.cloud?.let { cloud ->
                val width = cloud.width
                val height = cloud.height
                if (width != null && height != null) width.toLong() * height else null
            }
        SortField.NAME -> null
    }

    private fun stableKey(entry: GalleryEntry): String = when (val key = entry.key) {
        is GalleryEntryKey.Device -> "device:" + key.uri
        is GalleryEntryKey.Cloud -> "cloud:" + key.accountUid + ":" + key.uploadId
    }
}
