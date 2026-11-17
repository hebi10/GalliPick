package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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

internal data class GalliAdsSession(
    val configured: Boolean = false,
    val consentGranted: Boolean = false,
    val sdkReady: Boolean = false,
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
    cloudPlanRevision: Int,
): GalliAdsSession {
    val configured = remember(activity) {
        activity.resources.getBoolean(R.bool.gallipick_ads_enabled)
    }
    if (!configured) return GalliAdsSession()

    val removal = rememberCloudAdRemovalStatus(cloudConnection, cloudPlanRevision)
    val consentInformation = remember(activity) {
        UserMessagingPlatform.getConsentInformation(activity)
    }
    var canRequestAds by remember(activity) { mutableStateOf(false) }
    var privacyRequired by remember(activity) { mutableStateOf(false) }
    var initialized by remember(activity) { mutableStateOf(false) }

    DisposableEffect(activity, consentInformation) {
        var disposed = false
        fun updateStatus() {
            if (!disposed) {
                canRequestAds = consentInformation.canRequestAds()
                privacyRequired = consentInformation.privacyOptionsRequirementStatus ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            }
        }
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (!disposed) {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { updateStatus() }
                }
            },
            { updateStatus() },
        )
        onDispose { disposed = true }
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
                UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                    canRequestAds = consentInformation.canRequestAds()
                    privacyRequired = consentInformation.privacyOptionsRequirementStatus ==
                        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                }
            }
        },
    )
}

@Composable
private fun rememberCloudAdRemovalStatus(
    connection: CloudAccountConnection?,
    planRevision: Int,
): AdRemovalStatus {
    if (connection == null) return AdRemovalStatus.NOT_PURCHASED
    val billing = connection.billing ?: return AdRemovalStatus.NOT_PURCHASED
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return AdRemovalStatus.NOT_PURCHASED
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var removal by remember(billing, uid, planRevision) { mutableStateOf(AdRemovalStatus.VERIFYING) }
    LaunchedEffect(lifecycle, billing, uid, planRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                billing.open(uid).use { session ->
                    while (true) {
                        val plan = try {
                            session.loadPlan()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                        val now = System.currentTimeMillis()
                        removal = cloudAdRemovalStatus(plan, now)
                        val expiresAt = plan?.expiresAtMs?.takeIf { removal == AdRemovalStatus.VERIFIED_AD_FREE }
                        delay(expiresAt?.let { minOf(it - now, CLOUD_AD_ENTITLEMENT_REFRESH_MS) }
                            ?: CLOUD_AD_ENTITLEMENT_REFRESH_MS)
                        if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                            removal = AdRemovalStatus.VERIFYING
                        }
                    }
                }
            } finally {
                removal = AdRemovalStatus.VERIFYING
            }
        }
    }
    return removal
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
    )
    if (isPreview || !canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, policy)) return

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
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { loaded.getView(activity) },
                    modifier = Modifier.wrapContentSize(),
                )
            }
        }
    }
}
