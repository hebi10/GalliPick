package com.hebi.gallipick.domain

enum class MediaKind { PHOTO, VIDEO }
enum class DateField { CAPTURED, ADDED, MODIFIED }
enum class SortField { CAPTURED, ADDED, MODIFIED, SIZE, DURATION, NAME, PIXELS }
enum class SortDirection { ASCENDING, DESCENDING }
enum class Orientation { LANDSCAPE, PORTRAIT, SQUARE }

data class MediaItem(
    val uri: String,
    val id: Long,
    val volume: String,
    val displayName: String,
    val kind: MediaKind,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTakenMs: Long,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val generationModified: Long = 0,
    val isFavorite: Boolean = false,
    val isProtected: Boolean = false,
    val isTrashed: Boolean = false,
    val volumeVersion: String = "",
) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val capturedAt: Long get() = dateTakenMs.takeIf { it > 0 } ?: dateAddedMs
    val fingerprint: String get() = "$sizeBytes:$dateModifiedMs:$generationModified"
}

data class MediaFilter(
    val kind: MediaKind? = null,
    val query: String = "",
    val folder: String = "",
    val mimeType: String = "",
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
    val dateField: DateField = DateField.CAPTURED,
    val fromDateMs: Long? = null,
    val toDateMs: Long? = null,
    val minPixels: Long? = null,
    val orientation: Orientation? = null,
    val favoritesOnly: Boolean = false,
    val protectedOnly: Boolean = false,
    val sortField: SortField = SortField.CAPTURED,
    val sortDirection: SortDirection = SortDirection.DESCENDING,
)

data class FilterPreset(val id: String, val name: String, val filter: MediaFilter)
data class DuplicateGroup(val hash: String, val items: List<MediaItem>)
enum class ScanPhase { IDLE, QUEUED, RUNNING, PAUSED, COMPLETE, FAILED }
data class ScanState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
)
