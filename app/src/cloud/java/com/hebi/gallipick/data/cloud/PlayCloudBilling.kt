package com.hebi.gallipick.data.cloud

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
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
    private val checkoutLock = Mutex()

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

        override suspend fun loadPlan(): CloudBillingPlan = loadServerPlan(includeVerifiedCount = false)

        override suspend fun loadOverviewPlan(): CloudBillingPlan =
            loadServerPlan(includeVerifiedCount = backupAccess?.development == false)

        private suspend fun loadServerPlan(includeVerifiedCount: Boolean): CloudBillingPlan = try {
            serverPlan(includeVerifiedCount = includeVerifiedCount)
        } catch (error: CloudBackupException) {
            if (!error.permissionDenied) throw error
            ensureCurrentAccount()
            disabledPlan().also { backupAccess?.observe(uid, it) }
        }

        override suspend fun load(): CloudBillingSnapshot = withContext(Dispatchers.Main.immediate) {
            val plan = serverPlan()
            if (!plan.enabled) return@withContext CloudBillingSnapshot(plan, emptyList())
            val (purchases, update) = try {
                val purchases = readPlayPurchases()
                purchases to verifyPurchase(plan, purchases)
            } catch (error: CloudBillingException) {
                if (plan.activeSubscriptionProductId(System.currentTimeMillis()) == null || !error.canRetryPlayQuery()) throw error
                return@withContext CloudBillingSnapshot(plan, emptyList())
            }
            val current = update.plan ?: plan
            val newPurchase = current.enabled && !current.hasServerSubscription &&
                update.notice == CloudBillingNotice.NO_PURCHASE
            val replacePurchase = current.replacementSourceAllowed && replacementSource(purchases, current) != null
            var offerNotice: CloudBillingNotice? = null
            val offers = if (newPurchase || replacePurchase) {
                try {
                    loadOffers(current).map { it.quote }
                } catch (error: CloudBillingException) {
                    if (!error.canRetryPlayQuery()) throw error
                    offerNotice = error.notice
                    emptyList()
                }
            } else emptyList()
            val notice = update.notice.takeUnless { it == CloudBillingNotice.VERIFIED || it == CloudBillingNotice.NO_PURCHASE }
                ?: offerNotice
            CloudBillingSnapshot(
                plan = current,
                offers = if (newPurchase) offers else emptyList(),
                notice = notice,
                autoRenewing = update.autoRenewing,
                replacementOffers = if (replacePurchase) current.replacementOffers(offers, System.currentTimeMillis()) else emptyList(),
            )
        }

        override suspend fun recover(): CloudBillingUpdate = withContext(Dispatchers.Main.immediate) {
            queryPurchases(requireEnabledPlan())
        }

        override suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate =
            withCheckoutLock {
                val plan = requireEnabledPlan()
                if (plan.hasServerSubscription) {
                    return@withCheckoutLock CloudBillingUpdate(plan, CloudBillingNotice.ALREADY_OWNED)
                }
                val existing = queryPurchases(plan)
                if (existing.notice != CloudBillingNotice.NO_PURCHASE) return@withCheckoutLock existing
                val freshOffer = loadOffers(plan).singleOrNull { it.quote.product == offer.product }
                if (freshOffer == null || freshOffer.quote != offer) {
                    return@withCheckoutLock CloudBillingUpdate(plan, CloudBillingNotice.PRICE_CHANGED)
                }
                if (!testPurchasesOnly) {
                    val refreshed = requireEnabledPlan()
                    if (refreshed.hasServerSubscription) {
                        return@withCheckoutLock CloudBillingUpdate(refreshed, CloudBillingNotice.ALREADY_OWNED)
                    }
                    if (refreshed.obfuscatedAccountId != plan.obfuscatedAccountId ||
                        refreshed.testPurchasesOnly != plan.testPurchasesOnly ||
                        offer.product !in refreshed.products ||
                        (!offer.product.isStandaloneAdFree && !refreshed.cloudCheckoutAllowed)
                    ) return@withCheckoutLock CloudBillingUpdate(refreshed, CloudBillingNotice.UNAVAILABLE)
                }
                launchCheckout(activity, plan, freshOffer)
            }

        override suspend fun replace(activity: Activity, offer: CloudBillingReplacementOffer): CloudBillingUpdate =
            withCheckoutLock {
                val plan = requireEnabledPlan()
                if (backupAccess?.development != false || !plan.cloudCheckoutAllowed || plan.scheduledReplacement != null ||
                    plan.currentReplacementProduct(System.currentTimeMillis()) != offer.current
                ) return@withCheckoutLock CloudBillingUpdate(plan, CloudBillingNotice.UNAVAILABLE)

                val freshOffer = loadOffers(plan).singleOrNull { it.quote.product == offer.target.product }
                if (freshOffer == null || freshOffer.quote != offer.target) {
                    return@withCheckoutLock CloudBillingUpdate(plan, CloudBillingNotice.PRICE_CHANGED)
                }
                val source = replacementSource(readPlayPurchases(), plan)
                    ?: return@withCheckoutLock CloudBillingUpdate(plan, CloudBillingNotice.UNAVAILABLE)
                val verified = serverPlan(source.purchaseToken)
                if (verified.testPurchasesOnly != plan.testPurchasesOnly ||
                    verified.replacementOffers(listOf(freshOffer.quote), System.currentTimeMillis()).singleOrNull() != offer
                ) return@withCheckoutLock CloudBillingUpdate(verified, CloudBillingNotice.UNAVAILABLE)
                launchCheckout(activity, verified, freshOffer, ReplacementCheckout(source.purchaseToken, offer))
            }

        private suspend fun withCheckoutLock(block: suspend () -> CloudBillingUpdate): CloudBillingUpdate =
            withContext(Dispatchers.Main.immediate) {
                if (!checkoutLock.tryLock()) unavailable()
                try {
                    block()
                } finally {
                    checkoutLock.unlock()
                }
            }

        private suspend fun launchCheckout(
            activity: Activity,
            plan: CloudBillingPlan,
            offer: PlayOffer,
            replacement: ReplacementCheckout? = null,
        ): CloudBillingUpdate {
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
                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(offer.details)
                    .setOfferToken(offer.offerToken)
                val params = BillingFlowParams.newBuilder().setObfuscatedAccountId(plan.obfuscatedAccountId)
                if (replacement != null) {
                    productParams.setSubscriptionProductReplacementParams(SubscriptionProductReplacementParams.newBuilder()
                        .setOldProductId(replacement.offer.current.productId)
                        .setReplacementMode(when (replacement.offer.timing) {
                            CloudBillingReplacementTiming.IMMEDIATE_PRORATED ->
                                SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE
                            CloudBillingReplacementTiming.NEXT_RENEWAL -> SubscriptionProductReplacementParams.ReplacementMode.DEFERRED
                        })
                        .build())
                    params.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(replacement.oldToken).build())
                }
                val launchResult = client.launchBillingFlow(activity,
                    params.setProductDetailsParamsList(listOf(productParams.build())).build())
                if (launchResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                    updates.trySend(PurchaseUpdate(launchResult, emptyList()))
                    returnedToApp.complete(Unit)
                } else if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    return CloudBillingUpdate(plan, launchResult.toNotice())
                }
                return awaitCloudCheckout(
                    plan = plan,
                    callbackUpdates = updates,
                    returnedToApp = returnedToApp,
                    isInForeground = { lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false },
                    resolveUpdate = { update ->
                        ensureCurrentAccount()
                        if (update.result.responseCode == BillingClient.BillingResponseCode.OK) {
                            verifyCheckoutPurchase(plan, update.purchases, replacement)
                        } else CloudBillingUpdate(plan, update.result.toNotice())
                    },
                    queryPurchases = { queryCheckoutPurchases(plan, replacement) },
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

        private suspend fun serverPlan(
            purchaseToken: String? = null,
            includeVerifiedCount: Boolean = false,
        ): CloudBillingPlan = serverPlanRequests.withLock {
            ensureCurrentAccount()
            val idToken = tokenFor(uid)
            ensureCurrentAccount()
            val input = JSONObject().put("acceptsResumableTransfers", true)
            val response = if (purchaseToken == null) {
                if (includeVerifiedCount) input.put("includeVerifiedCount", true)
                transport.call("getCloudPlan", idToken, input)
            } else {
                transport.call("verifyCloudSubscription", idToken, input.put("purchaseToken", purchaseToken))
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
            val production = backupAccess?.development == false
            if (!isValidCloudBillingCatalog(products, production = production)) unavailable()
            val activeProductId = response.opt("activeProductId") as? String
            val activeBasePlanId = response.opt("activeBasePlanId") as? String
            val activeExpiry = response.optLong("expiresAtMs").takeIf { it > 0 }
            if (activeProductId != null &&
                (activeExpiry == null || products.none { it.productId == activeProductId && !it.isStandaloneAdFree })) {
                unavailable()
            }
            val standalone = response.opt("adFreeProductId") as? String
            val standaloneBasePlanId = response.opt("adFreeBasePlanId") as? String
            if (standalone != null && standalone != GALLIPICK_STANDALONE_AD_FREE) unavailable()
            val standaloneExpiry = response.optLong("adFreeExpiresAtMs").takeIf { it > 0 }
            if (standalone != null && standaloneExpiry == null) unavailable()
            val manualKind = response.opt("manualGrantKind") as? String
            val manualExpires = response.optLong("manualGrantExpiresAtMs").takeIf { it > 0L }
            val manualQuotaNumber = response.opt("manualGrantQuotaBytes") as? Number
            val manualQuota = manualQuotaNumber?.toLong() ?: 0L
            val manualAdFreeExpiry = response.optLong("manualAdFreeExpiresAtMs").takeIf { it > 0L }
            if (manualKind != null) {
                val expectedQuota = when (manualKind) {
                    "CLOUD_10GB" -> 10_000_000_000L
                    "CLOUD_50GB" -> 50_000_000_000L
                    "CLOUD_100GB" -> 100_000_000_000L
                    "AD_FREE" -> 0L
                    else -> unavailable()
                }
                if (manualExpires == null || manualAdFreeExpiry != manualExpires ||
                    manualQuota != expectedQuota ||
                    manualQuotaNumber?.toDouble() != manualQuota.toDouble() ||
                    !production) unavailable()
            } else if (manualExpires != null || manualAdFreeExpiry != null || manualQuotaNumber != null) {
                unavailable()
            }
            val backupAvailable = response.opt("backupAvailable") == true
            val isTestOnly = response.opt("testPurchasesOnly") as? Boolean
            val maxOriginalBytes = (response.opt("maxOriginalBytes") as? Number)
                ?.let { maximum -> maximum.toLong().takeIf { it.toDouble() == maximum.toDouble() } } ?: 0
            val scheduled = response.opt("scheduledReplacement").takeUnless { it == JSONObject.NULL }?.let { raw ->
                val change = raw as? JSONObject ?: unavailable()
                val productId = change.opt("productId") as? String ?: unavailable()
                val basePlanId = change.opt("basePlanId") as? String ?: unavailable()
                val effectiveAt = change.positiveSafeLong("effectiveAtMs") ?: unavailable()
                if (productionBillingProduct(productId, basePlanId) == null) unavailable()
                CloudBillingScheduledReplacement(productId, basePlanId, effectiveAt)
            }
            val replacementSourceAllowed = purchaseToken != null && production &&
                response.opt("replacementSourceAllowed") == true
            val sourcePrice = response.optJSONObject("replacementSourcePrice")?.takeIf { replacementSourceAllowed }?.let { raw ->
                val amount = raw.positiveSafeLong("amountMicros") ?: return@let null
                val currency = raw.opt("currencyCode") as? String ?: return@let null
                if (!CURRENCY_CODE.matches(currency)) return@let null
                CloudBillingReplacementPrice(amount, currency)
            }
            val verifiedMediaCount = (response.opt("verifiedMediaCount") as? Number)
                ?.takeIf { includeVerifiedCount }?.let { count ->
                    count.toInt().takeIf { it in 0 until MAX_VERIFIED_MEDIA_COUNT && it.toDouble() == count.toDouble() }
                }
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
                manualGrantKind = manualKind,
                manualGrantExpiresAtMs = manualExpires,
                manualGrantQuotaBytes = manualQuota,
                manualAdFreeExpiresAtMs = manualAdFreeExpiry,
                // Older production servers only know the test rollout. A missing mode cannot imply live.
                testPurchasesOnly = testPurchasesOnly || isTestOnly != false,
                backupAvailable = backupAvailable,
                maxOriginalBytes = maxOriginalBytes,
                backupObjectPrefix = response.opt("backupObjectPrefix") as? String,
                cloudCheckoutAllowed = response.opt("cloudCheckoutAllowed") == true,
                activeBasePlanId = activeBasePlanId,
                adFreeBasePlanId = standaloneBasePlanId,
                scheduledReplacement = scheduled,
                replacementSourceAllowed = replacementSourceAllowed,
                replacementSourcePrice = sourcePrice,
                verifiedMediaCount = verifiedMediaCount,
                supportsResumableTransfers = response.opt("supportsResumableTransfers") == true,
                resumableUploadAllowed = response.opt("resumableUploadAllowed") == true,
            )
            if (production && backupAvailable &&
                (isTestOnly == null || plan.productionBackupAccess() == null)) unavailable()
            if (production && plan.cloudCheckoutAllowed && !backupAvailable) unavailable()
            plan.also { backupAccess?.observe(uid, it) }
        }

        private suspend fun queryPurchases(plan: CloudBillingPlan): CloudBillingUpdate =
            verifyPurchase(plan, readPlayPurchases())

        private suspend fun queryCheckoutPurchases(
            plan: CloudBillingPlan,
            replacement: ReplacementCheckout?,
        ): CloudBillingUpdate? {
            val purchases = try {
                readPlayPurchases()
            } catch (error: CloudBillingException) {
                if (!error.canRetryPlayQuery()) throw error
                return null
            }
            return verifyCheckoutPurchase(plan, purchases, replacement)
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

        private fun replacementSource(purchases: List<Purchase>, plan: CloudBillingPlan): Purchase? {
            val current = plan.currentReplacementProduct(System.currentTimeMillis()) ?: return null
            return purchases.filter { it.packageName == APP_PACKAGE }.singleOrNull()?.takeIf { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED && purchase.isAcknowledged &&
                    purchase.isAutoRenewing && !purchase.isSuspended && purchase.pendingPurchaseUpdate == null &&
                    purchase.purchaseToken.isNotBlank() &&
                    purchase.accountIdentifiers?.obfuscatedAccountId == plan.obfuscatedAccountId &&
                    purchase.products.singleOrNull() == current.productId
            }
        }

        private suspend fun verifyCheckoutPurchase(
            plan: CloudBillingPlan,
            purchases: List<Purchase>,
            replacement: ReplacementCheckout?,
        ): CloudBillingUpdate {
            if (replacement == null) return verifyPurchase(plan, purchases)
            ensureCurrentAccount()
            val target = replacement.offer.target.product
            val next = purchases.filter { it.packageName == APP_PACKAGE && it.purchaseToken != replacement.oldToken }
                .singleOrNull()?.takeIf { purchase ->
                    purchase.purchaseToken.isNotBlank() &&
                        purchase.accountIdentifiers?.obfuscatedAccountId == plan.obfuscatedAccountId &&
                        (purchase.products.singleOrNull() == target.productId ||
                            (replacement.offer.timing == CloudBillingReplacementTiming.NEXT_RENEWAL &&
                                purchase.products.singleOrNull() == replacement.offer.current.productId))
                }
            if (next?.purchaseState != Purchase.PurchaseState.PURCHASED) {
                return CloudBillingUpdate(plan, CloudBillingNotice.PENDING)
            }
            return verifyCloudCheckout(plan) {
                val refreshed = purchaseVerification.withLock { serverPlan(next.purchaseToken) }
                if (refreshed.testPurchasesOnly != plan.testPurchasesOnly || !refreshed.enabled) {
                    return@verifyCloudCheckout CloudBillingUpdate(refreshed, CloudBillingNotice.UNAVAILABLE)
                }
                val notice = refreshed.confirmedReplacement(target.productId, target.basePlanId, System.currentTimeMillis())
                    ?: CloudBillingNotice.PENDING
                CloudBillingUpdate(refreshed, notice, next.isAutoRenewing)
            }
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
                it.productId != GALLIPICK_RETIRED_CLOUD_PILOT &&
                    (it.isStandaloneAdFree || backupAccess?.development != false || plan.cloudCheckoutAllowed)
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
                    detail,
                    offer.offerToken,
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

    private data class PlayOffer(val quote: CloudBillingOffer, val details: ProductDetails, val offerToken: String)
    private class ReplacementCheckout(val oldToken: String, val offer: CloudBillingReplacementOffer)
    private data class PurchaseUpdate(val result: BillingResult, val purchases: List<Purchase>)

    private companion object {
        const val APP_PACKAGE = "com.hebi.gallipick"
        const val MAX_PRODUCTS = 12
        const val MAX_VERIFIED_MEDIA_COUNT = 20_000
        const val PLAY_API_TIMEOUT_MS = 15_000L
        val CURRENCY_CODE = Regex("[A-Z]{3}")

        fun JSONObject.positiveSafeLong(key: String): Long? {
            val number = opt(key) as? Number ?: return null
            val value = number.toLong()
            return value.takeIf { it in 1..9_007_199_254_740_991L && it.toDouble() == number.toDouble() }
        }

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
