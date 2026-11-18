package com.hebi.gallipick.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBillingPolicyTest {
    private val plan = CloudBillingProduct("cloud-30", "monthly", 30_000_000_000)
    private val account = "a".repeat(64)

    @Test fun onlyExactRecurringMonthlyBasePlanCanBeOffered() {
        fun match(
            productId: String = plan.productId,
            basePlanId: String = plan.basePlanId,
            offerId: String? = null,
            billingPeriod: String = "P1M",
            recurring: Boolean = true,
            priceMicros: Long = 3_900_000_000,
        ) = cloudBillingOfferMatches(plan, productId, basePlanId, offerId, billingPeriod, recurring, priceMicros)

        assertTrue(match())
        assertFalse(match(productId = "cloud-100"))
        assertFalse(match(basePlanId = "yearly"))
        assertFalse(match(offerId = "promotional"))
        assertFalse(match(billingPeriod = "P1Y"))
        assertFalse(match(recurring = false))
        assertFalse(match(priceMicros = 0))
    }

    @Test fun adFreeBasePlansAcceptOnlyTheApprovedMonthlyAndAnnualPeriods() {
        assertTrue(matchesStandaloneAdFreeOffer("gallipick_ad_free", "monthly",
            null, "P1M", true, 1_000_000L))
        assertTrue(matchesStandaloneAdFreeOffer("gallipick_ad_free", "annual",
            null, "P1Y", true, 9_900_000L))
        assertFalse(matchesStandaloneAdFreeOffer("gallipick_ad_free", "monthly",
            null, "P1Y", true, 9_900_000L))
        assertFalse(matchesStandaloneAdFreeOffer("gallipick_ad_free", "annual",
            null, "P1M", true, 1_000_000L))
        assertFalse(matchesStandaloneAdFreeOffer("unknown", "annual",
            null, "P1Y", true, 9_900_000L))
        assertFalse(matchesStandaloneAdFreeOffer("gallipick_ad_free", "annual",
            "offer", "P1Y", true, 9_900_000L))
        assertFalse(matchesStandaloneAdFreeOffer("gallipick_ad_free", "annual",
            null, "P1Y", false, 9_900_000L))
        assertFalse(matchesStandaloneAdFreeOffer("gallipick_ad_free", "annual",
            null, "P1Y", true, 0L))
    }

    @Test fun catalogKeysDistinguishMonthlyAndAnnualWithoutGrantingStorage() {
        val monthly = CloudBillingProduct(GALLIPICK_STANDALONE_AD_FREE, "monthly", 0L)
        val annual = monthly.copy(basePlanId = "annual")
        val cloud = CloudBillingProduct("gallipick_cloud_10gb", "monthly", 10_000_000_000L)

        assertTrue(isValidCloudBillingCatalog(listOf(monthly, annual, cloud)))
        assertFalse(isValidCloudBillingCatalog(listOf(monthly, monthly, cloud)))
        assertFalse(isValidCloudBillingCatalog(listOf(monthly.copy(quotaBytes = 1L), annual)))
        assertFalse(isValidCloudBillingCatalog(listOf(monthly.copy(basePlanId = "weekly"))))
        assertFalse(isValidCloudBillingCatalog(listOf(cloud.copy(quotaBytes = 0L))))
    }

    @Test fun realOfferSelectionUsesExactStandaloneBasePlanAndBillingPeriod() {
        val monthly = CloudBillingProduct(GALLIPICK_STANDALONE_AD_FREE, "monthly", 0L)
        val annual = monthly.copy(basePlanId = "annual")
        fun matches(expected: CloudBillingProduct, quoted: CloudBillingProduct, period: String) =
            cloudBillingOfferMatches(expected, quoted.productId, quoted.basePlanId, null, period, true, 1L)

        assertTrue(matches(monthly, monthly, "P1M"))
        assertTrue(matches(annual, annual, "P1Y"))
        assertFalse(matches(monthly, annual, "P1Y"))
        assertFalse(matches(annual, annual, "P1M"))
        assertFalse(matches(annual.copy(quotaBytes = 1L), annual, "P1Y"))
    }

    @Test fun recoveryIgnoresUnrelatedOrUnboundPlayPurchases() {
        fun match(playAccount: String?, playProducts: List<String>) =
            cloudBillingPurchaseMatches(account, listOf(plan), playAccount, playProducts)

        assertTrue(match(account, listOf("cloud-30")))
        assertFalse(match("b".repeat(64), listOf("cloud-30")))
        assertFalse(match(null, listOf("cloud-30")))
        assertFalse(match(account, listOf("other-product")))
        assertFalse(match(account, listOf("cloud-30", "other-product")))
    }
}
