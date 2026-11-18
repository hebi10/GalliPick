package com.hebi.gallipick.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun productionCatalogAcceptsOnlyTheThreeDecimalCapacityTiers() {
        val cloud = listOf(
            CloudBillingProduct("gallipick_cloud_10gb", "monthly", 10_000_000_000L),
            CloudBillingProduct("gallipick_cloud_50gb", "annual", 50_000_000_000L),
            CloudBillingProduct("gallipick_cloud_100gb", "monthly", 100_000_000_000L),
        )
        val adFree = CloudBillingProduct(GALLIPICK_STANDALONE_AD_FREE, "annual", 0)
        assertTrue(isValidCloudBillingCatalog(cloud + adFree, production = true))
        assertFalse(isValidCloudBillingCatalog(listOf(cloud.first().copy(quotaBytes = 10L shl 30)), production = true))
        assertFalse(isValidCloudBillingCatalog(listOf(cloud.first().copy(basePlanId = "weekly")), production = true))
        assertFalse(isValidCloudBillingCatalog(listOf(plan), production = true))
        assertTrue(isValidCloudBillingCatalog(listOf(CloudBillingProduct("gallipick_cloud_pilot", "monthly", 1L shl 30))))
        assertTrue(cloud.all { isKnownGalliPickSubscription(it.productId) })
        assertFalse(isKnownGalliPickSubscription("unrelated"))
    }

    @Test fun productionOriginalsRemainReadableWhileUploadRequiresActiveVerifiedStorage() {
        val product = CloudBillingProduct("gallipick_cloud_10gb", "monthly", 10_000_000_000L)
        val plan = CloudBillingPlan(
            enabled = true,
            products = listOf(product),
            activeProductId = product.productId,
            expiresAtMs = 2_000,
            quotaBytes = product.quotaBytes,
            uploadAllowed = true,
            backupAvailable = true,
            maxOriginalBytes = CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES,
            backupObjectPrefix = CloudBackupNamespace.LICENSE_TEST.objectPrefix,
        )
        val access = checkNotNull(plan.productionBackupAccess())
        assertEquals(CloudBackupNamespace.LICENSE_TEST, access.namespace)
        assertTrue(access.allowsUpload(1_999))
        assertFalse(access.allowsUpload(2_000))
        assertFalse(checkNotNull(plan.copy(activeProductId = null).productionBackupAccess()).allowsUpload(0))
        assertFalse(checkNotNull(plan.copy(uploadAllowed = false).productionBackupAccess()).allowsUpload(0))
        assertFalse(checkNotNull(plan.copy(usedBytes = product.quotaBytes).productionBackupAccess()).allowsUpload(0))
        assertNull(plan.copy(testPurchasesOnly = false).productionBackupAccess())
        assertNull(plan.copy(backupAvailable = false).productionBackupAccess())
        assertNull(plan.copy(maxOriginalBytes = CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES + 1).productionBackupAccess())

        var uid: String? = "owner-a"
        val state = CloudBackupAccessState { uid }
        state.observe("owner-a", plan)
        assertEquals(access, state.forAccount("owner-a"))
        uid = "owner-b"
        state.clearForAccountChange()
        state.observe("owner-a", plan)
        assertNull(state.forAccount("owner-b"))
        uid = "owner-a"
        assertNull(state.forAccount("owner-a"))
    }

    @Test fun replacementsUseActualCurrentPriceAndKeepTheSameBillingPeriod() {
        val current = CloudBillingProduct("gallipick_cloud_50gb", "monthly", 50_000_000_000L)
        val smaller = CloudBillingProduct("gallipick_cloud_10gb", "monthly", 10_000_000_000L)
        val larger = CloudBillingProduct("gallipick_cloud_100gb", "monthly", 100_000_000_000L)
        val higherAnnual = larger.copy(basePlanId = "annual")
        val adFree = CloudBillingProduct(GALLIPICK_STANDALONE_AD_FREE, "monthly", 0L)
        val decision = verifiedReplacementPlan(current, listOf(current, smaller, larger, higherAnnual, adFree))

        val available = decision.replacementOffers(listOf(
            replacementQuote(larger, 10_900_000_000), replacementQuote(smaller, 1_900_000_000),
            replacementQuote(higherAnnual, 109_000_000_000), replacementQuote(current, 5_900_000_000),
            replacementQuote(adFree, 1_000_000_000),
        ), nowMillis = 100)
        assertEquals(listOf(
            "gallipick_cloud_100gb" to CloudBillingReplacementTiming.IMMEDIATE_PRORATED,
            "gallipick_cloud_10gb" to CloudBillingReplacementTiming.NEXT_RENEWAL,
            GALLIPICK_STANDALONE_AD_FREE to CloudBillingReplacementTiming.NEXT_RENEWAL,
        ), available.map { it.target.product.productId to it.timing })
        assertTrue(decision.replacementOffers(listOf(replacementQuote(larger, 5_000_000_000)), 100).isEmpty())
        assertTrue(decision.replacementOffers(listOf(replacementQuote(larger, 10_900_000_000, "USD")), 100).isEmpty())
    }

    @Test fun activeV2ReservationCanResumeWhenItAlreadyFillsThePlan() {
        val product = checkNotNull(productionBillingProduct("gallipick_cloud_100gb", "monthly"))
        val plan = CloudBillingPlan(
            enabled = true,
            products = listOf(product),
            activeProductId = product.productId,
            expiresAtMs = 2_000,
            quotaBytes = product.quotaBytes,
            usedBytes = product.quotaBytes,
            backupAvailable = true,
            maxOriginalBytes = CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES,
            backupObjectPrefix = CloudBackupNamespace.LICENSE_TEST.objectPrefix,
            supportsResumableTransfers = true,
            resumableUploadAllowed = true,
        )
        val access = checkNotNull(plan.productionBackupAccess())
        assertFalse(access.allowsUpload(1_999))
        assertTrue(access.allowsResumableUpload(1_999))
        assertTrue(access.allowsManualUpload(1_999))
        assertEquals(product.quotaBytes, access.maximumManualOriginalBytes(1_999))
        assertFalse(access.allowsManualUpload(2_000))
        assertFalse(checkNotNull(plan.copy(usedBytes = product.quotaBytes + 1).productionBackupAccess())
            .allowsResumableUpload(0))
        assertFalse(checkNotNull(plan.copy(supportsResumableTransfers = false).productionBackupAccess())
            .allowsResumableUpload(0))
    }

    @Test fun replacementRequiresFreshUnambiguousSourceAndNoScheduledChange() {
        val current = CloudBillingProduct("gallipick_cloud_10gb", "annual", 10_000_000_000L)
        val target = CloudBillingProduct("gallipick_cloud_50gb", "annual", 50_000_000_000L)
        val verified = verifiedReplacementPlan(current, listOf(current, target))
        val targetQuote = listOf(replacementQuote(target, 59_900_000_000))
        assertEquals(1, verified.replacementOffers(targetQuote, 100).size)
        for (blocked in listOf(
            verified.copy(replacementSourceAllowed = false), verified.copy(replacementSourcePrice = null),
            verified.copy(expiresAtMs = 100), verified.copy(activeBasePlanId = "monthly"),
            verified.copy(cloudCheckoutAllowed = false),
            verified.copy(adFreeProductId = GALLIPICK_STANDALONE_AD_FREE, adFreeExpiresAtMs = 200),
            verified.copy(scheduledReplacement = CloudBillingScheduledReplacement(target.productId, "annual", 200)),
        )) {
            assertTrue(blocked.replacementOffers(targetQuote, 100).isEmpty())
        }
    }

    private fun verifiedReplacementPlan(current: CloudBillingProduct, catalog: List<CloudBillingProduct>) =
        CloudBillingPlan(enabled = true, products = catalog, activeProductId = current.productId,
            activeBasePlanId = current.basePlanId, expiresAtMs = 200, cloudCheckoutAllowed = true,
            replacementSourceAllowed = true, replacementSourcePrice = CloudBillingReplacementPrice(5_900_000_000, "KRW"))

    private fun replacementQuote(product: CloudBillingProduct, price: Long, currency: String = "KRW") =
        CloudBillingOffer(product, product.productId, "Play price", price, currency)
}
