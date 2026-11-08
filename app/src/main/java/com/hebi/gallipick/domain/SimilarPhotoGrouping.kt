package com.hebi.gallipick.domain

/** Candidate photos only. Perceptual similarity never establishes byte identity or deletion eligibility. */
data class SimilarPhoto(val item: MediaItem, val phash: String)

data class SimilarPhotoGroup(val id: String, val photos: List<SimilarPhoto>) {
    val items: List<MediaItem> get() = photos.map { it.item }
    val totalBytes: Long get() {
        var result = 0L
        photos.forEach { photo ->
            val bytes = photo.item.sizeBytes.coerceAtLeast(0L)
            result = if (bytes > Long.MAX_VALUE - result) Long.MAX_VALUE else result + bytes
        }
        return result
    }
}

/** Fixed 8-band index: distance <= 6 guarantees that at least one 8-bit band matches. */
object SimilarPhotoGrouper {
    const val MAX_DISTANCE = 6
    private const val BANDS = 8

    fun groups(items: List<SimilarPhoto>, maxDistance: Int = MAX_DISTANCE): List<SimilarPhotoGroup> {
        require(maxDistance in 0..7)
        val valid = items.asSequence()
            .filter { it.item.kind == MediaKind.PHOTO && !it.item.isTrashed && it.item.width > 0 && it.item.height > 0 }
            .filter { it.phash.length == 16 && it.phash.all { c -> c in "0123456789abcdef" } }
            .distinctBy { it.item.uri }.sortedBy { it.item.uri }.toList()
        val clusters = mutableListOf<MutableList<SimilarPhoto>>()
        val index = Array(BANDS) { mutableMapOf<Int, MutableList<Int>>() }
        for (photo in valid) {
            val candidates = linkedSetOf<Int>()
            for (band in 0 until BANDS) {
                val key = photo.phash.substring(band * 2, band * 2 + 2).toInt(16)
                index[band][key]?.let(candidates::addAll)
            }
            val target = candidates.asSequence()
                .mapNotNull { id ->
                    val cluster = clusters[id]
                    val distances = cluster.map { existing ->
                        if (!similarAspect(photo.item, existing.item)) return@mapNotNull null
                        PerceptualHash.distance(photo.phash, existing.phash)
                    }
                    if (distances.size != cluster.size || distances.any { it > maxDistance }) null
                    else id to distances.maxOrNull().orEmptyDistance()
                }.minWithOrNull(compareBy<Pair<Int,Int>> { it.second }.thenBy { it.first })?.first
            if (target != null) clusters[target] += photo
            else {
                val id = clusters.size
                clusters += mutableListOf(photo)
                for (band in 0 until BANDS) {
                    val key = photo.phash.substring(band * 2, band * 2 + 2).toInt(16)
                    index[band].getOrPut(key) { mutableListOf() } += id
                }
            }
        }
        return clusters.filter { it.size >= 2 }.map { members ->
            SimilarPhotoGroup(members.first().item.uri + "#" + members.first().phash, members.toList())
        }.sortedWith(compareByDescending<SimilarPhotoGroup> { it.photos.size }.thenBy { it.id })
    }

    private fun Int?.orEmptyDistance() = this ?: 0

    private fun similarAspect(first: MediaItem, second: MediaItem): Boolean {
        val firstRatio = first.width.toDouble() / first.height
        val secondRatio = second.width.toDouble() / second.height
        return kotlin.math.abs(firstRatio / secondRatio - 1.0) <= 0.12
    }
}

object SimilarPhotoReviewPolicy {
    fun allowed(item: MediaItem, protectedFolders: Set<String>): Boolean {
        val folder = item.relativePath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return !item.isTrashed && !item.isFavorite && !item.isProtected &&
            protectedFolders.none {
                val protected = it.trim('/').let { path -> if (path.isEmpty()) "" else "$path/" }
                protected == folder || protected.isNotEmpty() && folder.startsWith(protected)
            }
    }

    fun maySelect(group: SimilarPhotoGroup, selected: Set<String>, candidateUri: String, protectedFolders: Set<String>): Boolean {
        val candidate = group.photos.firstOrNull { it.item.uri == candidateUri }?.item ?: return false
        if (!allowed(candidate, protectedFolders)) return false
        val after = selected + candidateUri
        return group.photos.any { it.item.uri !in after }
    }

    fun retained(group: SimilarPhotoGroup, selected: Set<String>): List<MediaItem> =
        group.photos.map { it.item }.filterNot { it.uri in selected }
}
