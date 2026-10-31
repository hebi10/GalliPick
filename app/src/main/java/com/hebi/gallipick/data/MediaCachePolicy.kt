package com.hebi.gallipick.data

import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem

/** Pure safety rules shared by catalog rendering and last-moment action validation. */
internal object MediaCachePolicy {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun normalizeFolder(path: String): String = path.trim('/').let {
        if (it.isEmpty()) "" else "$it/"
    }

    fun isProtected(uri: String, path: String, items: Set<String>, folders: List<String>): Boolean {
        val normalized = normalizeFolder(path)
        return uri in items || folders.any {
            normalized == it || (it.isNotEmpty() && normalized.startsWith(it))
        }
    }

    fun sameVersion(previous: MediaItem, current: MediaItem): Boolean =
        current.uri == previous.uri && current.fingerprint == previous.fingerprint &&
            current.id == previous.id && current.volume == previous.volume && current.kind == previous.kind

    fun duplicateGroups(items: List<MediaItem>, hashes: Map<Pair<String, String>, String?>): List<DuplicateGroup> {
        val validHashes = hashes.filterValues { it?.matches(sha256Pattern) == true }
        return items.asSequence().filter { !it.isTrashed && it.sizeBytes > 0 }.mapNotNull { item ->
            validHashes[item.uri to item.fingerprint]?.let { (item.sizeBytes to it) to item }
        }.groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (key, values) -> DuplicateGroup(key.second, values.sortedBy { it.capturedAt }) }
            .sortedByDescending { group -> group.items.first().sizeBytes.toDouble() * (group.items.size - 1) }
    }
}
