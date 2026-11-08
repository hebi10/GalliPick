package com.hebi.gallipick.domain

data class SoftFocusCandidate(
    val item: MediaItem,
    val score: Double,
    val comparisonGroupId: String?,
)

/**
 * Review-only recommendations. Access, content identity and provider epoch are checked
 * before the caller constructs SimilarPhoto instances; this policy never selects media.
 */
object SoftFocusCandidatePolicy {
    fun candidates(
        photos: List<SimilarPhoto>,
        scores: Map<String, Double?>,
        similarGroups: List<SimilarPhotoGroup>,
        protectedFolders: Set<String>,
    ): List<SoftFocusCandidate> {
        val comparisonByUri = buildMap {
            similarGroups.forEach { group ->
                if (group.photos.size >= 2) group.photos.forEach { putIfAbsent(it.item.uri, group.id) }
            }
        }
        return photos.asSequence()
            .distinctBy { it.item.uri }
            .filter { entry ->
                entry.item.kind == MediaKind.PHOTO && !entry.item.isTrashed &&
                    entry.item.width >= PhotoSharpness.MIN_ORIGINAL_SIDE &&
                    entry.item.height >= PhotoSharpness.MIN_ORIGINAL_SIDE &&
                    SimilarPhotoReviewPolicy.allowed(entry.item, protectedFolders) &&
                    PhotoSharpness.isReviewCandidate(scores[entry.item.uri])
            }
            .map { entry ->
                SoftFocusCandidate(
                    item = entry.item,
                    score = requireNotNull(scores[entry.item.uri]),
                    comparisonGroupId = comparisonByUri[entry.item.uri],
                )
            }
            .sortedWith(compareBy<SoftFocusCandidate> { it.score }
                .thenByDescending { it.item.capturedAt }.thenBy { it.item.uri })
            .toList()
    }
}
