package com.hebi.gallipick.data

import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem

/** Pure safety rules shared by catalog rendering and last-moment action validation. */
internal object MediaCachePolicy {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    /** Missing epochs include digests written before volume version tracking existed. */
    fun invalidatedVolumes(previous: Map<String, String>, current: Map<String, String>): Set<String> =
        current.filter { (volume, version) -> previous[volume] != version }.keys

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
            current.id == previous.id && current.volume == previous.volume && current.kind == previous.kind &&
            current.volumeVersion == previous.volumeVersion

    /** Provider resets can reuse both an ID and its apparent generation/fingerprint. */
    suspend fun freshUnchanged(
        items: List<MediaItem>,
        cachedVersions: Map<String, String>,
        currentVersion: (String) -> String,
        currentItem: suspend (String) -> MediaItem?,
    ): List<MediaItem> {
        val originals = items.distinctBy { it.uri }
        val eligibleVolumes = originals.mapTo(hashSetOf()) { it.volume }.filterTo(hashSetOf()) {
            cachedVersions[it]?.let { cached -> cached == currentVersion(it) } == true
        }
        val fresh = originals.filter { it.volume in eligibleVolumes }.mapNotNull { previous ->
            currentItem(previous.uri)?.takeIf { sameVersion(previous, it) }
        }
        val stableVolumes = eligibleVolumes.filterTo(hashSetOf()) { cachedVersions[it] == currentVersion(it) }
        return fresh.filter { it.volume in stableVolumes }
    }

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
