package com.hebi.gallipick.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RewardedAnalysisAttempt(val uid: String?)

internal sealed interface RewardedAnalysisPhase {
    data object Idle : RewardedAnalysisPhase
    data class Offer(val retry: Boolean = false) : RewardedAnalysisPhase
    data class Loading(val attempt: RewardedAnalysisAttempt, val started: Boolean = false) : RewardedAnalysisPhase
    data class Viewing(val attempt: RewardedAnalysisAttempt, val earned: Boolean = false) : RewardedAnalysisPhase
    data class Ready(val attempt: RewardedAnalysisAttempt) : RewardedAnalysisPhase
    data class Active(val uid: String?) : RewardedAnalysisPhase
}

/** In-memory session owned by the activity ViewModel. All transitions run on the main thread. */
internal class RewardedAnalysisSession {
    private val _phase = MutableStateFlow<RewardedAnalysisPhase>(RewardedAnalysisPhase.Idle)
    val phase = _phase.asStateFlow()
    private var uid: String? = null

    fun accountChanged(currentUid: String?) {
        if (uid == currentUid) return
        uid = currentUid
        close()
    }

    fun offer(currentUid: String?) {
        accountChanged(currentUid)
        if (_phase.value == RewardedAnalysisPhase.Idle) _phase.value = RewardedAnalysisPhase.Offer()
    }

    fun enterVerified(currentUid: String?): Boolean {
        if (currentUid == null) return false
        accountChanged(currentUid)
        val current = _phase.value
        if (current != RewardedAnalysisPhase.Idle && current !is RewardedAnalysisPhase.Offer) return false
        _phase.value = RewardedAnalysisPhase.Active(currentUid)
        return true
    }

    fun approveViewing(currentUid: String?) {
        accountChanged(currentUid)
        if (_phase.value is RewardedAnalysisPhase.Offer) {
            _phase.value = RewardedAnalysisPhase.Loading(RewardedAnalysisAttempt(currentUid))
        }
    }

    fun beginLoading(attempt: RewardedAnalysisAttempt, currentUid: String?): Boolean {
        accountChanged(currentUid)
        if (_phase.value != RewardedAnalysisPhase.Loading(attempt)) return false
        _phase.value = RewardedAnalysisPhase.Loading(attempt, started = true)
        return true
    }

    fun beginViewing(attempt: RewardedAnalysisAttempt, currentUid: String?): Boolean {
        accountChanged(currentUid)
        if (_phase.value != RewardedAnalysisPhase.Loading(attempt, started = true)) return false
        _phase.value = RewardedAnalysisPhase.Viewing(attempt)
        return true
    }

    fun earn(attempt: RewardedAnalysisAttempt, currentUid: String?) {
        accountChanged(currentUid)
        val viewing = _phase.value as? RewardedAnalysisPhase.Viewing ?: return
        if (viewing.attempt === attempt && !viewing.earned) _phase.value = viewing.copy(earned = true)
    }

    fun dismissAd(attempt: RewardedAnalysisAttempt, currentUid: String?) {
        accountChanged(currentUid)
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

    fun claim(attempt: RewardedAnalysisAttempt, currentUid: String?): Boolean {
        accountChanged(currentUid)
        if (_phase.value != RewardedAnalysisPhase.Ready(attempt)) return false
        _phase.value = RewardedAnalysisPhase.Active(currentUid)
        return true
    }

    fun close() {
        _phase.value = RewardedAnalysisPhase.Idle
    }
}
