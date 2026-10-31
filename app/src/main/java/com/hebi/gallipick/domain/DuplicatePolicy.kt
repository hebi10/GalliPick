package com.hebi.gallipick.domain

/** Recommendations for byte-identical media. Protected and favorite items always survive. */
object DuplicatePolicy {
    fun recommendedKeep(group: DuplicateGroup): MediaItem {
        require(group.items.isNotEmpty()) { "An empty duplicate group has no item to keep" }
        return group.items.minWithOrNull(
            compareByDescending<MediaItem> { it.isProtected }
                .thenByDescending { it.isFavorite }
                .thenByDescending { isCameraFolder(it.relativePath) }
                .thenBy { it.capturedAt.takeIf { timestamp -> timestamp > 0 } ?: Long.MAX_VALUE }
                .thenBy { it.uri },
        )!!
    }

    fun deletionCandidates(group: DuplicateGroup): List<MediaItem> {
        if (group.items.size < 2) return emptyList()
        val keepUri = recommendedKeep(group).uri
        // A repeated URI is one physical item, even if its metadata snapshots disagree.
        val protectedUris = group.items.asSequence()
            .filter { it.isFavorite || it.isProtected }
            .map { it.uri }
            .toSet()
        return group.items.asSequence()
            .filter { it.uri != keepUri && it.uri !in protectedUris }
            .distinctBy { it.uri }
            .sortedBy { it.uri }
            .toList()
    }

    fun reclaimableBytes(groups: List<DuplicateGroup>): Long {
        val countedUris = HashSet<String>()
        var total = 0L
        for (group in groups) {
            for (item in deletionCandidates(group)) {
                if (!countedUris.add(item.uri)) continue
                val bytes = item.sizeBytes.coerceAtLeast(0)
                // Corrupt metadata must not turn a large estimate into a negative number.
                if (bytes > Long.MAX_VALUE - total) return Long.MAX_VALUE
                total += bytes
            }
        }
        return total
    }

    private fun isCameraFolder(path: String): Boolean =
        path.split('/').any { it.equals("Camera", ignoreCase = true) }
}
