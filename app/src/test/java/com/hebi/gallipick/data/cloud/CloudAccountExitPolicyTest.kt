package com.hebi.gallipick.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountExitPolicyTest {
    @Test
    fun deletionRequestIsNotReportedAsCompleted() {
        val state = CloudExitSnapshot(CloudExitState.REQUESTED, CloudExitScope.ACCOUNT)
        assertTrue(state.deletionPending)
        assertTrue(state.accountRemovalPending)
        assertFalse(CloudExitSnapshot(CloudExitState.NONE).deletionPending)
    }

    @Test
    fun cloudOnlyCompletionDoesNotRemoveTheAccount() {
        val completed = CloudExitSnapshot(CloudExitState.COMPLETED, CloudExitScope.CLOUD)
        val resumed = CloudExitSnapshot(CloudExitState.RESUMED, CloudExitScope.CLOUD)
        assertFalse(completed.deletionPending)
        assertFalse(completed.accountRemovalPending)
        assertFalse(resumed.accountRemovalPending)
        assertFalse(resumed.deletionPending)
    }

    @Test
    fun aCompletedFullAccountExitIsStillTerminal() {
        val done = CloudExitSnapshot(CloudExitState.COMPLETED, CloudExitScope.ACCOUNT)
        assertFalse(done.deletionPending)
        assertTrue(done.accountRemovalPending)
    }
}
