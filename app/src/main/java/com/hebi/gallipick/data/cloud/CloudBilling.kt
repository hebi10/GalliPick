package com.hebi.gallipick.data.cloud

import android.app.Activity
import java.io.Closeable

internal const val GALLIPICK_STANDALONE_AD_FREE = "gallipick_ad_free"
internal const val GALLIPICK_RETIRED_CLOUD_PILOT = "gallipick_cloud_pilot"
private val PRODUCTION_CLOUD_QUOTAS = mapOf(
    "gallipick_cloud_10gb" to 10_000_000_000L,
    "gallipick_cloud_50gb" to 50_000_000_000L,
    "gallipick_cloud_100gb" to 100_000_000_000L,
)
internal val productionCloudQuotaBytes = PRODUCTION_CLOUD_QUOTAS.values.sorted()
internal fun isKnownGalliPickSubscription(productId: String): Boolean =
    productId == GALLIPICK_STANDALONE_AD_FREE || productId in PRODUCTION_CLOUD_QUOTAS ||
        productId == GALLIPICK_RETIRED_CLOUD_PILOT
private val CLOUD_PRODUCT_ID = Regex("[a-z0-9][a-z0-9_.]{0,149}")
private val CLOUD_BASE_PLAN_ID = Regex("[a-z0-9][a-z0-9-]{0,62}")

internal fun matchesStandaloneAdFreeOffer(
    productId: String,
    basePlanId: String,
    offerId: String?,
    period: String,
    recurring: Boolean,
    priceAmountMicros: Long,
): Boolean = productId == GALLIPICK_STANDALONE_AD_FREE &&
    ((basePlanId == "monthly" && period == "P1M") ||
        (basePlanId == "annual" && period == "P1Y")) &&
    offerId == null && recurring && priceAmountMicros > 0

internal interface CloudBilling {
    fun open(uid: String): CloudBillingSession
}

internal interface CloudBillingSession : Closeable {
    suspend fun loadPlan(): CloudBillingPlan
    suspend fun loadOverviewPlan(): CloudBillingPlan = loadPlan()
    suspend fun load(): CloudBillingSnapshot
    suspend fun recover(): CloudBillingUpdate
    suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate
    suspend fun replace(activity: Activity, offer: CloudBillingReplacementOffer): CloudBillingUpdate =
        CloudBillingUpdate(notice = CloudBillingNotice.UNAVAILABLE)
}

internal data class CloudBillingProduct(val productId: String, val basePlanId: String, val quotaBytes: Long) {
    val isStandaloneAdFree: Boolean get() = productId == GALLIPICK_STANDALONE_AD_FREE
    val billingPeriod: String get() = if (basePlanId == "annual") "P1Y" else "P1M"
}

internal fun productionBillingProduct(productId: String, basePlanId: String): CloudBillingProduct? {
    if (basePlanId != "monthly" && basePlanId != "annual") return null
    val limit = if (productId == GALLIPICK_STANDALONE_AD_FREE) 0L else PRODUCTION_CLOUD_QUOTAS[productId] ?: return null
    return CloudBillingProduct(productId, basePlanId, limit)
}

internal fun isValidCloudBillingCatalog(
    products: List<CloudBillingProduct>,
    production: Boolean = false,
): Boolean =
    products.isNotEmpty() && products.distinctBy { it.productId to it.basePlanId }.size == products.size &&
        products.all { product ->
            CLOUD_PRODUCT_ID.matches(product.productId) && CLOUD_BASE_PLAN_ID.matches(product.basePlanId) &&
                if (product.isStandaloneAdFree) {
                    product.quotaBytes == 0L && product.basePlanId in setOf("monthly", "annual")
                } else if (production) {
                    product.quotaBytes == PRODUCTION_CLOUD_QUOTAS[product.productId] &&
                        product.basePlanId in setOf("monthly", "annual")
                } else {
                    product.quotaBytes > 0L
                }
        }

internal data class CloudBillingPlan(
    val enabled: Boolean = false,
    val products: List<CloudBillingProduct> = emptyList(),
    val activeProductId: String? = null,
    val expiresAtMs: Long? = null,
    val uploadAllowed: Boolean = false,
    val quotaBytes: Long = 0,
    val usedBytes: Long = 0,
    val obfuscatedAccountId: String = "",
    val adFreeProductId: String? = null,
    val adFreeExpiresAtMs: Long? = null,
    val manualGrantKind: String? = null,
    val manualGrantExpiresAtMs: Long? = null,
    val manualGrantQuotaBytes: Long = 0L,
    val manualAdFreeExpiresAtMs: Long? = null,
    val testPurchasesOnly: Boolean = true,
    val backupAvailable: Boolean = false,
    val maxOriginalBytes: Long = 0,
    val backupObjectPrefix: String? = null,
    val cloudCheckoutAllowed: Boolean = false,
    val activeBasePlanId: String? = null,
    val adFreeBasePlanId: String? = null,
    val scheduledReplacement: CloudBillingScheduledReplacement? = null,
    val replacementSourceAllowed: Boolean = false,
    val replacementSourcePrice: CloudBillingReplacementPrice? = null,
    val verifiedMediaCount: Int? = null,
    val supportsResumableTransfers: Boolean = false,
    val resumableUploadAllowed: Boolean = false,
    val legacyRecoveryProducts: List<CloudBillingProduct> = emptyList(),
    val cloudReadAllowed: Boolean? = null,
)

