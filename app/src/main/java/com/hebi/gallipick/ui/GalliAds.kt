package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadResult
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.hebi.gallipick.R
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CLOUD_AD_ENTITLEMENT_REFRESH_MS = 5 * 60 * 1000L
private const val CLOUD_AD_ENTITLEMENT_RETRY_MS = 2_000L
private const val CLOUD_AD_ENTITLEMENT_FAST_RETRIES = 2

internal data class GalliAdsSession(
    val configured: Boolean = false,
    val consentGranted: Boolean = false,
    val sdkReady: Boolean = false,
    val previewOnly: Boolean = false,
    val removal: AdRemovalStatus = AdRemovalStatus.NOT_PURCHASED,
    val privacyOptionsRequired: Boolean = false,
    val openPrivacyOptions: () -> Unit = {},
)

/**
 * UMP is consulted each app launch. Ads and the GMA SDK remain disabled until
 * consent permits requests. This never reads or stores a substitute consent flag.
 */
@Composable
internal fun rememberGalliAdsSession(
    activity: Activity,
    cloudConnection: CloudAccountConnection?,
    planObservation: CloudAdPlanObservation?,
    billingScreenVisible: Boolean = false,
): GalliAdsSession {
    val configured = remember(activity) {
        activity.resources.getBoolean(R.bool.gallipick_ads_enabled)
    }
    val previewOnly = remember(activity) {
        activity.resources.getBoolean(R.bool.gallipick_ads_preview)
    }
    if (!configured && !previewOnly) return GalliAdsSession()

    val removal = rememberCloudAdRemovalStatus(cloudConnection, planObservation, billingScreenVisible)
    if (previewOnly) return GalliAdsSession(previewOnly = true, removal = removal)
    val consentInformation = remember(activity) {
        UserMessagingPlatform.getConsentInformation(activity)
    }
    var canRequestAds by remember(activity) { mutableStateOf(false) }
    var privacyRequired by remember(activity) { mutableStateOf(false) }
    var initialized by remember(activity) { mutableStateOf(false) }
    var consentInfoUpdated by remember(activity) { mutableStateOf(false) }
    var consentFormStarted by remember(activity) { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    fun updateConsentStatus() {
        canRequestAds = consentInformation.canRequestAds()
        privacyRequired = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    DisposableEffect(activity, consentInformation) {
        var disposed = false
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (!disposed) {
                    updateConsentStatus()
                    consentInfoUpdated = true
                }
            },
            { if (!disposed) updateConsentStatus() },
        )
        onDispose { disposed = true }
    }

    LaunchedEffect(activity, consentInfoUpdated, removal, billingScreenVisible, lifecycleState) {
        if (consentInfoUpdated && !consentFormStarted && !billingScreenVisible &&
            lifecycleState == Lifecycle.State.RESUMED && removal == AdRemovalStatus.NOT_PURCHASED
        ) {
            consentFormStarted = true
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                if (!activity.isDestroyed && !activity.isFinishing) updateConsentStatus()
            }
        }
    }

    LaunchedEffect(activity, canRequestAds, removal) {
        if (canRequestAds && removal == AdRemovalStatus.NOT_PURCHASED && !initialized) {
            withContext(Dispatchers.IO) {
                MobileAds.initialize(
                    activity,
                    InitializationConfig.Builder(activity.getString(R.string.gallipick_admob_app_id)).build(),
                ) { }
            }
            initialized = true
        }
    }

    return GalliAdsSession(
        configured = true,
        consentGranted = canRequestAds,
        sdkReady = initialized,
        removal = removal,
        privacyOptionsRequired = privacyRequired,
        openPrivacyOptions = {
            if (privacyRequired) {
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { updateConsentStatus() }
            }
        },
    )
}

