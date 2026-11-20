package com.hebi.gallipick.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.data.cloud.GalleryEntry
import com.hebi.gallipick.data.cloud.GalleryFileQuery
import com.hebi.gallipick.domain.FileGroupMode
import com.hebi.gallipick.domain.MediaDateGroups
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField

internal data class FileSection<T>(val title: String, val items: List<T>, val isDate: Boolean = false)

internal object FileSections {
    fun mode(filter: MediaFilter): FileGroupMode = when (filter.groupMode) {
        FileGroupMode.AUTO -> if (filter.groupByDate) FileGroupMode.DATE else when (filter.sortField) {
            SortField.CAPTURED, SortField.ADDED, SortField.MODIFIED -> FileGroupMode.DATE
            SortField.SIZE -> FileGroupMode.SIZE
            SortField.DURATION -> FileGroupMode.KIND
            else -> FileGroupMode.NONE
        }
        else -> filter.groupMode
    }

    fun local(items: List<MediaItem>, filter: MediaFilter): List<FileSection<MediaItem>> = when (mode(filter)) {
        FileGroupMode.NONE -> emptyList()
        FileGroupMode.DATE -> MediaDateGroups.group(items, filter.dateField,
            filter.sortDirection == SortDirection.ASCENDING).map { FileSection(it.date, it.items, true) }
        FileGroupMode.KIND -> group(items, { if (it.kind == MediaKind.PHOTO) "사진" else "동영상" },
            listOf("사진", "동영상"))
        FileGroupMode.FOLDER -> group(items, { it.relativePath.trim('/').ifBlank { "기본 폴더" } })
        FileGroupMode.SIZE -> groupBySize(items, { it.sizeBytes }, filter)
        FileGroupMode.AUTO -> emptyList()
    }

    fun unified(items: List<GalleryEntry>, filter: MediaFilter): List<FileSection<GalleryEntry>> = when (mode(filter)) {
        FileGroupMode.NONE -> emptyList()
        FileGroupMode.DATE -> GalleryFileQuery.groupByDate(items, filter.dateField,
            filter.sortDirection == SortDirection.ASCENDING).map { FileSection(it.date, it.items, true) }
        FileGroupMode.KIND -> group(items, { if (it.kind == MediaKind.PHOTO) "사진" else "동영상" },
            listOf("사진", "동영상"))
        FileGroupMode.FOLDER -> group(items, { it.sourceFolders.firstOrNull()?.trim('/')?.ifBlank { "기본 폴더" } ?: "폴더 없음" })
        FileGroupMode.SIZE -> groupBySize(items, { it.sizeBytes }, filter)
        FileGroupMode.AUTO -> emptyList()
    }

    private fun <T> group(items: List<T>, label: (T) -> String, preferred: List<String> = emptyList()): List<FileSection<T>> {
        val sections = items.groupBy(label)
        val keys = if (preferred.isNotEmpty()) preferred.filter { it in sections } +
            sections.keys.filterNot { it in preferred }.sorted() else sections.keys.sorted()
        return keys.map { FileSection(it, sections.getValue(it)) }
    }

    private fun <T> groupBySize(items: List<T>, bytes: (T) -> Long, filter: MediaFilter): List<FileSection<T>> {
        val names = listOf("10MB 미만", "10~100MB", "100MB~1GB", "1GB 이상")
        val sections = items.groupBy {
            when {
                bytes(it) < 10L * 1024 * 1024 -> 0
                bytes(it) < 100L * 1024 * 1024 -> 1
                bytes(it) < 1024L * 1024 * 1024 -> 2
                else -> 3
            }
        }
        val order = if (filter.sortDirection == SortDirection.ASCENDING) names.indices.toList()
            else names.indices.reversed()
        return order.filter { it in sections }.map { FileSection(names[it], sections.getValue(it)) }
    }
}

internal fun resolvedFileGridColumns(
    requested: Int?, availableWidth: Dp, fontScale: Float, tablet: Boolean,
): Int {
    if (requested == null) {
        if (tablet) return tabletMediaGridColumns(availableWidth, fontScale)
        val tile = if (fontScale >= 1.5f) 132f else 108f
        return ((availableWidth.value + 6f) / (tile + 6f)).toInt().coerceAtLeast(1)
    }
    // Keep a full 48 dp detail target beside the 48 dp selection target.
    val minimumTile = 96f * fontScale.coerceIn(1f, 1.5f)
    val safe = ((availableWidth.value + 6f) / (minimumTile + 6f)).toInt().coerceAtLeast(1)
    return requested.coerceIn(3, 6).coerceAtMost(safe)
}

internal fun fileGridCells(requested: Int?, availableWidth: Dp, fontScale: Float, tablet: Boolean): GridCells {
    if (requested == null && !tablet) {
        return GridCells.Adaptive(if (fontScale >= 1.5f) 132.dp else 108.dp)
    }
    return GridCells.Fixed(resolvedFileGridColumns(requested, availableWidth, fontScale, tablet))
}
