package com.hebi.gallipick.data.cloud

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.hebi.gallipick.domain.MediaHasher
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class PlayCloudBilling(
    context: Context,
    private val currentUid: () -> String?,
    private val tokenFor: suspend (String) -> String,
    private val transport: CloudBackupTransport,
) : CloudBilling {
    private val appContext = context.applicationContext

    override fun open(uid: String): CloudBillingSession = Session(uid)

    private inner class Session(private val uid: String) : CloudBillingSession {
        @Volatile private var closed = false
        @Volatile private var checkout: CompletableDeferred<PurchaseUpdate>? = null
        private val billing = lazy {
            BillingClient.newBuilder(appContext)
                .setListener { result, purchases -> checkout?.complete(PurchaseUpdate(result, purchases.orEmpty())) }
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()
        }
        private val client by billing

        override suspend fun loadPlan(): CloudBillingPlan = serverPlan()

        override suspend fun load(): CloudBillingSnapshot = withContext(Dispatchers.Main.immediate) {
            val plan = serverPlan()
            if (!plan.enabled) return@withContext CloudBillingSnapshot(plan, emptyList())
            val update = queryPurchases(plan)
            val current = update.plan ?: plan
            val offers = if (current.enabled && current.activeProductId == null) loadOffers(current).map { it.quote } else emptyList()
            CloudBillingSnapshot(current, offers, update.notice.takeIf { it == CloudBillingNotice.PENDING }, update.autoRenewing)
        }

        override suspend fun recover(): CloudBillingUpdate = withContext(Dispatchers.Main.immediate) {
            queryPurchases(requireEnabledPlan())
        }

        override suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate =
            withContext(Dispatchers.Main.immediate) {
                val plan = requireEnabledPlan()
                if (plan.activeProductId != null) return@withContext CloudBillingUpdate(plan, CloudBillingNotice.ALREADY_OWNED)
                val existing = queryPurchases(plan)
                if (existing.notice != CloudBillingNotice.NO_PURCHASE) return@withContext existing
                val freshOffer = loadOffers(plan).singleOrNull { it.quote.product == offer.product }
                if (freshOffer == null || freshOffer.quote != offer) {
                    return@withContext CloudBillingUpdate(plan, CloudBillingNotice.PRICE_CHANGED)
                }
                ensureCurrentAccount()
                if (activity.isFinishing || activity.isDestroyed || checkout != null) unavailable()
                val listener = CompletableDeferred<PurchaseUpdate>()
                checkout = listener
                try {
                    val params = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(freshOffer.params))
                        .setObfuscatedAccountId(plan.obfuscatedAccountId)
                        .build()
                    val launchResult = client.launchBillingFlow(activity, params)
                    if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        return@withContext CloudBillingUpdate(plan, launchResult.toNotice())
                    }
                    val update = withTimeoutOrNull(CHECKOUT_TIMEOUT_MS) { listener.await() }
                        ?: return@withContext CloudBillingUpdate(plan, CloudBillingNotice.PENDING)
                    ensureCurrentAccount()
                    if (update.result.responseCode == BillingClient.BillingResponseCode.OK) {
                        verifyPurchase(plan, update.purchases)
                    } else {
                        CloudBillingUpdate(plan, update.result.toNotice())
                    }
                } finally {
                    checkout = null
                    listener.cancel()
                }
            }

        override fun close() {
            if (closed) return
            closed = true
            checkout?.cancel()
            if (billing.isInitialized()) client.endConnection()
        }

        private suspend fun requireEnabledPlan(): CloudBillingPlan = serverPlan().also {
            if (!it.enabled) unavailable()
        }

        private suspend fun serverPlan(purchaseToken: String? = null): CloudBillingPlan {
            ensureCurrentAccount()
            val idToken = tokenFor(uid)
            ensureCurrentAccount()
            val response = if (purchaseToken == null) {
                transport.call("getCloudPlan", idToken, JSONObject())
            } else {
                transport.call("verifyCloudSubscription", idToken, JSONObject().put("purchaseToken", purchaseToken))
            }
            ensureCurrentAccount()
            if (response.opt("enabled") != true) return CloudBillingPlan()
            val expectedId = MediaHasher.sha256(uid.byteInputStream())
            val obfuscatedId = response.optString("obfuscatedAccountId")
            if (obfuscatedId != expectedId) unavailable()
            val entries = response.optJSONArray("products") ?: unavailable()
            if (entries.length() !in 1..MAX_PRODUCTS) unavailable()
            val products = (0 until entries.length()).map { index ->
                val item = entries.optJSONObject(index) ?: unavailable()
                val product = CloudBillingProduct(item.optString("productId"), item.optString("basePlanId"), item.optLong("quotaBytes"))
                if (!PLAN_ID.matches(product.productId) || !PLAN_ID.matches(product.basePlanId) || product.quotaBytes <= 0) {
                    unavailable()
                }
                product
            }
            if (products.distinctBy { it.productId }.size != products.size) unavailable()
            val activeProductId = response.opt("activeProductId") as? String
            if (activeProductId != null && products.none { it.productId == activeProductId }) unavailable()
            return CloudBillingPlan(
                enabled = true,
                products = products,
                activeProductId = activeProductId,
                expiresAtMs = response.optLong("expiresAtMs").takeIf { it > 0 },
                uploadAllowed = response.opt("uploadAllowed") == true,
                quotaBytes = response.optLong("quotaBytes").coerceAtLeast(0),
                usedBytes = response.optLong("usedBytes").coerceAtLeast(0),
                obfuscatedAccountId = obfuscatedId,
            )
        }

        private suspend fun queryPurchases(plan: CloudBillingPlan): CloudBillingUpdate {
            connect()
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            val response = awaitPlay<PurchaseUpdate> { complete ->
                client.queryPurchasesAsync(params) { result, purchases -> complete(PurchaseUpdate(result, purchases)) }
            }
            response.result.requireOk()
            return verifyPurchase(plan, response.purchases)
        }

        private suspend fun verifyPurchase(plan: CloudBillingPlan, purchases: List<Purchase>): CloudBillingUpdate {
            ensureCurrentAccount()
            val matching = purchases.filter { purchase ->
                purchase.packageName == APP_PACKAGE && cloudBillingPurchaseMatches(
                    plan.obfuscatedAccountId, plan.products, purchase.accountIdentifiers?.obfuscatedAccountId, purchase.products,
                )
            }
            val current = matching.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .maxByOrNull { it.purchaseTime }
            if (current != null) {
                val refreshed = serverPlan(current.purchaseToken.takeIf { it.isNotBlank() } ?: unavailable())
                val notice = if (refreshed.enabled) CloudBillingNotice.VERIFIED else CloudBillingNotice.UNAVAILABLE
                val autoRenewing = current.isAutoRenewing.takeIf { refreshed.activeProductId == current.products.single() }
                return CloudBillingUpdate(refreshed, notice, autoRenewing)
            }
            if (matching.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                return CloudBillingUpdate(plan, CloudBillingNotice.PENDING)
            }
            return CloudBillingUpdate(plan, CloudBillingNotice.NO_PURCHASE)
        }

        private suspend fun loadOffers(plan: CloudBillingPlan): List<PlayOffer> {
            connect()
            val products = plan.products.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it.productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
            val (result, details) = awaitPlay<Pair<BillingResult, List<ProductDetails>>> { complete ->
                client.queryProductDetailsAsync(params) { response, query -> complete(response to query.productDetailsList) }
            }
            result.requireOk()
            ensureCurrentAccount()
            return plan.products.mapNotNull { product ->
                val detail = details.singleOrNull { it.productId == product.productId && it.productType == BillingClient.ProductType.SUBS }
                    ?: return@mapNotNull null
                val offer = detail.subscriptionOfferDetails.orEmpty().singleOrNull { candidate ->
                    val phase = candidate.pricingPhases.pricingPhaseList.singleOrNull() ?: return@singleOrNull false
                    candidate.installmentPlanDetails == null && cloudBillingOfferMatches(
                        product, detail.productId, candidate.basePlanId, candidate.offerId, phase.billingPeriod,
                        phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING, phase.priceAmountMicros,
                    )
                } ?: return@mapNotNull null
                val price = offer.pricingPhases.pricingPhaseList.single()
                if (price.formattedPrice.isBlank() || price.priceCurrencyCode.isBlank() || offer.offerToken.isBlank()) {
                    return@mapNotNull null
                }
                PlayOffer(
                    CloudBillingOffer(product, detail.name, price.formattedPrice, price.priceAmountMicros, price.priceCurrencyCode),
                    BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detail).setOfferToken(offer.offerToken).build(),
                )
            }
        }

        private suspend fun connect() {
            ensureCurrentAccount()
            if (client.isReady) return
            awaitPlay<BillingResult> { complete ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) = complete(result)
                    override fun onBillingServiceDisconnected() = Unit
                })
            }.requireOk()
            ensureCurrentAccount()
        }

        private suspend fun ensureCurrentAccount() {
            currentCoroutineContext().ensureActive()
            if (closed || currentUid() != uid) throw CloudBillingException(CloudBillingNotice.ACCOUNT_CHANGED)
        }
    }

    private data class PlayOffer(val quote: CloudBillingOffer, val params: BillingFlowParams.ProductDetailsParams)
    private data class PurchaseUpdate(val result: BillingResult, val purchases: List<Purchase>)

    private companion object {
        const val APP_PACKAGE = "com.hebi.gallipick"
        const val MAX_PRODUCTS = 12
        const val PLAY_API_TIMEOUT_MS = 15_000L
        const val CHECKOUT_TIMEOUT_MS = 180_000L
        val PLAN_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        suspend fun <T> awaitPlay(start: ((T) -> Unit) -> Unit): T = withTimeoutOrNull(PLAY_API_TIMEOUT_MS) {
            suspendCancellableCoroutine<T> { continuation ->
                start { result -> if (continuation.isActive) continuation.resume(result) }
            }
        } ?: unavailable()

        fun unavailable(): Nothing = throw CloudBillingException(CloudBillingNotice.UNAVAILABLE)

        fun BillingResult.requireOk() {
            if (responseCode != BillingClient.BillingResponseCode.OK) throw CloudBillingException(toNotice())
        }

        fun BillingResult.toNotice(): CloudBillingNotice = when (responseCode) {
            BillingClient.BillingResponseCode.USER_CANCELED -> CloudBillingNotice.CANCELLED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> CloudBillingNotice.ALREADY_OWNED
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> CloudBillingNotice.UNAVAILABLE
            else -> CloudBillingNotice.FAILED
        }
    }
}
