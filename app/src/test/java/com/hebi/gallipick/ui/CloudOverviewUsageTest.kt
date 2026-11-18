package com.hebi.gallipick.ui

import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.GALLIPICK_RETIRED_CLOUD_PILOT
import com.hebi.gallipick.data.cloud.productionBillingProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudOverviewUsageTest {
    private val product = checkNotNull(productionBillingProduct("gallipick_cloud_10gb", "monthly"))
    private val now = 1_800_000_000_000L
    private val paid = CloudBillingPlan(
        enabled = true,
        products = listOf(product),
        activeProductId = product.productId,
        activeBasePlanId = product.basePlanId,
        expiresAtMs = now + 60_000,
        quotaBytes = product.quotaBytes,
        usedBytes = 12_000_000_000L,
        backupAvailable = true,
    )

    @Test
    fun reportsTheRealRatioEvenWhenStoredBytesExceedTheNewPlanLimit() {
        val usage = checkNotNull(cloudOverviewUsage(paid, production = true, nowMillis = now))

        assertEquals(12_000_000_000L, usage.usedBytes)
        assertEquals(10_000_000_000L, usage.quotaBytes)
        assertEquals(1.2, checkNotNull(usage.usedFraction), 0.0001)
    }

    @Test
    fun expiredAndLegacyPlansRetainUsageWithoutDisplayingAStorageQuota() {
        val expired = cloudOverviewUsage(paid.copy(expiresAtMs = now), production = true, nowMillis = now)
        val legacy = cloudOverviewUsage(paid.copy(activeProductId = GALLIPICK_RETIRED_CLOUD_PILOT,
            quotaBytes = 1_073_741_824L), production = false, nowMillis = now)

        assertEquals(12_000_000_000L, expired?.usedBytes)
        assertNull(expired?.usedFraction)
        assertEquals(12_000_000_000L, legacy?.usedBytes)
        assertNull(legacy?.quotaBytes)
    }

    @Test
    fun hidesUsageWhenServerDoesNotSupplyStorageOrWhenQuotaContradictsTheProduct() {
        assertNull(cloudOverviewUsage(paid.copy(backupAvailable = false), production = true, nowMillis = now))
        assertNull(cloudOverviewUsage(paid.copy(quotaBytes = 1_073_741_824L), production = true, nowMillis = now))
    }

    @Test
    fun reportsOnlyAValidProductionCatalogCountIncludingAnExplicitZero() {
        val empty = paid.copy(verifiedMediaCount = 0)
        val populated = paid.copy(verifiedMediaCount = 12)

        assertEquals(0, cloudOverviewUsage(empty, production = true, nowMillis = now)?.verifiedMediaCount)
        assertEquals(12, cloudOverviewUsage(populated, production = true, nowMillis = now)?.verifiedMediaCount)
        assertNull(cloudOverviewUsage(paid, production = true, nowMillis = now)?.verifiedMediaCount)
        assertNull(cloudOverviewUsage(paid.copy(verifiedMediaCount = -1), production = true, nowMillis = now)?.verifiedMediaCount)
        assertNull(cloudOverviewUsage(populated, production = false, nowMillis = now)?.verifiedMediaCount)
    }
}