@Composable
internal fun rememberCloudAdRemovalStatus(
    connection: CloudAccountConnection?,
    planObservation: CloudAdPlanObservation?,
    billingScreenVisible: Boolean = false,
): AdRemovalStatus {
    if (connection == null) return AdRemovalStatus.NOT_PURCHASED
    val billing = connection.billing ?: return AdRemovalStatus.NOT_PURCHASED
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return AdRemovalStatus.NOT_PURCHASED
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lastObservation by remember(billing, uid) { mutableStateOf<CloudAdPlanObservation?>(null) }
    var currentPlan by remember(billing, uid) { mutableStateOf<CloudAdPlanObservation?>(null) }
    val currentAccountObservation = planObservation?.takeIf { it.uid == uid }
    val newObservation = currentAccountObservation?.takeIf { it != lastObservation }

    LaunchedEffect(lifecycle, billing, uid, currentAccountObservation, billingScreenVisible) {
        if (newObservation != null) {
            lastObservation = newObservation
            currentPlan = newObservation
        }
        if (billingScreenVisible) {
            // An earlier no-purchase result is stale once checkout can start.
            if (currentPlan?.removalFor(uid, System.currentTimeMillis()) == AdRemovalStatus.NOT_PURCHASED) {
                currentPlan = null
            }
            return@LaunchedEffect
        }

        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var recoverPurchase = true
            var retries = 0
            while (true) {
                val beforeRequest = System.currentTimeMillis()
                val needsRecheck = currentPlan?.removalFor(uid, beforeRequest) == AdRemovalStatus.VERIFYING
                if (needsRecheck) currentPlan = null
                val plan = try {
                    billing.open(uid).use { session ->
                        loadCloudAdPlan(session, recoverPurchase || needsRecheck, beforeRequest)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                val observation = plan?.let { CloudAdPlanObservation(uid, it) }
                currentPlan = observation
                val now = System.currentTimeMillis()
                val status = observation?.removalFor(uid, now) ?: AdRemovalStatus.VERIFYING
                recoverPurchase = status == AdRemovalStatus.VERIFYING
                if (recoverPurchase && retries < CLOUD_AD_ENTITLEMENT_FAST_RETRIES) {
                    retries++
                    delay(CLOUD_AD_ENTITLEMENT_RETRY_MS * retries)
                } else {
                    val expiresAt = plan?.verifiedAdRemovalExpiry(now)
                    delay(expiresAt?.let { minOf(it - now, CLOUD_AD_ENTITLEMENT_REFRESH_MS) }
                        ?: CLOUD_AD_ENTITLEMENT_REFRESH_MS)
                    retries = 0
                }
            }
        }
    }
    return (newObservation ?: currentPlan)?.removalFor(uid, System.currentTimeMillis()) ?: AdRemovalStatus.VERIFYING
}

@Composable
internal fun GalliHomeBanner(
    activity: Activity,
    session: GalliAdsSession,
) {
    val isPreview = LocalInspectionMode.current
    val policy = GalliAdEligibility(
        configured = session.configured,
        consentGranted = session.consentGranted,
        sdkReady = session.sdkReady,
        removal = session.removal,
        previewOnly = session.previewOnly,
    )
    if (isPreview || !canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, policy)) return

    if (session.previewOnly) {
        HomeBannerFrame {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).galliCardSurface(Panel).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("광고 표시 테스트", style = MaterialTheme.typography.labelMedium, color = Muted)
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bannerWidth = maxWidth.value.toInt().coerceAtMost(600)
        if (bannerWidth < 320) return@BoxWithConstraints

        val unitId = activity.getString(R.string.gallipick_admob_banner_unit_id)
        val adSize = remember(bannerWidth) {
            AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, bannerWidth)
        }
        var banner by remember(unitId, bannerWidth) { mutableStateOf<BannerAd?>(null) }

        LaunchedEffect(activity, unitId, bannerWidth) {
            try {
                when (val outcome = BannerAd.load(BannerAdRequest.Builder(unitId, adSize).build())) {
                    is AdLoadResult.Success -> banner = outcome.ad
                    is AdLoadResult.Failure -> banner = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                banner = null
            }
        }
        banner?.let { loaded ->
            DisposableEffect(loaded) { onDispose { loaded.destroy() } }
            HomeBannerFrame(Modifier.testTag("home-admob-banner")) {
                AndroidView(
                    factory = { loaded.getView(activity) },
                    modifier = Modifier.wrapContentSize(),
                )
            }
        }
    }
}

@Composable
private fun HomeBannerFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(AppSurface).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(color = Line)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            content()
        }
        HorizontalDivider(color = Line)
    }
}
