package com.hebi.gallipick.domain

data class FolderStorageEntry(
    val path: String,
    val count: Int,
    val sizeBytes: Long,
) {
    val displayName: String get() = path.trim('/').ifBlank { "폴더 정보 없음" }
    val canBrowse: Boolean get() = path.isNotBlank()
}

data class FolderStorageSummary(
    val totalBytes: Long,
    val totalCount: Int,
    val folders: List<FolderStorageEntry>,
)

object FolderStoragePolicy {
    fun summarize(items: List<MediaItem>): FolderStorageSummary {
        val visible = items.asSequence()
            .filterNot { it.isTrashed }
            .distinctBy { it.uri }
            .toList()
        val folders = visible.groupBy { it.relativePath }.map { (path, contents) ->
            FolderStorageEntry(path, contents.size, sumSizes(contents))
        }.sortedWith(compareByDescending<FolderStorageEntry> { it.sizeBytes }.thenBy { it.path })
        return FolderStorageSummary(sumSizes(visible), visible.size, folders)
    }

    fun fraction(entry: FolderStorageEntry, totalBytes: Long): Float =
        if (totalBytes <= 0L) 0f
        else (entry.sizeBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    private fun sumSizes(items: List<MediaItem>): Long {
        var total = 0L
        items.forEach { item ->
            val bytes = item.sizeBytes.coerceAtLeast(0L)
            total = if (bytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + bytes
        }
        return total
    }
}
