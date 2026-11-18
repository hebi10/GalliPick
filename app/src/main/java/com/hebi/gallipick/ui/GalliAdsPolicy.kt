package com.hebi.gallipick.ui

import com.hebi.gallipick.data.cloud.CloudBillingNotice
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.GALLIPICK_STANDALONE_AD_FREE
import com.hebi.gallipick.data.cloud.activeCloudProductId
import com.hebi.gallipick.data.cloud.activeStandaloneAdFreeProductId
import com.hebi.gallipick.data.cloud.activeSubscriptionProductId

internal enum class AdRemovalStatus {
    NOT_PURCHASED,
    VERIFIED_AD_FREE,
    VERIFYING,
}

internal fun cloudAdRemovalStatus(plan: CloudBillingPlan?, nowMillis: Long): AdRemovalStatus {
    if (plan == null) return AdRemovalStatus.VERIFYING
    if (!plan.enabled) return AdRemovalStatus.NOT_PURCHASED
    if (plan.activeSubscriptionProductId(nowMillis) != null) return AdRemovalStatus.VERIFIED_AD_FREE
    if (plan.activeProductId != null && (plan.expiresAtMs == null ||
            plan.products.none { it.productId == plan.activeProductId && !it.isStandaloneAdFree })) {
        return AdRemovalStatus.VERIFYING
    }
    if (plan.adFreeProductId != null &&
        (plan.adFreeProductId != GALLIPICK_STANDALONE_AD_FREE || plan.adFreeExpiresAtMs == null)) {
        return AdRemovalStatus.VERIFYING
    }
    return AdRemovalStatus.NOT_PURCHASED
}

internal fun CloudBillingPlan.verifiedAdRemovalExpiry(nowMillis: Long): Long? = listOfNotNull(
    expiresAtMs.takeIf { activeCloudProductId(nowMillis) != null },
    adFreeExpiresAtMs.takeIf { activeStandaloneAdFreeProductId(nowMillis) != null },
).maxOrNull()

internal data class CloudAdPlanObservation(val uid: String, val plan: CloudBillingPlan) {
    fun removalFor(currentUid: String, nowMillis: Long): AdRemovalStatus? {
        if (uid != currentUid) return null
        val status = cloudAdRemovalStatus(plan, nowMillis)
        // An expired observation does not tell us whether Play has renewed the subscription.
        return if ((plan.activeProductId != null || plan.adFreeProductId != null) &&
            status == AdRemovalStatus.NOT_PURCHASED) {
            AdRemovalStatus.VERIFYING
        } else {
            status
        }
    }
}

internal suspend fun loadCloudAdPlan(
    session: CloudBillingSession,
    recoverPurchase: Boolean,
    nowMillis: Long,
): CloudBillingPlan? {
    val plan = session.loadPlan()
    if (!recoverPurchase || !plan.enabled || cloudAdRemovalStatus(plan, nowMillis) != AdRemovalStatus.NOT_PURCHASED) {
        return plan
    }
    val purchase = session.recover()
    return when (purchase.notice) {
        CloudBillingNotice.VERIFIED -> purchase.plan
        CloudBillingNotice.NO_PURCHASE -> purchase.plan ?: plan
        else -> null
    }
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

internal const val GALLIPICK_AD_FREE_PRODUCT = GALLIPICK_STANDALONE_AD_FREE
internal const val GALLIPICK_AD_FREE_MONTHLY = "monthly"
internal const val GALLIPICK_AD_FREE_ANNUAL = "annual"
