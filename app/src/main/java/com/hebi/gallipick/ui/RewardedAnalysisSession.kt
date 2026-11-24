package com.hebi.gallipick.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RewardedAnalysisAccount(val uid: String?, val generation: Long = 0)

internal class RewardedAnalysisAttempt

internal sealed interface RewardedAnalysisPhase {
    data object Idle : RewardedAnalysisPhase
    data class Offer(val retry: Boolean = false) : RewardedAnalysisPhase
    data class Loading(val attempt: RewardedAnalysisAttempt, val started: Boolean = false) : RewardedAnalysisPhase
    data class Viewing(val attempt: RewardedAnalysisAttempt, val host: Any, val earned: Boolean = false) : RewardedAnalysisPhase
    data class Ready(val attempt: RewardedAnalysisAttempt) : RewardedAnalysisPhase
    data class Active(val account: RewardedAnalysisAccount) : RewardedAnalysisPhase
}

/** In-memory session owned by the activity ViewModel. All transitions run on the main thread. */
internal class RewardedAnalysisSession {
    private val _phase = MutableStateFlow<RewardedAnalysisPhase>(RewardedAnalysisPhase.Idle)
    val phase = _phase.asStateFlow()
    private var account = RewardedAnalysisAccount(null)

    fun accountChanged(currentAccount: RewardedAnalysisAccount) {
        if (account == currentAccount) return
        account = currentAccount
        close()
    }

    fun offer(currentAccount: RewardedAnalysisAccount) {
        accountChanged(currentAccount)
        if (_phase.value == RewardedAnalysisPhase.Idle) _phase.value = RewardedAnalysisPhase.Offer()
    }

    fun enterVerified(currentAccount: RewardedAnalysisAccount): Boolean {
        if (currentAccount.uid == null) return false
        accountChanged(currentAccount)
        val current = _phase.value
        if (current != RewardedAnalysisPhase.Idle && current !is RewardedAnalysisPhase.Offer) return false
        _phase.value = RewardedAnalysisPhase.Active(currentAccount)
        return true
    }

    fun approveViewing(currentAccount: RewardedAnalysisAccount) {
        accountChanged(currentAccount)
        if (_phase.value is RewardedAnalysisPhase.Offer) {
            _phase.value = RewardedAnalysisPhase.Loading(RewardedAnalysisAttempt())
        }
    }

    fun beginLoading(attempt: RewardedAnalysisAttempt, currentAccount: RewardedAnalysisAccount): Boolean {
        accountChanged(currentAccount)
        if (_phase.value != RewardedAnalysisPhase.Loading(attempt)) return false
        _phase.value = RewardedAnalysisPhase.Loading(attempt, started = true)
        return true
    }

    fun beginViewing(attempt: RewardedAnalysisAttempt, currentAccount: RewardedAnalysisAccount, host: Any): Boolean {
        accountChanged(currentAccount)
        if (_phase.value != RewardedAnalysisPhase.Loading(attempt, started = true)) return false
        _phase.value = RewardedAnalysisPhase.Viewing(attempt, host)
        return true
    }

    fun recoverViewing(attempt: RewardedAnalysisAttempt, currentHost: Any) {
        val viewing = _phase.value as? RewardedAnalysisPhase.Viewing ?: return
        if (viewing.attempt === attempt && viewing.host !== currentHost) fail(attempt)
    }

    fun earn(attempt: RewardedAnalysisAttempt, currentAccount: RewardedAnalysisAccount) {
        accountChanged(currentAccount)
        val viewing = _phase.value as? RewardedAnalysisPhase.Viewing ?: return
        if (viewing.attempt === attempt && !viewing.earned) _phase.value = viewing.copy(earned = true)
    }

    fun dismissAd(attempt: RewardedAnalysisAttempt, currentAccount: RewardedAnalysisAccount) {
        accountChanged(currentAccount)
        val viewing = _phase.value as? RewardedAnalysisPhase.Viewing ?: return
        if (viewing.attempt !== attempt) return
        _phase.value = if (viewing.earned) RewardedAnalysisPhase.Ready(attempt) else RewardedAnalysisPhase.Offer(retry = true)
    }

    fun fail(attempt: RewardedAnalysisAttempt) {
        val current = _phase.value
        if ((current is RewardedAnalysisPhase.Loading && current.attempt === attempt) ||
            (current is RewardedAnalysisPhase.Viewing && current.attempt === attempt)
        ) {
            _phase.value = RewardedAnalysisPhase.Offer(retry = true)
        }
    }

    fun claim(attempt: RewardedAnalysisAttempt, currentAccount: RewardedAnalysisAccount): Boolean {
        accountChanged(currentAccount)
        if (_phase.value != RewardedAnalysisPhase.Ready(attempt)) return false
        _phase.value = RewardedAnalysisPhase.Active(currentAccount)
        return true
    }

    fun close() {
        _phase.value = RewardedAnalysisPhase.Idle
    }
}
