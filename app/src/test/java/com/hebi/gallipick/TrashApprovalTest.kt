package com.hebi.gallipick

import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.TrashIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashApprovalTest {
    private val identity = TrashIdentity("content://media/items/3", 3, "external", MediaKind.PHOTO, 12, "v1")
    private val request = TrashApprovalRequest("request", false, listOf(identity))

    @Test fun approvalCanVerifyOnlyOnce() {
        val awaiting = TrashApproval.Awaiting(request)
        val verifying = TrashApproval.Verifying(request)

        assertEquals(TrashApprovalDecision.Verify(verifying), awaiting.decide(approved = true))
        assertEquals(TrashApprovalDecision.Ignore, verifying.decide(approved = true))
        assertEquals(TrashApprovalDecision.Ignore, verifying.decide(approved = false))
        assertEquals(TrashApprovalDecision.Ignore, null.decide(approved = true))
    }

    @Test fun cancellationKeepsRequestDetailsAndDoesNotLeadToVerification() {
        val restoring = request.copy(restore = true)
        val awaiting = TrashApproval.Awaiting(restoring)

        assertEquals(TrashApprovalDecision.Cancel(restoring), awaiting.decide(approved = false))
        assertEquals(TrashApprovalDecision.Ignore, null.decide(approved = false))
    }

    @Test fun savedStateCanDistinguishAnOpenPromptFromInProgressVerification() {
        val restoring = request.copy(restore = true)
        val awaiting = TrashApproval.Awaiting(restoring)
        val verifying = TrashApproval.Verifying(restoring)

        assertEquals(awaiting, restoreTrashApproval(awaiting.save()))
        assertEquals(verifying, restoreTrashApproval(verifying.save()))
        assertNull(restoreTrashApproval(awaiting.save().dropLast(1)))
    }

    @Test fun requestedByteCountSaturatesWithoutCountingNegativeSizes() {
        assertEquals(12L, trashRequestBytes(listOf(-1, 12)))
        assertEquals(Long.MAX_VALUE, trashRequestBytes(listOf(Long.MAX_VALUE - 1, 2)))
    }
}
