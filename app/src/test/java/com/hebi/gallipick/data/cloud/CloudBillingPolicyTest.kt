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
