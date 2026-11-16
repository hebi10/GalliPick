package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GalliAdsSession(
    val configured: Boolean = false,
    val consentGranted: Boolean = false,
    val sdkReady: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
    val openPrivacyOptions: () -> Unit = {},
)

/**
 * UMP is consulted each app launch. Ads and the GMA SDK remain disabled until
 * consent permits requests. This never reads or stores a substitute consent flag.
 */
@Composable
internal fun rememberGalliAdsSession(activity: Activity): GalliAdsSession {
    val configured = remember(activity) {
        activity.resources.getBoolean(R.bool.gallipick_ads_enabled)
    }
    if (!configured) return GalliAdsSession()

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

    LaunchedEffect(activity, canRequestAds) {
        if (canRequestAds && !initialized) {
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
internal fun GalliHomeBanner(
    activity: Activity,
    session: GalliAdsSession,
    removalStatus: AdRemovalStatus = AdRemovalStatus.NOT_PURCHASED,
) {
    val isPreview = LocalInspectionMode.current
    val policy = GalliAdEligibility(
        configured = session.configured,
        consentGranted = session.consentGranted,
        sdkReady = session.sdkReady,
        removal = removalStatus,
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
