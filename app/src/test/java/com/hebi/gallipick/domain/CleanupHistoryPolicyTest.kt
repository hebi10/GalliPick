package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class CleanupHistoryPolicyTest {
    @Test fun approvedAndVerifiedMoveCreatesOnlyConfirmedHistory() {
        val history = CleanupHistoryPolicy.confirmed("request-a", 1_700_000_000_000L, 2,
            restore = false, outcome = TrashOutcome(2, 0, 0, 8_000))
        assertNotNull(history)
        assertEquals("request-a", history!!.id)
        assertEquals(CleanupHistoryAction.MOVE_TO_TRASH, history.action)
        assertEquals(2, history.requestedCount)
        assertEquals(2, history.confirmedCount)
        assertEquals(8_000L, history.confirmedBytes)
    }

    @Test fun partialMoveAndRestoreAreRecordedWithoutCountingUnknownBytes() {
        val history = CleanupHistoryPolicy.confirmed("request-b", 1_700_000_000_000L, 3,
            restore = true, outcome = TrashOutcome(1, 1, 1, 4_000))
        assertNotNull(history)
        assertEquals(CleanupHistoryAction.RESTORE, history!!.action)
        assertEquals(1, history.confirmedCount)
        assertEquals(3, history.requestedCount)
        assertEquals(4_000L, history.confirmedBytes)
    }

    @Test fun cancellationUnknownAndUnverifiedStatesNeverBecomeHistory() {
        val now = 1_700_000_000_000L
        assertNull(CleanupHistoryPolicy.confirmed("cancelled", now, 3, false, TrashOutcome(0, 3, 0)))
        assertNull(CleanupHistoryPolicy.confirmed("unknown", now, 3, false, TrashOutcome(0, 0, 3)))
        assertNull(CleanupHistoryPolicy.confirmed("missing", now, 3, false, TrashOutcome(1, 0, 0)))
        assertNull(CleanupHistoryPolicy.confirmed("", now, 1, false, TrashOutcome(1, 0, 0)))
        assertNull(CleanupHistoryPolicy.confirmed("bad", now, 1, false, TrashOutcome(2, 0, 0)))
        assertNull(CleanupHistoryPolicy.confirmed("bad-bytes", now, 1, false, TrashOutcome(1, 0, 0, -1)))
        assertNull(CleanupHistoryPolicy.confirmed("bad-time", 0, 1, false, TrashOutcome(1, 0, 0)))
    }
}
