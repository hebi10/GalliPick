package com.hebi.gallipick.ui

import android.app.Activity
import com.hebi.gallipick.data.cloud.CloudBillingNotice
import com.hebi.gallipick.data.cloud.CloudBillingOffer
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingProduct
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.CloudBillingUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun pilotPreviewFollowsAdRemovalWithoutNetworkAds() {
        val preview = GalliAdEligibility(
            configured = false,
            consentGranted = false,
            sdkReady = false,
            removal = AdRemovalStatus.NOT_PURCHASED,
            previewOnly = true,
        )
        assertTrue(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER, preview))
        assertFalse(canDisplayGalliAd(GalliAdPlacement.CLOUD, preview))
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER,
            preview.copy(removal = AdRemovalStatus.VERIFIED_AD_FREE)))
        assertFalse(canDisplayGalliAd(GalliAdPlacement.HOME_BANNER,
            preview.copy(removal = AdRemovalStatus.VERIFYING)))
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

    @Test fun standaloneRecurringAdFreeRemovesAdsWithoutGrantingCloudStorage() {
        val standAlone = CloudBillingPlan(
            enabled = true,
            adFreeProductId = "gallipick_ad_free",
            adFreeExpiresAtMs = 3_000L,
            quotaBytes = 0L,
            uploadAllowed = false,
        )
        assertEquals(0L, standAlone.quotaBytes)
        assertFalse(standAlone.uploadAllowed)
        assertEquals(AdRemovalStatus.VERIFIED_AD_FREE, cloudAdRemovalStatus(standAlone, 2_999L))
        assertEquals(AdRemovalStatus.NOT_PURCHASED, cloudAdRemovalStatus(standAlone, 3_000L))
        val observation = CloudAdPlanObservation("owner", standAlone)
        assertEquals(AdRemovalStatus.VERIFYING, observation.removalFor("owner", 3_000L))
        assertNull(observation.removalFor("other", 2_999L))
    }

    @Test fun twoActiveEntitlementsGrantAdFreeUntilTheLaterExpiry() {
        val cloud = activePlan.copy(
            adFreeProductId = "gallipick_ad_free",
            adFreeExpiresAtMs = 4_000L,
        )
        assertEquals(AdRemovalStatus.VERIFIED_AD_FREE, cloudAdRemovalStatus(cloud, 3_000L))
        assertEquals(AdRemovalStatus.NOT_PURCHASED, cloudAdRemovalStatus(cloud, 4_000L))
        assertEquals(AdRemovalStatus.VERIFYING, cloudAdRemovalStatus(
            CloudBillingPlan(enabled = true, adFreeProductId = "unknown", adFreeExpiresAtMs = 4_000L), 2_000L))
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

    @Test fun purchaseObservationAppliesOnlyToTheCurrentAccountBeforeExpiry() {
        val observation = CloudAdPlanObservation("account-a", activePlan)

        assertEquals(AdRemovalStatus.VERIFIED_AD_FREE, observation.removalFor("account-a", 1_999L))
        assertNull(observation.removalFor("account-b", 1_999L))
        assertEquals(AdRemovalStatus.VERIFYING, observation.removalFor("account-a", 2_000L))
    }

    @Test fun foregroundLookupVerifiesAnUnrecordedPurchaseWithPlay() = runBlocking {
        val session = FakeAdsBillingSession(inactivePlan, CloudBillingUpdate(activePlan, CloudBillingNotice.VERIFIED))

        assertEquals(activePlan, loadCloudAdPlan(session, recoverPurchase = true, nowMillis = 1_999L))
        assertEquals(1, session.recoverCalls)
    }

    @Test fun serverEntitlementAndRoutineRefreshNeedNoExtraPlayLookup() = runBlocking {
        val active = FakeAdsBillingSession(activePlan, CloudBillingUpdate(notice = CloudBillingNotice.FAILED))
        val inactive = FakeAdsBillingSession(inactivePlan, CloudBillingUpdate(notice = CloudBillingNotice.FAILED))

        assertEquals(activePlan, loadCloudAdPlan(active, recoverPurchase = true, nowMillis = 1_999L))
        assertEquals(inactivePlan, loadCloudAdPlan(inactive, recoverPurchase = false, nowMillis = 1_999L))
        assertEquals(0, active.recoverCalls)
        assertEquals(0, inactive.recoverCalls)
    }

    @Test fun pendingAndFailedPlayLookupKeepTheAdDecisionUnconfirmed() = runBlocking {
        for (notice in listOf(CloudBillingNotice.PENDING, CloudBillingNotice.FAILED)) {
            val session = FakeAdsBillingSession(inactivePlan, CloudBillingUpdate(inactivePlan, notice))
            val result = loadCloudAdPlan(session, recoverPurchase = true, nowMillis = 1_999L)
            assertEquals(AdRemovalStatus.VERIFYING, cloudAdRemovalStatus(result, nowMillis = 1_999L))
        }
    }

    private val product = CloudBillingProduct("gallipick_cloud_pilot", "monthly", 1_073_741_824L)
    private val inactivePlan = CloudBillingPlan(enabled = true, products = listOf(product))
    private val activePlan = inactivePlan.copy(activeProductId = product.productId, expiresAtMs = 2_000L)

    private class FakeAdsBillingSession(
        private val plan: CloudBillingPlan,
        private val update: CloudBillingUpdate,
    ) : CloudBillingSession {
        var recoverCalls = 0
            private set

        override suspend fun loadPlan() = plan
        override suspend fun recover(): CloudBillingUpdate {
            recoverCalls++
            return update
        }
        override suspend fun load() = error("The ad check loads the plan directly")
        override suspend fun purchase(activity: Activity, offer: CloudBillingOffer) =
            error("The ad check never starts a purchase")
        override fun close() = Unit
    }
}