internal fun CloudBillingPlan.productionBackupAccess(): CloudBackupAccess? {
    if (!backupAvailable || maxOriginalBytes !in 1..CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES) return null
    val namespace = if (testPurchasesOnly) CloudBackupNamespace.LICENSE_TEST else CloudBackupNamespace.PRODUCTION
    if (backupObjectPrefix != namespace.objectPrefix) return null
    val paidQuota = PRODUCTION_CLOUD_QUOTAS[activeProductId]
    val paidVerified = activeProductId != null && paidQuota != null &&
        products.any { it.productId == activeProductId && it.quotaBytes == paidQuota }
    val manualQuota = when (manualGrantKind) {
        "CLOUD_10GB" -> 10_000_000_000L
        "CLOUD_50GB" -> 50_000_000_000L
        "CLOUD_100GB" -> 100_000_000_000L
        else -> 0L
    }
    val manualVerified = manualQuota > 0L && manualGrantQuotaBytes == manualQuota &&
        manualGrantExpiresAtMs?.let { it > System.currentTimeMillis() } == true &&
        manualAdFreeExpiresAtMs == manualGrantExpiresAtMs
    val availablePaidQuota = paidQuota.takeIf { paidVerified } ?: 0L
    val effectiveQuota = maxOf(availablePaidQuota, manualQuota.takeIf { manualVerified } ?: 0L)
    val verifiedExpiry = listOfNotNull(
        expiresAtMs.takeIf { paidVerified && paidQuota == effectiveQuota },
        manualGrantExpiresAtMs.takeIf { manualVerified && manualQuota == effectiveQuota },
    ).maxOrNull()
    val eligible = enabled && effectiveQuota > 0L && quotaBytes == effectiveQuota &&
        usedBytes in 0L..quotaBytes
    val uploadExpiryMillis = verifiedExpiry.takeIf {
        eligible && uploadAllowed && usedBytes < quotaBytes
    }
    val canResume = supportsResumableTransfers && eligible && resumableUploadAllowed
    return CloudBackupAccess(
        namespace,
        maxOriginalBytes,
        uploadExpiryMillis,
        supportsResumableTransfers,
        verifiedExpiry.takeIf { canResume },
        quotaBytes.takeIf { canResume } ?: 0L,
        lastKnownRemainingBytes = (quotaBytes - usedBytes).takeIf { eligible },
    )
}

internal fun CloudBillingPlan.developmentBackupAccess(): CloudBackupAccess {
    val canResume = supportsResumableTransfers && resumableUploadAllowed && quotaBytes in 1L..(1024L * 1024L * 1024L) &&
        usedBytes in 0L..quotaBytes
    return CloudBackupAccess.development.copy(
        supportsResumableTransfers = supportsResumableTransfers,
        resumableUploadExpiryMillis = (expiresAtMs ?: Long.MAX_VALUE).takeIf { canResume },
        maximumResumableOriginalBytes = quotaBytes.takeIf { canResume } ?: 0L,
        lastKnownRemainingBytes = (quotaBytes - usedBytes).takeIf { canResume },
    )
}

internal fun CloudBillingPlan.activeCloudProductId(nowMillis: Long): String? = activeProductId?.takeIf { id ->
    enabled && expiresAtMs?.let { it > nowMillis } == true &&
        products.any { it.productId == id && !it.isStandaloneAdFree }
}

internal fun CloudBillingPlan.activeStandaloneAdFreeProductId(nowMillis: Long): String? = adFreeProductId?.takeIf {
    enabled && it == GALLIPICK_STANDALONE_AD_FREE && adFreeExpiresAtMs?.let { expiry -> expiry > nowMillis } == true
}

internal fun CloudBillingPlan.activeSubscriptionProductId(nowMillis: Long): String? =
    activeCloudProductId(nowMillis) ?: activeStandaloneAdFreeProductId(nowMillis)

internal val CloudBillingPlan.hasServerSubscription: Boolean
    get() = activeProductId != null || adFreeProductId != null

internal data class CloudBillingOffer(
    val product: CloudBillingProduct,
    val title: String,
    val formattedPrice: String,
    val priceAmountMicros: Long,
    val currencyCode: String,
)

internal enum class CloudBillingReplacementTiming { IMMEDIATE_PRORATED, NEXT_RENEWAL }

internal data class CloudBillingReplacementOffer(
    val current: CloudBillingProduct,
    val target: CloudBillingOffer,
    val timing: CloudBillingReplacementTiming,
)

