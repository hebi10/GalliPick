package com.hebi.gallipick.domain

data class TrashIdentity(val uri: String, val id: Long, val volume: String, val kind: MediaKind, val sizeBytes: Long, val volumeVersion: String) {
    companion object {
        fun from(item: MediaItem) = TrashIdentity(item.uri, item.id, item.volume, item.kind, item.sizeBytes, item.volumeVersion)
    }
}

data class TrashOutcome(val completed: Int, val unchanged: Int, val unknown: Int, val confirmedCompletedBytes: Long = 0L)

object TrashOutcomePolicy {
    fun classify(requested: List<TrashIdentity>, current: Map<String, MediaItem?>, restore: Boolean): TrashOutcome {
        var completed = 0
        var confirmedCompletedBytes = 0L
        var unchanged = 0
        var unknown = 0
        for (before in requested.distinctBy { it.uri }) {
            val now = current[before.uri]
            if (now == null || now.uri != before.uri || now.id != before.id || now.volume != before.volume || now.kind != before.kind ||
                now.sizeBytes != before.sizeBytes || before.volumeVersion.isBlank() || now.volumeVersion != before.volumeVersion) unknown++
            else if (now.isTrashed == !restore) {
                completed++
                val size = before.sizeBytes.coerceAtLeast(0L)
                confirmedCompletedBytes = if (size > Long.MAX_VALUE - confirmedCompletedBytes) Long.MAX_VALUE else confirmedCompletedBytes + size
            }
            else unchanged++
        }
        return TrashOutcome(completed, unchanged, unknown, confirmedCompletedBytes)
    }
}
