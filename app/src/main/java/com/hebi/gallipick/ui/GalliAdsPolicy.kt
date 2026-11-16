package com.hebi.gallipick.ui

/**
 * Ad entitlement is separate from GalliPick's cloud storage subscription.
 * Ad-free must eventually come from a server-verified Play entitlement,
 * never an editable local Boolean or an unverified purchase callback.
 */
internal enum class AdRemovalStatus {
    NOT_PURCHASED,
    VERIFIED_AD_FREE,
    VERIFYING,
}

internal enum class GalliAdPlacement {
    HOME_BANNER,
    FILES,
    CLEANUP,
    CLOUD,
    SETTINGS,
}

internal data class GalliAdEligibility(
    val configured: Boolean,
    val consentGranted: Boolean,
    val sdkReady: Boolean,
    val removal: AdRemovalStatus,
)

internal fun canDisplayGalliAd(
    placement: GalliAdPlacement,
    access: GalliAdEligibility,
): Boolean = placement == GalliAdPlacement.HOME_BANNER &&
    access.configured && access.consentGranted && access.sdkReady &&
    access.removal == AdRemovalStatus.NOT_PURCHASED

internal const val GALLIPICK_AD_FREE_PRODUCT = "gallipick_ad_free"
internal const val GALLIPICK_AD_FREE_MONTHLY = "monthly"
internal const val GALLIPICK_AD_FREE_ANNUAL = "annual"
