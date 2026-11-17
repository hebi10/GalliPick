package com.hebi.gallipick.ui

import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalliAdsPolicyTest {
    private val eligible = GalliAdEligibility(
        configured = true,
        consentGranted = true,
        sdkReady = true,
        removal = AdRemovalStatus.NOT_PURCHASED,
    )

    @Test fun onlyTheNormalHomeBannerCanShowAds() {
        assertTrue(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, eligible))
        for (screen in GalliAdPlacement.entries.filterNot { it == GalliAdPlacement.HOME_BANNER }) {
            assertFalse(canDisplayGalliAd(screen, eligible))
        }
    }

    @Test fun consentAndConfigurationAlwaysGateAdRequests() {
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, eligible.copy(configured = false)))
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, eligible.copy(consentGranted = false)))
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, eligible.copy(sdkReady = false)))
    }

    @Test fun paidAndUnverifiedAdRemovalAlwaysHideAds() {
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER,
            eligible.copy(removal = AdRemovalStatus.VERIFIED_AD_FREE)))
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER,
            eligible.copy(removal = AdRemovalStatus.VERIFYING)))
    }

    @Test fun aVerifiedCloudSubscriptionSuppressesAdsUntilItsServerExpiry() {
        val product = CloudBillingProduct("gallipick_cloud_pilot", "monthly", 1_073_741_824L)
        val plan = CloudBillingPlan(
            enabled = true,
            products = listOf(product),
            activeProductId = product.productId,
            expiresAtMs = 2_000L,
        )

        val active = cloudAdRemovalStatus(plan, nowMillis = 1_999L)
        assertEquals(AdRemovalStatus.VERIFIED_AD_FREE, active)
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, eligible.copy(removal = active)))

        val expired = cloudAdRemovalStatus(plan, nowMillis = 2_000L)
        assertEquals(AdRemovalStatus.NOT_PURCHASED, expired)
        assertTrue(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, eligible.copy(removal = expired)))
    }

    @Test fun unconfirmedPlansKeepAdsHiddenDuringVerification() {
        val incomplete = CloudBillingPlan(enabled = true, activeProductId = "gallipick_cloud_pilot")
        assertEquals(AdRemovalStatus.VERIFYING, cloudAdRemovalStatus(null, nowMillis = 1_000L))
        assertEquals(AdRemovalStatus.VERIFYING, cloudAdRemovalStatus(incomplete, nowMillis = 1_000L))
        assertEquals(AdRemovalStatus.VERIFYING,
            cloudAdRemovalStatus(incomplete.copy(expiresAtMs = 2_000L), nowMillis = 1_000L))
    }

    @Test fun inactiveCloudPlansDoNotGrantAdRemoval() {
        assertEquals(AdRemovalStatus.NOT_PURCHASED,
            cloudAdRemovalStatus(CloudBillingPlan(), nowMillis = 1_000L))
        assertEquals(AdRemovalStatus.NOT_PURCHASED,
            cloudAdRemovalStatus(CloudBillingPlan(enabled = true), nowMillis = 1_000L))
    }
}