internal data class CloudBillingScheduledReplacement(
    val productId: String,
    val basePlanId: String,
    val effectiveAtMs: Long,
)

internal data class CloudBillingReplacementPrice(val amountMicros: Long, val currencyCode: String)

internal fun CloudBillingPlan.currentReplacementProduct(nowMillis: Long): CloudBillingProduct? {
    val cloud = activeCloudProductId(nowMillis)
    val adFree = activeStandaloneAdFreeProductId(nowMillis)
    if ((cloud == null) == (adFree == null)) return null
    val id = cloud ?: checkNotNull(adFree)
    val basePlan = (if (cloud == null) adFreeBasePlanId else activeBasePlanId) ?: return null
    return productionBillingProduct(id, basePlan)?.takeIf { it in products }
}

internal fun CloudBillingPlan.confirmedReplacement(
    productId: String,
    basePlanId: String,
    nowMillis: Long,
): CloudBillingNotice? {
    val target = productionBillingProduct(productId, basePlanId)?.takeIf { enabled && it in products } ?: return null
    val scheduled = scheduledReplacement
    return when {
        scheduled?.productId == target.productId && scheduled.basePlanId == target.basePlanId &&
            scheduled.effectiveAtMs > nowMillis -> CloudBillingNotice.CHANGE_SCHEDULED
        currentReplacementProduct(nowMillis) == target -> CloudBillingNotice.VERIFIED
        else -> null
    }
}

internal fun CloudBillingPlan.replacementOffers(
    offers: List<CloudBillingOffer>,
    nowMillis: Long,
): List<CloudBillingReplacementOffer> {
    if (!cloudCheckoutAllowed || !replacementSourceAllowed || scheduledReplacement != null) return emptyList()
    val current = currentReplacementProduct(nowMillis) ?: return emptyList()
    val currentPrice = replacementSourcePrice ?: return emptyList()
    return offers.mapNotNull { quote ->
        val target = quote.product
        if (target !in products || productionBillingProduct(target.productId, target.basePlanId) != target ||
            current.productId == target.productId || current.basePlanId != target.basePlanId ||
            currentPrice.currencyCode != quote.currencyCode || quote.priceAmountMicros <= 0L ||
            currentPrice.amountMicros <= 0L
        ) return@mapNotNull null
        val timing = when {
            target.quotaBytes > current.quotaBytes && quote.priceAmountMicros > currentPrice.amountMicros ->
                CloudBillingReplacementTiming.IMMEDIATE_PRORATED
            target.quotaBytes < current.quotaBytes && quote.priceAmountMicros < currentPrice.amountMicros ->
                CloudBillingReplacementTiming.NEXT_RENEWAL
            else -> return@mapNotNull null
        }
        CloudBillingReplacementOffer(current, quote, timing)
    }
}

internal data class CloudBillingSnapshot(
    val plan: CloudBillingPlan,
    val offers: List<CloudBillingOffer>,
    val notice: CloudBillingNotice? = null,
    val autoRenewing: Boolean? = null,
    val replacementOffers: List<CloudBillingReplacementOffer> = emptyList(),
)

internal data class CloudBillingUpdate(
    val plan: CloudBillingPlan? = null,
    val notice: CloudBillingNotice,
    val autoRenewing: Boolean? = null,
)

internal enum class CloudBillingNotice {
    VERIFIED, PENDING, CANCELLED, NO_PURCHASE, ALREADY_OWNED, PRICE_CHANGED, ACCOUNT_CHANGED,
    PURCHASE_LINKED_TO_ANOTHER_ACCOUNT, ACCOUNT_ACCESS_REQUIRED, UNAVAILABLE, FAILED, CHANGE_SCHEDULED,
}

internal class CloudBillingException(val notice: CloudBillingNotice) : Exception()

internal fun cloudBillingOfferMatches(
    expected: CloudBillingProduct,
    productId: String,
    basePlanId: String,
    offerId: String?,
    billingPeriod: String,
    recurring: Boolean,
    priceAmountMicros: Long,
): Boolean {
    if (expected.productId != productId || expected.basePlanId != basePlanId) return false
    return if (expected.isStandaloneAdFree) {
        expected.quotaBytes == 0L &&
            matchesStandaloneAdFreeOffer(productId, basePlanId, offerId, billingPeriod, recurring, priceAmountMicros)
    } else {
        expected.quotaBytes > 0L && offerId == null && billingPeriod == expected.billingPeriod &&
            recurring && priceAmountMicros > 0
    }
}

internal fun cloudBillingPurchaseMatches(
    expectedAccount: String,
    allowedProducts: List<CloudBillingProduct>,
    playAccount: String?,
    playProducts: List<String>,
): Boolean = expectedAccount.isNotBlank() && playAccount == expectedAccount &&
    playProducts.singleOrNull()?.let { id -> allowedProducts.any { it.productId == id } } == true
