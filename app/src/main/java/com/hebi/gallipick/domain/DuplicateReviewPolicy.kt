package com.hebi.gallipick.domain

object DuplicateReviewPolicy {
    fun liveGroups(groups: List<DuplicateGroup>, items: List<MediaItem>): List<DuplicateGroup> {
        val current = items.associateBy { it.uri }
        return groups.mapNotNull { group ->
            val copies = group.items.mapNotNull { cached ->
                current[cached.uri]?.takeIf { live ->
                    !live.isTrashed && live.id == cached.id && live.volume == cached.volume && live.kind == cached.kind &&
                        live.fingerprint == cached.fingerprint && live.volumeVersion == cached.volumeVersion
                }
            }.distinctBy { it.uri }
            if (copies.size >= 2) group.copy(items = copies) else null
        }
    }

    fun candidates(groups: List<DuplicateGroup>, items: List<MediaItem>, preferredKeepers: Map<String, String>, protectedFolders: Set<String>): List<MediaItem> =
        liveGroups(groups, items).flatMap { group ->
            DuplicatePolicy.deletionCandidates(group, preferredKeepers[group.hash])
        }.filterNot { item ->
            val path = normalized(item.relativePath)
            protectedFolders.any { folder ->
                val protected = normalized(folder)
                path == protected || protected.isNotEmpty() && path.startsWith(protected)
            }
        }.distinctBy { it.uri }

    fun afterKeeperChange(selected: Collection<MediaItem>, group: DuplicateGroup): List<MediaItem> {
        val affected = group.items.mapTo(hashSetOf()) { it.uri }
        return selected.filterNot { it.uri in affected }
    }

    fun selectGroup(selected: Collection<MediaItem>, eligible: List<MediaItem>, group: DuplicateGroup): List<MediaItem> {
        val groupUris = group.items.mapTo(hashSetOf()) { it.uri }
        val retained = SelectionPolicy.retain(selected, eligible)
        return SelectionPolicy.candidates(retained + eligible.filter { it.uri in groupUris })
    }

    private fun normalized(path: String): String = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
}
