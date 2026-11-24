package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadResult
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

private const val GOOGLE_REWARDED_TEST_UNIT = "ca-app-pub-3940256099942544/5224354917"

internal data class RewardedAnalysisAccess(val canShowResults: Boolean, val requestResults: () -> Unit)

@Composable
internal fun rememberRewardedAnalysisAccess(
    activity: Activity?,
    connection: CloudAccountConnection?,
    ads: GalliAdsSession,
    session: RewardedAnalysisSession,
    canOffer: Boolean,
    onOpenResults: () -> Unit,
): RewardedAnalysisAccess {
    if (!ads.rewardedTestEnabled) return RewardedAnalysisAccess(true, onOpenResults)

    val account = connection?.session?.account
    val uid = account?.collectAsState()?.value?.uid
    val phase by session.phase.collectAsState()
    val currentAds by rememberUpdatedState(ads)
    val offerAllowed by rememberUpdatedState(canOffer)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    fun resumed() = activity != null && !activity.isFinishing && !activity.isDestroyed &&
        lifecycle.currentState == Lifecycle.State.RESUMED
    fun removal(): AdRemovalStatus {
        val currentUid = account?.value?.uid
        return if (currentAds.entitlementUid != currentUid || (currentUid != null && connection?.billing == null)) {
            AdRemovalStatus.VERIFYING
        } else currentAds.removal
    }
    fun canRequestAd() = currentAds.configured && currentAds.sdkReady && currentAds.consentGranted &&
        currentAds.canRequestAdsNow() && removal() == AdRemovalStatus.NOT_PURCHASED

    LaunchedEffect(account) {
        if (account == null) session.accountChanged(null)
        else account.collect { session.accountChanged(it?.uid) }
    }
    LaunchedEffect(canOffer) {
        if (!canOffer && session.phase.value !is RewardedAnalysisPhase.Active) session.close()
    }
    LaunchedEffect(activity, (phase as? RewardedAnalysisPhase.Loading)?.attempt) {
        val attempt = (phase as? RewardedAnalysisPhase.Loading)?.attempt ?: return@LaunchedEffect
        if (!session.beginLoading(attempt, account?.value?.uid)) return@LaunchedEffect
        if (activity == null || !resumed() || !offerAllowed || !canRequestAd() || attempt.uid != account?.value?.uid) {
            session.fail(attempt)
            return@LaunchedEffect
        }
        val ad = try {
            val result = withTimeoutOrNull(15_000) {
                RewardedAd.load(AdRequest.Builder(GOOGLE_REWARDED_TEST_UNIT).build())
            }
            if (result is AdLoadResult.Success) result.ad else null
        } catch (cancelled: CancellationException) {
            session.fail(attempt)
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (ad == null || !resumed() || !offerAllowed || !canRequestAd() ||
            !session.beginViewing(attempt, account?.value?.uid)
        ) {
            ad?.destroy()
            session.fail(attempt)
            return@LaunchedEffect
        }
        showRewardedAnalysisAd(activity, ad, session, attempt) { account?.value?.uid }
    }
    LaunchedEffect(phase, uid, canOffer, lifecycleState) {
        val ready = phase as? RewardedAnalysisPhase.Ready ?: return@LaunchedEffect
        if (canOffer && resumed() && session.claim(ready.attempt, account?.value?.uid)) onOpenResults()
    }

    if (canOffer) {
        val offer = phase as? RewardedAnalysisPhase.Offer
        if (offer != null || phase is RewardedAnalysisPhase.Loading) {
            val status = removal()
            RewardedAnalysisDialog(
                status = status,
                canWatch = canRequestAd(),
                loading = phase is RewardedAnalysisPhase.Loading,
                retry = offer?.retry == true,
                onDismiss = session::close,
                onConfirm = {
                    if (resumed() && offerAllowed) {
                        val currentUid = account?.value?.uid
                        when {
                            removal() == AdRemovalStatus.VERIFIED_AD_FREE -> {
                                if (session.enterVerified(currentUid)) onOpenResults()
                            }
                            canRequestAd() -> session.approveViewing(currentUid)
                            else -> currentAds.refreshVerification()
                        }
                    }
                },
            )
        }
    }
    return RewardedAnalysisAccess(
        canShowResults = session.phase.value == RewardedAnalysisPhase.Active(uid),
        requestResults = {
            if (resumed() && offerAllowed) {
                val currentUid = account?.value?.uid
                if (removal() == AdRemovalStatus.VERIFIED_AD_FREE) {
                    if (session.enterVerified(currentUid)) onOpenResults()
                } else session.offer(currentUid)
            }
        },
    )
}

private fun showRewardedAnalysisAd(
    activity: Activity,
    ad: RewardedAd,
    session: RewardedAnalysisSession,
    attempt: RewardedAnalysisAttempt,
    currentUid: () -> String?,
) {
    fun release() {
        ad.adEventCallback = null
        ad.destroy()
    }
    try {
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                activity.runOnUiThread {
                    session.dismissAd(attempt, currentUid())
                    release()
                }
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                activity.runOnUiThread {
                    session.fail(attempt)
                    release()
                }
            }
        }
        ad.show(activity) { activity.runOnUiThread { session.earn(attempt, currentUid()) } }
    } catch (_: Exception) {
        session.fail(attempt)
        release()
    }
}

@Composable
internal fun RewardedAnalysisDialog(
    status: AdRemovalStatus,
    canWatch: Boolean,
    loading: Boolean,
    retry: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("rewarded-soft-focus-dialog"),
        title = { Text("선명도 확인 후보") },
        text = {
            Column(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(when {
                    loading -> "시험 광고를 불러오는 중입니다."
                    status == AdRemovalStatus.VERIFYING -> "광고 제거 혜택을 확인하고 있습니다."
                    status == AdRemovalStatus.VERIFIED_AD_FREE -> "광고 제거 혜택이 확인됐습니다."
                    !canWatch -> "지금은 시험 광고를 사용할 수 없습니다. 뒤로 돌아가 다른 분석 결과를 볼 수 있습니다."
                    retry -> "광고를 완료하지 못했습니다. 시험 광고를 완료하면 선명도 후보를 한 번 볼 수 있습니다."
                    else -> "시험 광고를 완료하면 선명도 후보를 한 번 볼 수 있습니다."
                })
                if (loading) CircularProgressIndicator()
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = {
            if (!loading) {
                TextButton(onClick = onConfirm) {
                    Text(when {
                        status == AdRemovalStatus.VERIFIED_AD_FREE -> "결과 보기"
                        canWatch -> "광고 시청하고 이용"
                        status == AdRemovalStatus.VERIFYING -> "혜택 다시 확인"
                        else -> "다시 확인"
                    })
                }
            }
        },
        containerColor = AppSurface,
    )
}
