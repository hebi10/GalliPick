package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrashOutcomePolicyTest {
    @Test fun trashCanChangeGenerationWithoutChangingTheFileIdentity() {
        val before = item(1)
        val after = before.copy(isTrashed = true, generationModified = 99, dateModifiedMs = 88)
        assertEquals(TrashOutcome(1, 0, 0), TrashOutcomePolicy.classify(listOf(TrashIdentity.from(before)), mapOf(before.uri to after), false))
    }

    @Test fun partialApprovalDistinguishesUnchangedAndUnknownFiles() {
        val items = (1L..3L).map(::item)
        assertEquals(TrashOutcome(1, 1, 1), TrashOutcomePolicy.classify(items.map(TrashIdentity::from), mapOf(
            items[0].uri to items[0].copy(isTrashed = true), items[1].uri to items[1], items[2].uri to null,
        ), false))
    }

    @Test fun reusedProviderIdentityIsNotReportedAsSuccess() {
        val before = item(1)
        assertEquals(TrashOutcome(0, 0, 1), TrashOutcomePolicy.classify(listOf(TrashIdentity.from(before)),
            mapOf(before.uri to before.copy(isTrashed = true, volumeVersion = "new-provider")), false))
    }

    @Test fun restoreRequiresTheFileToLeaveTrash() {
        val before = item(1).copy(isTrashed = true)
        assertEquals(TrashOutcome(1, 0, 0), TrashOutcomePolicy.classify(listOf(TrashIdentity.from(before)), mapOf(before.uri to before.copy(isTrashed = false)), true))
    }

    private fun item(id: Long) = MediaItem("content://fixture/$id", id, "external", "$id.jpg", MediaKind.PHOTO,
        "image/jpeg", "Pictures/", 1024, 100, 100, 0, 10, 10, 10, volumeVersion = "epoch")
}
