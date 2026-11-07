package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrashOutcomePolicyTest {
    @Test fun trashCanChangeGenerationWithoutChangingTheFileIdentity() {
        val before = item(1)
        val after = before.copy(isTrashed = true, generationModified = 99, dateModifiedMs = 88)
        assertEquals(TrashOutcome(1, 0, 0, 1_024L), TrashOutcomePolicy.classify(listOf(TrashIdentity.from(before)), mapOf(before.uri to after), false))
    }

    @Test fun partialApprovalDistinguishesUnchangedAndUnknownFiles() {
        val items = (1L..3L).map(::item)
        assertEquals(TrashOutcome(1, 1, 1, 1_024L), TrashOutcomePolicy.classify(items.map(TrashIdentity::from), mapOf(
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
        assertEquals(TrashOutcome(1, 0, 0, 1_024L), TrashOutcomePolicy.classify(listOf(TrashIdentity.from(before)), mapOf(before.uri to before.copy(isTrashed = false)), true))
    }

    @Test fun duplicateRequestsCountAndSumOnlyOnce() {
        val original = item(1)
        val identity = TrashIdentity.from(original)
        assertEquals(TrashOutcome(1, 0, 0, 1024L),
            TrashOutcomePolicy.classify(listOf(identity, identity), mapOf(original.uri to original.copy(isTrashed = true)), false))
    }

    @Test fun mismatchAndUnchangedNeverAddToVerifiedBytes() {
        val a = item(1)
        val b = item(2)
        val c = item(3)
        val outcome = TrashOutcomePolicy.classify(listOf(a, b, c).map(TrashIdentity::from), mapOf(
            a.uri to a.copy(isTrashed = true),
            b.uri to b.copy(isTrashed = true, sizeBytes = b.sizeBytes + 1L),
            c.uri to c,
        ), false)
        assertEquals(TrashOutcome(1, 1, 1, 1024L), outcome)
    }

    @Test fun confirmedBytesSaturateInsteadOfOverflowing() {
        val a = item(1).copy(sizeBytes = Long.MAX_VALUE)
        val b = item(2).copy(sizeBytes = 10L)
        assertEquals(TrashOutcome(2, 0, 0, Long.MAX_VALUE),
            TrashOutcomePolicy.classify(listOf(a, b).map(TrashIdentity::from),
                mapOf(a.uri to a.copy(isTrashed = true), b.uri to b.copy(isTrashed = true)), false))
    }

    private fun item(id: Long) = MediaItem("content://fixture/$id", id, "external", "$id.jpg", MediaKind.PHOTO,
        "image/jpeg", "Pictures/", 1024, 100, 100, 0, 10, 10, 10, volumeVersion = "epoch")
}
