package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private const val CHECKOUT_CALLBACK_TIMEOUT_MS = 180_000L
private val checkoutQueryDelaysMs = listOf(0L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L)

internal suspend fun verifyCloudCheckout(
    plan: CloudBillingPlan,
    verify: suspend () -> CloudBillingUpdate,
): CloudBillingUpdate = try {
    verify()
} catch (error: CloudBackupException) {
    when (error.result) {
        CloudBackupResult.NETWORK_ERROR, CloudBackupResult.FAILED -> CloudBillingUpdate(plan, CloudBillingNotice.PENDING)
        else -> throw error
    }
}

internal suspend fun <T : Any> awaitCloudCheckout(
    plan: CloudBillingPlan,
    callbackUpdates: ReceiveChannel<T>,
    returnedToApp: Deferred<Unit>,
    isInForeground: () -> Boolean,
    resolveUpdate: suspend (T) -> CloudBillingUpdate,
    queryPurchases: suspend () -> CloudBillingUpdate?,
): CloudBillingUpdate {
    suspend fun awaitCallbackOrReturn(): T? = select {
        callbackUpdates.onReceive { it }
        returnedToApp.onAwait { null }
    }

    val firstCallback = withTimeoutOrNull(CHECKOUT_CALLBACK_TIMEOUT_MS) { awaitCallbackOrReturn() }
    var lastCallback = firstCallback?.let { resolveUpdate(it) }
    if (lastCallback != null && !lastCallback.needsCheckoutQuery()) return lastCallback

    // The user can still complete a Play sheet that's open past the callback timeout.
    while (!returnedToApp.isCompleted && !isInForeground()) {
        val callback = awaitCallbackOrReturn() ?: break
        lastCallback = resolveUpdate(callback)
        if (!lastCallback.needsCheckoutQuery()) return lastCallback
    }

    for (delayMs in checkoutQueryDelaysMs) {
        val callback = if (delayMs == 0L) callbackUpdates.tryReceive().getOrNull()
            else withTimeoutOrNull(delayMs) { callbackUpdates.receive() }
        if (callback != null) {
            lastCallback = resolveUpdate(callback)
            if (!lastCallback.needsCheckoutQuery()) return lastCallback
        }
        val recovered = queryPurchases()
        if (recovered != null && !recovered.needsCheckoutQuery()) return recovered
    }

    val lastQueuedCallback = callbackUpdates.tryReceive().getOrNull()
    if (lastQueuedCallback != null) {
        lastCallback = resolveUpdate(lastQueuedCallback)
        if (!lastCallback.needsCheckoutQuery()) return lastCallback
    }
    return lastCallback?.takeIf { it.notice == CloudBillingNotice.ALREADY_OWNED }
        ?: CloudBillingUpdate(plan, CloudBillingNotice.PENDING)
}

private fun CloudBillingUpdate.needsCheckoutQuery(): Boolean = when (notice) {
    CloudBillingNotice.PENDING, CloudBillingNotice.NO_PURCHASE, CloudBillingNotice.ALREADY_OWNED -> true
    else -> false
}
