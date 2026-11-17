package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudCheckoutTest {
    private val product = CloudBillingProduct("cloud-test", "monthly", 1_073_741_824)
    private val plan = CloudBillingPlan(enabled = true, products = listOf(product))
    private val verified = CloudBillingUpdate(
        plan.copy(activeProductId = product.productId, expiresAtMs = 240_000),
        CloudBillingNotice.VERIFIED,
    )

    @Test fun returnFromCheckoutRecoversWithoutACallback() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        val resumed = CompletableDeferred<Unit>()
        val result = async { awaitCloudCheckout(plan, updates, resumed, { true }, { it }, { verified }) }

        runCurrent()
        assertFalse(result.isCompleted)
        resumed.complete(Unit)

        assertEquals(verified, result.await())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun checkoutCanReturnAfterTheCallbackTimeout() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        val resumed = CompletableDeferred<Unit>()
        var inForeground = false
        val result = async {
            awaitCloudCheckout(plan, updates, resumed, { inForeground }, { it }, { verified })
        }

        advanceTimeBy(200_000)
        assertFalse(result.isCompleted)
        inForeground = true
        resumed.complete(Unit)

        assertEquals(verified, result.await())
    }

    @Test fun pendingCallbackIsFollowedByAutomaticPurchaseVerification() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        updates.send(CloudBillingUpdate(plan, CloudBillingNotice.PENDING))
        val resumed = CompletableDeferred(Unit)
        val queries = listOf(CloudBillingUpdate(plan, CloudBillingNotice.NO_PURCHASE), verified).iterator()

        val result = awaitCloudCheckout(plan, updates, resumed, { true }, { it }, { queries.next() })

        assertEquals(verified, result)
    }

    @Test fun successCallbackCanArriveAfterTheEarlyResumeQueries() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        val resumed = CompletableDeferred(Unit)
        launch {
            delay(12_000)
            updates.send(verified)
        }

        val result = awaitCloudCheckout(plan, updates, resumed, { true }, { it }) {
            CloudBillingUpdate(plan, CloudBillingNotice.NO_PURCHASE)
        }

        assertEquals(verified, result)
    }

    @Test fun failedPlayQueryDoesNotDiscardACallbackReceivedDuringTheQuery() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        val resumed = CompletableDeferred(Unit)
        var queryCount = 0

        val result = awaitCloudCheckout(plan, updates, resumed, { true }, { it }) {
            queryCount++
            delay(1_000)
            updates.send(verified)
            null
        }

        assertEquals(verified, result)
        assertEquals(1, queryCount)
    }

    @Test fun aCallbackDuringTheLastPlayQueryIsHandledBeforeReturningPending() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        val resumed = CompletableDeferred(Unit)

        val result = awaitCloudCheckout(plan, updates, resumed, { true }, { it }) {
            if (testScheduler.currentTime >= 60_000L) updates.send(verified)
            CloudBillingUpdate(plan, CloudBillingNotice.NO_PURCHASE)
        }

        assertEquals(verified, result)
    }

    @Test fun successfulCallbackDoesNotVerifyTheSamePurchaseThroughTheResumeQuery() = runTest {
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        updates.send(verified)
        val resumed = CompletableDeferred(Unit)

        val result = awaitCloudCheckout(plan, updates, resumed, { true }, { it }) {
            error("Checkout callback already verified the purchase")
        }

        assertEquals(verified, result)
    }

    @Test fun temporaryServerFailuresAfterPlayApprovalAreRetried() = runTest {
        val updates = Channel<Unit>(Channel.CONFLATED)
        updates.send(Unit)
        val resumed = CompletableDeferred(Unit)
        var queryCount = 0

        val result = awaitCloudCheckout(
            plan,
            updates,
            resumed,
            { true },
            { verifyCloudCheckout(plan) { throw CloudBackupException(CloudBackupResult.NETWORK_ERROR) } },
        ) {
            queryCount++
            verifyCloudCheckout(plan) {
                if (queryCount == 1) throw CloudBackupException(CloudBackupResult.FAILED)
                verified
            }
        }

        assertEquals(verified, result)
        assertEquals(2, queryCount)
    }

    @Test fun accountAccessFailureAfterPlayApprovalIsNotRetried() = runTest {
        val updates = Channel<Unit>(Channel.CONFLATED)
        updates.send(Unit)
        val resumed = CompletableDeferred(Unit)
        val denied = CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)

        try {
            awaitCloudCheckout(plan, updates, resumed, { true }, { verifyCloudCheckout(plan) { throw denied } }) {
                error("Unverified account must not trigger a purchase query")
            }
            error("Account access failure was hidden")
        } catch (error: CloudBackupException) {
            assertSame(denied, error)
        }
    }

    @Test fun cancellationFromPlayStopsAutomaticQueries() = runTest {
        val cancelled = CloudBillingUpdate(plan, CloudBillingNotice.CANCELLED)
        val updates = Channel<CloudBillingUpdate>(Channel.CONFLATED)
        updates.send(cancelled)
        val resumed = CompletableDeferred(Unit)

        val result = awaitCloudCheckout(plan, updates, resumed, { true }, { it }) {
            error("A cancelled checkout must not restart the purchase check")
        }

        assertEquals(cancelled, result)
    }
}
