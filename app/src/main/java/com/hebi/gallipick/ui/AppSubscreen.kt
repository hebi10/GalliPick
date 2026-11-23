package com.hebi.gallipick.ui

internal enum class PhotoReviewMode { SIMILAR, BURST, SOFT_FOCUS }

internal enum class PhotoCompareSource { RESULTS, INBOX }

internal sealed interface AppSubscreen {
    data object Main : AppSubscreen
    data class Trash(val fromHistory: Boolean = false) : AppSubscreen
    data object Duplicates : AppSubscreen
    data object FolderStorage : AppSubscreen
    data object CleanupHistory : AppSubscreen
    data object PhotoAnalysis : AppSubscreen
    data object ReviewInbox : AppSubscreen
    data class PhotoResults(val mode: PhotoReviewMode) : AppSubscreen
    data class PhotoCompare(
        val mode: PhotoReviewMode,
        val groupId: String,
        val source: PhotoCompareSource,
    ) : AppSubscreen
}

internal fun AppSubscreen.parent(): AppSubscreen = when (this) {
    is AppSubscreen.Trash -> if (fromHistory) AppSubscreen.CleanupHistory else AppSubscreen.Main
    is AppSubscreen.PhotoResults -> AppSubscreen.PhotoAnalysis
    is AppSubscreen.PhotoCompare -> when (source) {
        PhotoCompareSource.RESULTS -> AppSubscreen.PhotoResults(mode)
        PhotoCompareSource.INBOX -> AppSubscreen.ReviewInbox
    }
    else -> AppSubscreen.Main
}

internal fun AppSubscreen.save(): List<String> = when (this) {
    AppSubscreen.Main -> listOf("main")
    is AppSubscreen.Trash -> listOf(if (fromHistory) "history-trash" else "trash")
    AppSubscreen.Duplicates -> listOf("duplicates")
    AppSubscreen.FolderStorage -> listOf("folder-storage")
    AppSubscreen.CleanupHistory -> listOf("cleanup-history")
    AppSubscreen.PhotoAnalysis -> listOf("photo-analysis")
    AppSubscreen.ReviewInbox -> listOf("review-inbox")
    is AppSubscreen.PhotoResults -> listOf("photo-results", mode.name)
    is AppSubscreen.PhotoCompare -> listOf("photo-compare", mode.name, groupId, source.name)
}

internal fun restoreAppSubscreen(saved: List<String>): AppSubscreen {
    val mode = PhotoReviewMode.entries.firstOrNull { it.name == saved.getOrNull(1) }
    return when (saved.firstOrNull()) {
        "trash" -> AppSubscreen.Trash()
        "history-trash" -> AppSubscreen.Trash(fromHistory = true)
        "duplicates" -> AppSubscreen.Duplicates
        "folder-storage" -> AppSubscreen.FolderStorage
        "cleanup-history" -> AppSubscreen.CleanupHistory
        "photo-analysis" -> AppSubscreen.PhotoAnalysis
        "review-inbox" -> AppSubscreen.ReviewInbox
        "photo-results" -> if (mode == null) AppSubscreen.Main else AppSubscreen.PhotoResults(mode)
        "photo-compare" -> {
            val groupId = saved.getOrNull(2)
            val source = PhotoCompareSource.entries.firstOrNull { it.name == saved.getOrNull(3) }
            if (mode == null || groupId == null || source == null) AppSubscreen.Main
            else AppSubscreen.PhotoCompare(mode, groupId, source)
        }
        else -> AppSubscreen.Main
    }
}
