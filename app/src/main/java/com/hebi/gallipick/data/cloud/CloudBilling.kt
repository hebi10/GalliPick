package com.hebi.gallipick.data.cloud

import android.app.Activity
import java.io.Closeable

internal const val GALLIPICK_STANDALONE_AD_FREE = "gallipick_ad_free"
private val PRODUCTION_CLOUD_QUOTAS = mapOf(
    "gallipick_cloud_10gb" to 10_000_000_000L,
    "gallipick_cloud_50gb" to 50_000_000_000L,
    "gallipick_cloud_100gb" to 100_000_000_000L,
)
internal fun isKnownGalliPickSubscription(productId: String): Boolean =
    productId == GALLIPICK_STANDALONE_AD_FREE || productId in PRODUCTION_CLOUD_QUOTAS ||
        productId == "gallipick_cloud_pilot"
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
    suspend fun load(): CloudBillingSnapshot
    suspend fun recover(): CloudBillingUpdate
    suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate
}

internal data class CloudBillingProduct(val productId: String, val basePlanId: String, val quotaBytes: Long) {
    val isStandaloneAdFree: Boolean get() = productId == GALLIPICK_STANDALONE_AD_FREE
    val billingPeriod: String get() = if (basePlanId == "annual") "P1Y" else "P1M"
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
    val testPurchasesOnly: Boolean = true,
    val backupAvailable: Boolean = false,
    val maxOriginalBytes: Long = 0,
    val backupObjectPrefix: String? = null,
    val cloudCheckoutAllowed: Boolean = false,
)

internal fun CloudBillingPlan.productionBackupAccess(): CloudBackupAccess? {
    if (!backupAvailable || maxOriginalBytes !in 1..CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES) return null
    val namespace = if (testPurchasesOnly) CloudBackupNamespace.LICENSE_TEST else CloudBackupNamespace.PRODUCTION
    if (backupObjectPrefix != namespace.objectPrefix) return null
    val quota = PRODUCTION_CLOUD_QUOTAS[activeProductId]
    val activeVerified = activeProductId != null &&
        products.any { it.productId == activeProductId && it.quotaBytes == quota }
    val uploadExpiryMillis = expiresAtMs.takeIf {
        enabled && activeVerified && uploadAllowed && quotaBytes == quota && usedBytes in 0L until quotaBytes
    }
    return CloudBackupAccess(namespace, maxOriginalBytes, uploadExpiryMillis)
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

internal data class CloudBillingSnapshot(
    val plan: CloudBillingPlan,
    val offers: List<CloudBillingOffer>,
    val notice: CloudBillingNotice? = null,
    val autoRenewing: Boolean? = null,
)

internal data class CloudBillingUpdate(
    val plan: CloudBillingPlan? = null,
    val notice: CloudBillingNotice,
    val autoRenewing: Boolean? = null,
)

internal enum class CloudBillingNotice {
    VERIFIED, PENDING, CANCELLED, NO_PURCHASE, ALREADY_OWNED, PRICE_CHANGED, ACCOUNT_CHANGED,
    PURCHASE_LINKED_TO_ANOTHER_ACCOUNT, UNAVAILABLE, FAILED,
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
