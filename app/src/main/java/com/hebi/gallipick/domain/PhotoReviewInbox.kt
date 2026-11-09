package com.hebi.gallipick.domain

enum class PhotoReviewDecision { KEEP, CANDIDATE, DEFER }

data class PhotoReviewEntry(
    val groupId: String,
    val uri: String,
    val fingerprint: String,
    val volumeVersion: String,
    val decision: PhotoReviewDecision,
    val updatedAtMs: Long,
)

data class PhotoReviewGroupProgress(
    val group: SimilarPhotoGroup,
    val decidedCount: Int,
    val deferredCount: Int,
    val candidateCount: Int,
    val started: Boolean,
) {
    val completed: Boolean get() = decidedCount == group.photos.size
    val remainingCount: Int get() = group.photos.size - decidedCount
}

/**
 * Review notes are not trash selections. Only the current, accessible photo identity
 * can be displayed or converted into an explicit cleanup selection.
 */
object PhotoReviewInboxPolicy {
    fun valid(
        groups: List<SimilarPhotoGroup>,
        entries: List<PhotoReviewEntry>,
        protectedFolders: Set<String>,
    ): List<PhotoReviewEntry> {
        val byGroup = groups.associateBy { it.id }
        return entries.filter { entry ->
            val photo = byGroup[entry.groupId]?.photos?.firstOrNull { it.item.uri == entry.uri }?.item
                ?: return@filter false
            entry.fingerprint == photo.fingerprint &&
                entry.volumeVersion == photo.volumeVersion &&
                !photo.isTrashed &&
                (entry.decision != PhotoReviewDecision.CANDIDATE ||
                    SimilarPhotoReviewPolicy.allowed(photo, protectedFolders))
        }.distinctBy { it.groupId to it.uri }
    }

    fun summaries(
        groups: List<SimilarPhotoGroup>,
        entries: List<PhotoReviewEntry>,
        protectedFolders: Set<String>,
    ): List<PhotoReviewGroupProgress> {
        val valid = valid(groups, entries, protectedFolders).groupBy { it.groupId }
        return groups.map { group ->
            val decisions = valid[group.id].orEmpty()
            PhotoReviewGroupProgress(
                group = group,
                decidedCount = decisions.count { it.decision != PhotoReviewDecision.DEFER },
                deferredCount = decisions.count { it.decision == PhotoReviewDecision.DEFER },
                candidateCount = decisions.count { it.decision == PhotoReviewDecision.CANDIDATE },
                started = decisions.isNotEmpty(),
            )
        }
    }

    fun maySet(
        group: SimilarPhotoGroup,
        entries: List<PhotoReviewEntry>,
        uri: String,
        decision: PhotoReviewDecision,
        protectedFolders: Set<String>,
    ): Boolean {
        val item = group.photos.firstOrNull { it.item.uri == uri }?.item ?: return false
        if (decision != PhotoReviewDecision.CANDIDATE) return !item.isTrashed
        if (!SimilarPhotoReviewPolicy.allowed(item, protectedFolders)) return false
        val existing = valid(listOf(group), entries, protectedFolders)
            .filter { it.groupId == group.id && it.decision == PhotoReviewDecision.CANDIDATE }
            .mapTo(mutableSetOf()) { it.uri }
        existing.add(uri)
        return group.photos.any { it.item.uri !in existing }
    }

    fun pending(groups: List<SimilarPhotoGroup>, entries: List<PhotoReviewEntry>,
                protectedFolders: Set<String>): Boolean =
        summaries(groups, entries, protectedFolders).any { it.started && !it.completed }
}
