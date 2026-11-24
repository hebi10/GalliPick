package com.hebi.gallipick.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAnalysisSessionTest {
    private val session = RewardedAnalysisSession()
    private val host = Any()
    private val visitor = RewardedAnalysisAccount(null)
    private val owner = RewardedAnalysisAccount("owner")
    private val firstAccount = RewardedAnalysisAccount("first")
    private val secondAccount = RewardedAnalysisAccount("second")

    @Test fun opensOnlyAfterExplicitApprovalRewardAndDismissal() {
        session.approveViewing(visitor)
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)

        session.offer(visitor)
        assertEquals(RewardedAnalysisPhase.Offer(), session.phase.value)
        val attempt = approve(visitor)
        session.approveViewing(visitor)
        assertEquals(RewardedAnalysisPhase.Loading(attempt), session.phase.value)
        session.earn(attempt, visitor)
        assertFalse(session.claim(attempt, visitor))
        assertTrue(session.beginLoading(attempt, visitor))
        assertFalse(session.beginLoading(attempt, visitor))
        assertTrue(session.beginViewing(attempt, visitor, host))
        assertFalse(session.beginViewing(attempt, visitor, host))
        session.earn(attempt, visitor)
        session.earn(attempt, visitor)
        assertFalse(session.claim(attempt, visitor))
        session.dismissAd(attempt, visitor)
        session.dismissAd(attempt, visitor)
        assertTrue(session.claim(attempt, visitor))
        assertFalse(session.claim(attempt, visitor))
        assertEquals(RewardedAnalysisPhase.Active(visitor), session.phase.value)
    }

    @Test fun dismissWithoutRewardRequiresANewExplicitAttempt() {
        session.offer(owner)
        val first = approve(owner)
        assertTrue(session.beginLoading(first, owner))
        assertTrue(session.beginViewing(first, owner, host))
        session.dismissAd(first, owner)
        session.earn(first, owner)
        assertEquals(RewardedAnalysisPhase.Offer(retry = true), session.phase.value)
        val second = approve(owner)
        assertNotSame(first, second)
        assertTrue(session.beginLoading(second, owner))
        assertTrue(session.beginViewing(second, owner, host))
        session.earn(first, owner)
        session.dismissAd(first, owner)
        assertEquals(RewardedAnalysisPhase.Viewing(second, host), session.phase.value)
    }

    @Test fun failureAndNavigationCannotReusePreviousReward() {
        session.offer(owner)
        val first = approve(owner)
        assertTrue(session.beginLoading(first, owner))
        session.fail(first)
        assertEquals(RewardedAnalysisPhase.Offer(retry = true), session.phase.value)
        val second = approve(owner)
        assertTrue(session.beginLoading(second, owner))
        assertTrue(session.beginViewing(second, owner, host))
        session.earn(second, owner)
        session.dismissAd(second, owner)
        session.close()
        assertFalse(session.claim(second, owner))
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
    }

    @Test fun accountSwitchAndReloginDiscardCallbacksAndSessions() {
        session.offer(firstAccount)
        val first = approve(firstAccount)
        assertTrue(session.beginLoading(first, firstAccount))
        assertTrue(session.beginViewing(first, firstAccount, host))
        session.earn(first, firstAccount)
        session.accountChanged(secondAccount)
        session.accountChanged(firstAccount)
        session.dismissAd(first, firstAccount)
        assertFalse(session.claim(first, firstAccount))
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
        assertFalse(session.enterVerified(visitor))
        assertTrue(session.enterVerified(firstAccount))
        assertEquals(RewardedAnalysisPhase.Active(firstAccount), session.phase.value)
        session.accountChanged(visitor)
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
    }

    @Test fun newProcessCannotReuseAnEarlierEarnedAttempt() {
        session.offer(owner)
        val first = approve(owner)
        assertTrue(session.beginLoading(first, owner))
        assertTrue(session.beginViewing(first, owner, host))
        session.earn(first, owner)
        session.dismissAd(first, owner)
        assertFalse(RewardedAnalysisSession().claim(first, owner))
    }

    @Test fun sameAccountReauthenticationInvalidatesAnEarlierAttempt() {
        session.offer(owner)
        val attempt = approve(owner)
        assertTrue(session.beginLoading(attempt, owner))
        assertTrue(session.beginViewing(attempt, owner, host))
        session.earn(attempt, owner)
        session.dismissAd(attempt, owner)

        val reauthenticated = owner.copy(generation = owner.generation + 2)
        assertFalse(session.claim(attempt, reauthenticated))
        assertEquals(RewardedAnalysisPhase.Idle, session.phase.value)
    }

    @Test fun replacementHostRecoversAStuckViewWithoutAwardingIt() {
        session.offer(owner)
        val attempt = approve(owner)
        assertTrue(session.beginLoading(attempt, owner))
        assertTrue(session.beginViewing(attempt, owner, host))
        session.recoverViewing(attempt, host)
        assertEquals(RewardedAnalysisPhase.Viewing(attempt, host), session.phase.value)

        session.earn(attempt, owner)
        session.recoverViewing(attempt, Any())
        session.dismissAd(attempt, owner)
        assertEquals(RewardedAnalysisPhase.Offer(retry = true), session.phase.value)
        assertFalse(session.claim(attempt, owner))
    }

    private fun approve(account: RewardedAnalysisAccount): RewardedAnalysisAttempt {
        session.approveViewing(account)
        return (session.phase.value as RewardedAnalysisPhase.Loading).attempt
    }
}
