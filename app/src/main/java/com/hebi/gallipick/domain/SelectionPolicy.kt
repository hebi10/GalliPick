package com.hebi.gallipick.domain

object SelectionPolicy {
    const val MAX_SELECTION_SIZE = 500

    fun candidates(items: List<MediaItem>): List<MediaItem> = items.asSequence()
        .filterNot { it.isProtected || it.isFavorite || it.isTrashed }
        .distinctBy { it.uri }.take(MAX_SELECTION_SIZE).toList()

    fun retain(selected: Collection<MediaItem>, current: List<MediaItem>): List<MediaItem> {
        val versions = selected.associate { it.uri to (it.fingerprint to it.volumeVersion) }
        return candidates(current.filter { versions[it.uri] == (it.fingerprint to it.volumeVersion) })
    }
}
