package com.hebi.gallipick.ui

import com.hebi.gallipick.domain.TrashOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupResultUiTest {
    @Test fun successRequiresFreshConfirmationOfAtLeastOneFile() {
        val result = CleanupOperationResult.verified(TrashOutcome(3, 0, 0, 3_500L), false, 3, 4_000L)
        assertEquals(CleanupResultStatus.SUCCESS, result.status)
        assertEquals(3, result.completedCount)
        assertEquals(4_000L, result.requestedBytes)
        assertEquals(3_500L, result.confirmedCompletedBytes)
        assertTrue(result.title.contains("휴지통"))
    }

    @Test fun partialConfirmationDoesNotClaimAllItemsSucceeded() {
        val result = CleanupOperationResult.verified(TrashOutcome(2, 1, 1, 4_000L), true, 4, 8_000L)
        assertEquals(CleanupResultStatus.PARTIAL, result.status)
        assertEquals(2, result.completedCount)
        assertEquals(1, result.unchangedCount)
        assertEquals(1, result.unknownCount)
        assertEquals(4_000L, result.confirmedCompletedBytes)
        assertFalse(result.title.contains("완료"))
    }

    @Test fun noObservedChangesAreNotSuccess() {
        assertEquals(CleanupResultStatus.FAILED,
            CleanupOperationResult.verified(TrashOutcome(0, 2, 0), false, 2, 2_000L).status)
        assertEquals(CleanupResultStatus.UNKNOWN,
            CleanupOperationResult.verified(TrashOutcome(0, 1, 1), false, 2, 2_000L).status)
        assertEquals(CleanupResultStatus.FAILED,
            CleanupOperationResult.verified(TrashOutcome(0, 0, 0), false, 0, 0).status)
    }

    @Test fun cancellationAndVerificationFailuresAreExplicit() {
        val cancelled = CleanupOperationResult.cancelled(true, 2, 1_024L)
        assertEquals(CleanupResultStatus.CANCELED, cancelled.status)
        assertEquals(0, cancelled.completedCount)
        assertEquals(0L, cancelled.confirmedCompletedBytes)
        assertEquals(CleanupResultStatus.UNKNOWN, CleanupOperationResult.unknown(false, 2, 1_024L).status)
        assertEquals(CleanupResultStatus.FAILED, CleanupOperationResult.failed(false, 2, 1_024L).status)
    }
}
