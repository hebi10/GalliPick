package com.hebi.gallipick.ui

import android.app.Activity
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.delay
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

    val cloud = connection?.session
    val account = cloud?.account
    val status = cloud?.status
    val observedStatus = status?.collectAsState()?.value
    val observedAccount = RewardedAnalysisAccount(account?.collectAsState()?.value?.uid, observedStatus?.generation ?: 0L)
    val phase by session.phase.collectAsState()
    val currentAds by rememberUpdatedState(ads)
    val offerAllowed by rememberUpdatedState(canOffer)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    fun resumed() = activity != null && !activity.isFinishing && !activity.isDestroyed &&
        lifecycle.currentState == Lifecycle.State.RESUMED
    fun accountChanging() = status?.value?.operation != null
    fun currentAccount() = RewardedAnalysisAccount(account?.value?.uid, status?.value?.generation ?: 0L)
    fun removal(): AdRemovalStatus {
        val current = currentAccount()
        return if (accountChanging() || currentAds.entitlementUid != current.uid ||
            currentAds.entitlementGeneration != current.generation ||
            (current.uid != null && connection?.billing == null)
        ) AdRemovalStatus.VERIFYING else currentAds.removal
    }
    fun canRequestAd() = currentAds.configured && currentAds.sdkReady && currentAds.consentGranted &&
        currentAds.canRequestAdsNow() && removal() == AdRemovalStatus.NOT_PURCHASED

    LaunchedEffect(observedAccount) { session.accountChanged(currentAccount()) }
    val host = remember(activity) { Any() }
    val view = LocalView.current
    var windowFocused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { windowFocused = it }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
    }
    val viewing = phase as? RewardedAnalysisPhase.Viewing
    LaunchedEffect(host, viewing?.attempt, windowFocused, lifecycleState) {
        if (viewing != null && viewing.host !== host && windowFocused && resumed()) {
            // A late dismissal from the old activity may still arrive after foregrounding.
            delay(750)
            if (resumed() && view.hasWindowFocus()) session.recoverViewing(viewing.attempt, host)
        }
    }
    LaunchedEffect(canOffer) {
        if (!canOffer && session.phase.value !is RewardedAnalysisPhase.Active) session.close()
    }
    LaunchedEffect(activity, (phase as? RewardedAnalysisPhase.Loading)?.attempt) {
        val attempt = (phase as? RewardedAnalysisPhase.Loading)?.attempt ?: return@LaunchedEffect
        if (!session.beginLoading(attempt, currentAccount())) return@LaunchedEffect
        if (activity == null || !resumed() || !offerAllowed || !canRequestAd()) {
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
            !session.beginViewing(attempt, currentAccount(), host)
        ) {
            ad?.destroy()
            session.fail(attempt)
            return@LaunchedEffect
        }
        showRewardedAnalysisAd(activity, ad, session, attempt) {
            currentAccount().takeUnless { accountChanging() }
        }
    }
    LaunchedEffect(phase, observedAccount, observedStatus?.operation, canOffer, lifecycleState) {
        val ready = phase as? RewardedAnalysisPhase.Ready ?: return@LaunchedEffect
        if (canOffer && resumed() && !accountChanging() && session.claim(ready.attempt, currentAccount())) onOpenResults()
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
                    if (resumed() && offerAllowed && !accountChanging()) {
                        val current = currentAccount()
                        when {
                            removal() == AdRemovalStatus.VERIFIED_AD_FREE -> {
                                if (session.enterVerified(current)) onOpenResults()
                            }
                            canRequestAd() -> session.approveViewing(current)
                            else -> currentAds.refreshVerification()
                        }
                    }
                },
            )
        }
    }
    return RewardedAnalysisAccess(
        canShowResults = !accountChanging() &&
            session.phase.value == RewardedAnalysisPhase.Active(currentAccount()),
        requestResults = {
            if (resumed() && offerAllowed && !accountChanging()) {
                val current = currentAccount()
                if (removal() == AdRemovalStatus.VERIFIED_AD_FREE) {
                    if (session.enterVerified(current)) onOpenResults()
                } else session.offer(current)
            }
        },
    )
}

private fun showRewardedAnalysisAd(
    activity: Activity,
    ad: RewardedAd,
    session: RewardedAnalysisSession,
    attempt: RewardedAnalysisAttempt,
    currentAccount: () -> RewardedAnalysisAccount?,
) {
    fun release() {
        ad.adEventCallback = null
        ad.destroy()
    }
    try {
        ad.adEventCallback = object : RewardedAdEventCallback {
            override fun onAdDismissedFullScreenContent() {
                activity.runOnUiThread {
                    currentAccount()?.let { session.dismissAd(attempt, it) } ?: session.fail(attempt)
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
        ad.show(activity) {
            activity.runOnUiThread { currentAccount()?.let { session.earn(attempt, it) } }
        }
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
