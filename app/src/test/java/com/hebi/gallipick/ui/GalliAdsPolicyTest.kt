package com.hebi.gallipick.ui

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

    @Test fun adRemovalIsASeparateProductFromCloudBilling() {
        assertTrue(GALLIPICK_AD_FREE_PRODUCT != "cloud_50")
        assertTrue(GALLIPICK_AD_FREE_MONTHLY != GALLIPICK_AD_FREE_ANNUAL)
    }
}
