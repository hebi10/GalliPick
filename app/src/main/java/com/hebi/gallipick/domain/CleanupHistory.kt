package com.hebi.gallipick.domain

enum class CleanupHistoryAction { MOVE_TO_TRASH, RESTORE }

data class CleanupHistoryEntry(
    val id: String,
    val occurredAtMs: Long,
    val action: CleanupHistoryAction,
    val requestedCount: Int,
    val confirmedCount: Int,
    val confirmedBytes: Long,
)

object CleanupHistoryPolicy {
    /** Only a verified state transition can become history. Never infer success from system approval. */
    fun confirmed(
        operationId: String,
        occurredAtMs: Long,
        requestedCount: Int,
        restore: Boolean,
        outcome: TrashOutcome,
    ): CleanupHistoryEntry? {
        if (operationId.isBlank() || occurredAtMs <= 0L || requestedCount <= 0 ||
            outcome.completed <= 0 || outcome.completed > requestedCount ||
            outcome.unchanged < 0 || outcome.unknown < 0 ||
            outcome.completed.toLong() + outcome.unchanged + outcome.unknown != requestedCount.toLong() ||
            outcome.confirmedCompletedBytes < 0L
        ) return null
        return CleanupHistoryEntry(
            id = operationId, occurredAtMs = occurredAtMs,
            action = if (restore) CleanupHistoryAction.RESTORE else CleanupHistoryAction.MOVE_TO_TRASH,
            requestedCount = requestedCount, confirmedCount = outcome.completed,
            confirmedBytes = outcome.confirmedCompletedBytes,
        )
    }
}
