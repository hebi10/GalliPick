package com.hebi.gallipick.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAnalysisSessionTest {
    private val session = RewardedAnalysisSession()

    @Test fun opensOnlyAfterExplicitApprovalRewardAndDismissal() {
        session.approveViewing(null)
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)

        session.offer(null)
        assertEquals(RewardedAnalysisPhase.Offer(), session.phase.value)
        val attempt = approve(null)
        session.approveViewing(null)
        assertEquals(RewardedAnalysisPhase.Loading(attempt), session.phase.value)
        session.earn(attempt, null)
        assertFalse(session.claim(attempt, null))
        assertTrue(session.beginLoading(attempt, null))
        assertFalse(session.beginLoading(attempt, null))
        assertTrue(session.beginViewing(attempt, null))
        assertFalse(session.beginViewing(attempt, null))
        session.earn(attempt, null)
        session.earn(attempt, null)
        assertFalse(session.claim(attempt, null))
        session.dismissAd(attempt, null)
        session.dismissAd(attempt, null)
        assertTrue(session.claim(attempt, null))
        assertFalse(session.claim(attempt, null))
        assertEquals(RewardedAnalysisPhase.Active(null), session.phase.value)
    }

    @Test fun dismissWithoutRewardRequiresANewExplicitAttempt() {
        session.offer("owner")
        val first = approve("owner")
        assertTrue(session.beginLoading(first, "owner"))
        assertTrue(session.beginViewing(first, "owner"))
        session.dismissAd(first, "owner")
        session.earn(first, "owner")
        assertEquals(RewardedAnalysisPhase.Offer(retry = true), session.phase.value)
        val second = approve("owner")
        assertNotSame(first, second)
        assertTrue(session.beginLoading(second, "owner"))
        assertTrue(session.beginViewing(second, "owner"))
        session.earn(first, "owner")
        session.dismissAd(first, "owner")
        assertEquals(RewardedAnalysisPhase.Viewing(second), session.phase.value)
    }

    @Test fun failureAndNavigationCannotReusePreviousReward() {
        session.offer("owner")
        val first = approve("owner")
        assertTrue(session.beginLoading(first, "owner"))
        session.fail(first)
        assertEquals(RewardedAnalysisPhase.Offer(retry = true), session.phase.value)
        val second = approve("owner")
        assertTrue(session.beginLoading(second, "owner"))
        assertTrue(session.beginViewing(second, "owner"))
        session.earn(second, "owner")
        session.dismissAd(second, "owner")
        session.close()
        assertFalse(session.claim(second, "owner"))
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
    }

    @Test fun accountSwitchAndReloginDiscardCallbacksAndSessions() {
        session.offer("first")
        val first = approve("first")
        assertTrue(session.beginLoading(first, "first"))
        assertTrue(session.beginViewing(first, "first"))
        session.earn(first, "first")
        session.accountChanged("second")
        session.accountChanged("first")
        session.dismissAd(first, "first")
        assertFalse(session.claim(first, "first"))
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
        assertFalse(session.enterVerified(null))
        assertTrue(session.enterVerified("first"))
        assertEquals(RewardedAnalysisPhase.Active("first"), session.phase.value)
        session.accountChanged(null)
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
    }

    @Test fun newProcessCannotReuseAnEarlierEarnedAttempt() {
        session.offer("owner")
        val first = approve("owner")
        assertTrue(session.beginLoading(first, "owner"))
        assertTrue(session.beginViewing(first, "owner"))
        session.earn(first, "owner")
        session.dismissAd(first, "owner")
        assertFalse(RewardedAnalysisSession().claim(first, "owner"))
    }

    private fun approve(uid: String?): RewardedAnalysisAttempt {
        session.approveViewing(uid)
        return (session.phase.value as RewardedAnalysisPhase.Loading).attempt
    }
}
