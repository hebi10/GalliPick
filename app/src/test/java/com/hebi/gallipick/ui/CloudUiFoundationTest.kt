package com.hebi.gallipick.ui

import com.hebi.gallipick.data.cloud.CloudAccountOperation
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudUiFoundationTest {
    @Test
    fun authenticationRoutesDoNotExposePreviousAccountsFiles() {
        assertEquals(
            CloudAccessState.SIGNED_IN,
            cloudAccessState(true, "uid-a", null),
        )
        assertEquals(
            CloudRoute.LIBRARY,
            cloudVisibleRoute(CloudRoute.LIBRARY, CloudAccessState.SIGNED_IN),
        )
        listOf(
            CloudAccessState.UNAVAILABLE,
            CloudAccessState.SIGNED_OUT,
            CloudAccessState.CHOOSING_ACCOUNT,
            CloudAccessState.SIGNING_OUT,
        ).forEach { access ->
            assertEquals(CloudRoute.OVERVIEW, cloudVisibleRoute(CloudRoute.LIBRARY, access))
        }
    }

    @Test
    fun accountTransitionBlocksProtectedRoutesWhileAnOldUidExists() {
        assertEquals(
            CloudAccessState.CHOOSING_ACCOUNT,
            cloudAccessState(true, "uid-a", CloudAccountOperation.CHOOSING_ACCOUNT),
        )
        assertEquals(
            CloudAccessState.SIGNING_OUT,
            cloudAccessState(true, "uid-a", CloudAccountOperation.SIGNING_OUT),
        )
        assertEquals(CloudAccessState.SIGNED_OUT, cloudAccessState(true, null, null))
        assertEquals(CloudAccessState.UNAVAILABLE, cloudAccessState(false, "uid-a", null))
    }

    @Test
    fun failedSubscriptionLookupIsNotTreatedAsNoPurchase() {
        assertEquals(
            CloudSubscriptionUiState.LOADING,
            cloudSubscriptionUiState(loading = true, failed = false, plan = null, nowMillis = 100L),
        )
        assertEquals(
            CloudSubscriptionUiState.ERROR,
            cloudSubscriptionUiState(loading = false, failed = true, plan = null, nowMillis = 100L),
        )
        assertEquals(
            CloudSubscriptionUiState.RESTRICTED,
            cloudSubscriptionUiState(false, false, CloudBillingPlan(enabled = false), 100L),
        )
        assertEquals(
            CloudSubscriptionUiState.NONE,
            cloudSubscriptionUiState(false, false, CloudBillingPlan(enabled = true), 100L),
        )
    }

    @Test
    fun expiredSubscriptionIsNotPresentedAsActive() {
        val plan = CloudBillingPlan(enabled = true, activeProductId = "pilot", expiresAtMs = 100L)
        assertEquals(
            CloudSubscriptionUiState.ACTIVE,
            cloudSubscriptionUiState(false, false, plan, 99L),
        )
        assertEquals(
            CloudSubscriptionUiState.EXPIRED,
            cloudSubscriptionUiState(false, false, plan, 100L),
        )
    }
}
