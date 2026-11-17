package com.hebi.gallipick.ui

import com.hebi.gallipick.data.cloud.CloudBillingPlan

internal enum class AdRemovalStatus {
    NOT_PURCHASED,
    VERIFIED_AD_FREE,
    VERIFYING,
}

internal fun cloudAdRemovalStatus(plan: CloudBillingPlan?, nowMillis: Long): AdRemovalStatus {
    if (plan == null) return AdRemovalStatus.VERIFYING
    if (!plan.enabled || plan.activeProductId == null) return AdRemovalStatus.NOT_PURCHASED
    val expiresAtMillis = plan.expiresAtMs ?: return AdRemovalStatus.VERIFYING
    if (plan.products.none { it.productId == plan.activeProductId }) return AdRemovalStatus.VERIFYING
    return if (expiresAtMillis > nowMillis) AdRemovalStatus.VERIFIED_AD_FREE else AdRemovalStatus.NOT_PURCHASED
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
    val previewOnly: Boolean = false,
)

internal fun canDisplayGalliAd(
    placement: GalliAdPlacement,
    access: GalliAdEligibility,
): Boolean = placement == GalliAdPlacement.HOME_BANNER &&
    (access.previewOnly || (access.configured && access.consentGranted && access.sdkReady)) &&
    access.removal == AdRemovalStatus.NOT_PURCHASED

internal const val GALLIPICK_AD_FREE_PRODUCT = "gallipick_ad_free"
internal const val GALLIPICK_AD_FREE_MONTHLY = "monthly"
internal const val GALLIPICK_AD_FREE_ANNUAL = "annual"
