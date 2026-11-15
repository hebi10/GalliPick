package com.hebi.gallipick.data.cloud

import android.app.Activity
import java.io.Closeable

internal interface CloudBilling {
    fun open(uid: String): CloudBillingSession
}

internal interface CloudBillingSession : Closeable {
    suspend fun load(): CloudBillingSnapshot
    suspend fun recover(): CloudBillingUpdate
    suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate
}

internal data class CloudBillingProduct(val productId: String, val basePlanId: String, val quotaBytes: Long)

internal data class CloudBillingPlan(
    val enabled: Boolean = false,
    val products: List<CloudBillingProduct> = emptyList(),
    val activeProductId: String? = null,
    val expiresAtMs: Long? = null,
    val uploadAllowed: Boolean = false,
    val quotaBytes: Long = 0,
    val usedBytes: Long = 0,
    val obfuscatedAccountId: String = "",
)

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
    VERIFIED, PENDING, CANCELLED, NO_PURCHASE, ALREADY_OWNED, PRICE_CHANGED, ACCOUNT_CHANGED, UNAVAILABLE, FAILED,
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
): Boolean = expected.productId == productId && expected.basePlanId == basePlanId && offerId == null &&
    billingPeriod == "P1M" && recurring && priceAmountMicros > 0

internal fun cloudBillingPurchaseMatches(
    expectedAccount: String,
    allowedProducts: List<CloudBillingProduct>,
    playAccount: String?,
    playProducts: List<String>,
): Boolean = expectedAccount.isNotBlank() && playAccount == expectedAccount &&
    playProducts.singleOrNull()?.let { id -> allowedProducts.any { it.productId == id } } == true
