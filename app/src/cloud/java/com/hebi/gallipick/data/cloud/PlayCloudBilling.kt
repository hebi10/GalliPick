package com.hebi.gallipick.data.cloud

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class PlayCloudBilling(
    context: Context,
    private val currentUid: () -> String?,
    private val tokenFor: suspend (String) -> String,
    private val transport: CloudBackupTransport,
    private val testPurchasesOnly: Boolean,
    private val backupAccess: CloudBackupAccessState? = null,
) : CloudBilling {
    private val appContext = context.applicationContext
    private val purchaseVerification = Mutex()
    private val serverPlanRequests = Mutex()

    override fun open(uid: String): CloudBillingSession = Session(uid)

    private inner class Session(private val uid: String) : CloudBillingSession {
        @Volatile private var closed = false
        @Volatile private var checkout: Channel<PurchaseUpdate>? = null
        private val billing = lazy {
            BillingClient.newBuilder(appContext)
                .setListener { result, purchases -> checkout?.trySend(PurchaseUpdate(result, purchases.orEmpty())) }
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()
        }
        private val client by billing

        override suspend fun loadPlan(): CloudBillingPlan = try {
            serverPlan()
        } catch (error: CloudBackupException) {
            if (!error.permissionDenied) throw error
            ensureCurrentAccount()
            disabledPlan().also { backupAccess?.observe(uid, it) }
        }

        override suspend fun load(): CloudBillingSnapshot = withContext(Dispatchers.Main.immediate) {
            val plan = serverPlan()
            if (!plan.enabled) return@withContext CloudBillingSnapshot(plan, emptyList())
            val update = try {
                queryPurchases(plan)
            } catch (error: CloudBillingException) {
                if (plan.activeSubscriptionProductId(System.currentTimeMillis()) == null || !error.canRetryPlayQuery()) throw error
                return@withContext CloudBillingSnapshot(plan, emptyList())
            }
            val current = update.plan ?: plan
            val offers = if (current.enabled && !current.hasServerSubscription &&
                update.notice == CloudBillingNotice.NO_PURCHASE) {
                loadOffers(current).map { it.quote }
            } else emptyList()
            val notice = update.notice.takeUnless { it == CloudBillingNotice.VERIFIED || it == CloudBillingNotice.NO_PURCHASE }
            CloudBillingSnapshot(current, offers, notice, update.autoRenewing)
        }

        override suspend fun recover(): CloudBillingUpdate = withContext(Dispatchers.Main.immediate) {
            queryPurchases(requireEnabledPlan())
        }

        override suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate =
            withContext(Dispatchers.Main.immediate) {
                val plan = requireEnabledPlan()
                if (plan.hasServerSubscription) {
                    return@withContext CloudBillingUpdate(plan, CloudBillingNotice.ALREADY_OWNED)
                }
                val existing = queryPurchases(plan)
                if (existing.notice != CloudBillingNotice.NO_PURCHASE) return@withContext existing
                val freshOffer = loadOffers(plan).singleOrNull { it.quote.product == offer.product }
                if (freshOffer == null || freshOffer.quote != offer) {
                    return@withContext CloudBillingUpdate(plan, CloudBillingNotice.PRICE_CHANGED)
                }
                if (!testPurchasesOnly) {
                    val refreshed = requireEnabledPlan()
                    if (refreshed.hasServerSubscription) {
                        return@withContext CloudBillingUpdate(refreshed, CloudBillingNotice.ALREADY_OWNED)
                    }
                    if (refreshed.obfuscatedAccountId != plan.obfuscatedAccountId ||
                        refreshed.testPurchasesOnly != plan.testPurchasesOnly ||
                        offer.product !in refreshed.products ||
                        (!offer.product.isStandaloneAdFree && !refreshed.cloudCheckoutAllowed)
                    ) return@withContext CloudBillingUpdate(refreshed, CloudBillingNotice.UNAVAILABLE)
                }
                ensureCurrentAccount()
                if (activity.isFinishing || activity.isDestroyed || checkout != null) unavailable()
                val updates = Channel<PurchaseUpdate>(Channel.CONFLATED)
                val returnedToApp = CompletableDeferred<Unit>()
                val lifecycle = (activity as? LifecycleOwner)?.lifecycle
                var paused = false
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> paused = true
                        Lifecycle.Event.ON_RESUME -> if (paused) returnedToApp.complete(Unit) else Unit
                        else -> Unit
                    }
                }
                checkout = updates
                try {
                    lifecycle?.addObserver(observer)
                    val params = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(freshOffer.params))
                        .setObfuscatedAccountId(plan.obfuscatedAccountId)
                        .build()
                    val launchResult = client.launchBillingFlow(activity, params)
                    if (launchResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                        updates.trySend(PurchaseUpdate(launchResult, emptyList()))
                        returnedToApp.complete(Unit)
                    } else if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        return@withContext CloudBillingUpdate(plan, launchResult.toNotice())
                    }
                    awaitCloudCheckout(
                        plan = plan,
                        callbackUpdates = updates,
                        returnedToApp = returnedToApp,
                        isInForeground = { lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false },
                        resolveUpdate = { update ->
                            ensureCurrentAccount()
                            if (update.result.responseCode == BillingClient.BillingResponseCode.OK) {
                                verifyPurchase(plan, update.purchases)
                            } else {
                                CloudBillingUpdate(plan, update.result.toNotice())
                            }
                        },
                        queryPurchases = { queryCheckoutPurchases(plan) },
                    )
                } finally {
                    lifecycle?.removeObserver(observer)
                    checkout = null
                    updates.cancel()
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

        private fun disabledPlan() = CloudBillingPlan()

        private suspend fun serverPlan(purchaseToken: String? = null): CloudBillingPlan = serverPlanRequests.withLock {
            ensureCurrentAccount()
            val idToken = tokenFor(uid)
            ensureCurrentAccount()
            val response = if (purchaseToken == null) {
                transport.call("getCloudPlan", idToken, JSONObject())
            } else {
                transport.call("verifyCloudSubscription", idToken, JSONObject().put("purchaseToken", purchaseToken))
            }
            ensureCurrentAccount()
            if (response.opt("enabled") != true) return@withLock disabledPlan().also { backupAccess?.observe(uid, it) }
            val expectedId = MediaHasher.sha256(uid.byteInputStream())
            val obfuscatedId = response.optString("obfuscatedAccountId")
            if (obfuscatedId != expectedId) unavailable()
            val entries = response.optJSONArray("products") ?: unavailable()
            if (entries.length() !in 1..MAX_PRODUCTS) unavailable()
            val products = (0 until entries.length()).map { index ->
                val item = entries.optJSONObject(index) ?: unavailable()
                val quota = item.opt("quotaBytes") as? Number ?: unavailable()
                val quotaBytes = quota.toLong()
                if (quota.toDouble() != quotaBytes.toDouble()) unavailable()
                CloudBillingProduct(item.optString("productId"), item.optString("basePlanId"), quotaBytes)
            }
            if (!isValidCloudBillingCatalog(products, production = backupAccess != null)) unavailable()
            val activeProductId = response.opt("activeProductId") as? String
            val activeExpiry = response.optLong("expiresAtMs").takeIf { it > 0 }
            if (activeProductId != null &&
                (activeExpiry == null || products.none { it.productId == activeProductId && !it.isStandaloneAdFree })) {
                unavailable()
            }
            val standalone = response.opt("adFreeProductId") as? String
            if (standalone != null && standalone != GALLIPICK_STANDALONE_AD_FREE) unavailable()
            val standaloneExpiry = response.optLong("adFreeExpiresAtMs").takeIf { it > 0 }
            if (standalone != null && standaloneExpiry == null) unavailable()
            val backupAvailable = response.opt("backupAvailable") == true
            val isTestOnly = response.opt("testPurchasesOnly") as? Boolean
            val maxOriginalBytes = (response.opt("maxOriginalBytes") as? Number)
                ?.let { maximum -> maximum.toLong().takeIf { it.toDouble() == maximum.toDouble() } } ?: 0
            val plan = CloudBillingPlan(
                enabled = true,
                products = products,
                activeProductId = activeProductId,
                expiresAtMs = activeExpiry,
                uploadAllowed = response.opt("uploadAllowed") == true,
                quotaBytes = response.optLong("quotaBytes").coerceAtLeast(0),
                usedBytes = response.optLong("usedBytes").coerceAtLeast(0),
                obfuscatedAccountId = obfuscatedId,
                adFreeProductId = standalone,
                adFreeExpiresAtMs = standaloneExpiry,
                // Older production servers only know the test rollout. A missing mode cannot imply live.
                testPurchasesOnly = testPurchasesOnly || isTestOnly != false,
                backupAvailable = backupAvailable,
                maxOriginalBytes = maxOriginalBytes,
                backupObjectPrefix = response.opt("backupObjectPrefix") as? String,
                cloudCheckoutAllowed = response.opt("cloudCheckoutAllowed") == true,
            )
            if (backupAccess != null && backupAvailable &&
                (isTestOnly == null || plan.productionBackupAccess() == null)) unavailable()
            if (backupAccess != null && plan.cloudCheckoutAllowed && !backupAvailable) unavailable()
            plan.also { backupAccess?.observe(uid, it) }
        }

        private suspend fun queryPurchases(plan: CloudBillingPlan): CloudBillingUpdate =
            verifyPurchase(plan, readPlayPurchases())

        private suspend fun queryCheckoutPurchases(plan: CloudBillingPlan): CloudBillingUpdate? {
            val purchases = try {
                readPlayPurchases()
            } catch (error: CloudBillingException) {
                if (!error.canRetryPlayQuery()) throw error
                return null
            }
            return verifyPurchase(plan, purchases)
        }

        private suspend fun readPlayPurchases(): List<Purchase> {
            connect()
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            val response = awaitPlay<PurchaseUpdate> { complete ->
                client.queryPurchasesAsync(params) { result, purchases -> complete(PurchaseUpdate(result, purchases)) }
            }
            response.result.requireOk()
            return response.purchases
        }

        private suspend fun verifyPurchase(plan: CloudBillingPlan, purchases: List<Purchase>): CloudBillingUpdate {
            ensureCurrentAccount()
            val appSubscriptions = purchases.filter { purchase ->
                purchase.packageName == APP_PACKAGE &&
                    purchase.products.any { productId ->
                        isKnownGalliPickSubscription(productId) || plan.products.any { it.productId == productId }
                    }
            }
            val matching = appSubscriptions.filter {
                cloudBillingPurchaseMatches(plan.obfuscatedAccountId, plan.products,
                    it.accountIdentifiers?.obfuscatedAccountId, it.products)
            }
            val purchased = matching.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .sortedByDescending { it.purchaseTime }
                .distinctBy { it.products.single() }
            if (purchased.isNotEmpty()) {
                return verifyCloudCheckout(plan) {
                    val refreshed = purchaseVerification.withLock {
                        purchased.asReversed().fold(plan) { current, purchase ->
                            if (!current.enabled) current
                            else serverPlan(purchase.purchaseToken.takeIf { it.isNotBlank() } ?: unavailable())
                        }
                    }
                    val notice = if (refreshed.enabled) CloudBillingNotice.VERIFIED else CloudBillingNotice.UNAVAILABLE
                    val activeId = refreshed.activeSubscriptionProductId(System.currentTimeMillis())
                    val autoRenewing = purchased.firstOrNull { it.products.single() == activeId }?.isAutoRenewing
                    CloudBillingUpdate(refreshed, notice, autoRenewing)
                }
            }
            if (matching.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
                return CloudBillingUpdate(plan, CloudBillingNotice.PENDING)
            }
            if (appSubscriptions.any {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED || it.purchaseState == Purchase.PurchaseState.PENDING
                }) {
                if (appSubscriptions.any {
                        it.accountIdentifiers?.obfuscatedAccountId == plan.obfuscatedAccountId &&
                            (it.purchaseState == Purchase.PurchaseState.PURCHASED ||
                                it.purchaseState == Purchase.PurchaseState.PENDING)
                    }) return CloudBillingUpdate(plan, CloudBillingNotice.UNAVAILABLE)
                return CloudBillingUpdate(plan, CloudBillingNotice.PURCHASE_LINKED_TO_ANOTHER_ACCOUNT)
            }
            return CloudBillingUpdate(plan, CloudBillingNotice.NO_PURCHASE)
        }

        private suspend fun loadOffers(plan: CloudBillingPlan): List<PlayOffer> {
            val eligibleProducts = plan.products.filter {
                it.isStandaloneAdFree || backupAccess == null || plan.cloudCheckoutAllowed
            }
            if (eligibleProducts.isEmpty()) return emptyList()
            connect()
            val products = eligibleProducts.distinctBy { it.productId }.map {
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
            return eligibleProducts.mapNotNull { product ->
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

        suspend fun <T> awaitPlay(start: ((T) -> Unit) -> Unit): T = withTimeoutOrNull(PLAY_API_TIMEOUT_MS) {
            suspendCancellableCoroutine<T> { continuation ->
                start { result -> if (continuation.isActive) continuation.resume(result) }
            }
        } ?: unavailable()

        fun unavailable(): Nothing = throw CloudBillingException(CloudBillingNotice.UNAVAILABLE)

        fun CloudBillingException.canRetryPlayQuery(): Boolean =
            notice == CloudBillingNotice.FAILED || notice == CloudBillingNotice.UNAVAILABLE

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
