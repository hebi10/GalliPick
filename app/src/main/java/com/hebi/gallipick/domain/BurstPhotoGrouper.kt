package com.hebi.gallipick.domain

/**
 * Creates review candidates only, not proof of byte equality.
 * Consecutive capture time AND visual similarity are required; device-add time is never used.
 */
object BurstPhotoGrouper {
    const val MAX_CAPTURE_GAP_MS = 5_000L
    const val MAX_GROUP_SPAN_MS = 30_000L
    const val MAX_DISTANCE = 6
    const val MAX_PHOTOS_PER_GROUP = 64
    private const val MAX_ASPECT_DIFFERENCE = 0.12

    fun groups(photos: List<SimilarPhoto>): List<SimilarPhotoGroup> {
        val ordered = photos.asSequence()
            .filter { entry ->
                val item = entry.item
                item.kind == MediaKind.PHOTO && !item.isTrashed &&
                    item.dateTakenMs > 0L && item.width > 0 && item.height > 0 &&
                    entry.phash.length == 16 && entry.phash.all { it in "0123456789abcdef" }
            }
            .distinctBy { it.item.uri }
            .sortedWith(compareBy<SimilarPhoto> { it.item.dateTakenMs }.thenBy { it.item.uri })
            .toList()

        val groups = mutableListOf<MutableList<SimilarPhoto>>()
        val active = mutableListOf<MutableList<SimilarPhoto>>()

        for (entry in ordered) {
            val captured = entry.item.dateTakenMs
            active.removeAll { members ->
                captured - members.last().item.dateTakenMs > MAX_CAPTURE_GAP_MS ||
                    captured - members.first().item.dateTakenMs > MAX_GROUP_SPAN_MS ||
                    members.size >= MAX_PHOTOS_PER_GROUP
            }
            val closest = active.mapNotNull { members ->
                if (members.any { !similarAspect(entry.item, it.item) }) return@mapNotNull null
                val distances = members.map { PerceptualHash.distance(entry.phash, it.phash) }
                if (distances.any { it > MAX_DISTANCE }) null
                else members to (distances.maxOrNull() ?: 0)
            }.minWithOrNull(compareBy<Pair<MutableList<SimilarPhoto>, Int>> { it.second }
                .thenBy { it.first.first().item.dateTakenMs }
                .thenBy { it.first.first().item.uri })?.first

            if (closest != null) {
                closest += entry
            } else {
                val group = mutableListOf(entry)
                groups += group
                active += group
            }
        }

        return groups.filter { it.size >= 2 }.map { members ->
            SimilarPhotoGroup(
                id = "burst:" + members.first().item.uri + "#" + members.first().phash,
                photos = members.toList(),
            )
        }.sortedWith(compareByDescending<SimilarPhotoGroup> { it.photos.first().item.dateTakenMs }
            .thenBy { it.id })
    }

    private fun similarAspect(first: MediaItem, second: MediaItem): Boolean {
        val a = first.width.toDouble() / first.height
        val b = second.width.toDouble() / second.height
        return kotlin.math.abs(a / b - 1.0) <= MAX_ASPECT_DIFFERENCE
    }
}
