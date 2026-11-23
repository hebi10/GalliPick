package com.hebi.gallipick

import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.SelectionPolicy
import com.hebi.gallipick.domain.TrashIdentity

internal data class TrashApprovalRequest(
    val operationId: String,
    val restore: Boolean,
    val identities: List<TrashIdentity>,
) {
    val sizeBytes: Long
        get() = trashRequestBytes(identities.map { it.sizeBytes })
}

internal sealed interface TrashApproval {
    val request: TrashApprovalRequest

    data class Awaiting(override val request: TrashApprovalRequest) : TrashApproval
    data class Verifying(override val request: TrashApprovalRequest) : TrashApproval
}

internal sealed interface TrashApprovalDecision {
    data object Ignore : TrashApprovalDecision
    data class Cancel(val request: TrashApprovalRequest) : TrashApprovalDecision
    data class Verify(val approval: TrashApproval.Verifying) : TrashApprovalDecision
}

internal fun TrashApproval?.decide(approved: Boolean): TrashApprovalDecision = when {
    this !is TrashApproval.Awaiting -> TrashApprovalDecision.Ignore
    approved -> TrashApprovalDecision.Verify(TrashApproval.Verifying(request))
    else -> TrashApprovalDecision.Cancel(request)
}

internal fun TrashApproval.save(): ArrayList<String> = arrayListOf(
    if (this is TrashApproval.Verifying) "verifying" else "awaiting",
    request.operationId,
    request.restore.toString(),
).apply {
    for (item in request.identities) {
        addAll(listOf(item.uri, item.id.toString(), item.volume, item.kind.name, item.sizeBytes.toString(), item.volumeVersion))
    }
}

internal fun restoreTrashApproval(saved: List<String>): TrashApproval? {
    val operationId = saved.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val restore = saved.getOrNull(2)?.toBooleanStrictOrNull() ?: return null
    val fields = saved.drop(3)
    if (fields.size % 6 != 0 || fields.size / 6 !in 1..SelectionPolicy.MAX_SELECTION_SIZE) return null
    val identities = fields.chunked(6).map { item ->
        TrashIdentity(
            item[0], item[1].toLongOrNull() ?: return null, item[2],
            MediaKind.entries.firstOrNull { it.name == item[3] } ?: return null,
            item[4].toLongOrNull() ?: return null, item[5],
        )
    }
    val request = TrashApprovalRequest(operationId, restore, identities)
    return when (saved.firstOrNull()) {
        "awaiting" -> TrashApproval.Awaiting(request)
        "verifying" -> TrashApproval.Verifying(request)
        else -> null
    }
}

internal fun trashRequestBytes(sizes: List<Long>): Long = sizes.fold(0L) { sum, value ->
    val size = value.coerceAtLeast(0L)
    if (size > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + size
}
