package com.hebi.gallipick.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudBilling
import com.hebi.gallipick.data.cloud.CloudBillingNotice
import com.hebi.gallipick.data.cloud.CloudBillingOffer
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingProduct
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.CloudBillingSnapshot
import com.hebi.gallipick.data.cloud.CloudBillingUpdate
import com.hebi.gallipick.data.cloud.awaitCloudCheckout
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudCheckoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val useSdkBanner: Boolean
        get() = InstrumentationRegistry.getArguments().getString("liveAdBanner") == "true"

    @Test
    fun homeToGoogleSignInToCheckoutRemovesBannerWithoutManualRecovery() {
        val billing = showJourney()

        if (useSdkBanner) {
            compose.waitUntil(20_000L) {
                compose.onAllNodesWithTag("home-admob-banner").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("home-admob-banner").assertIsDisplayed()
        } else {
            compose.onNodeWithText("광고 표시 테스트").assertIsDisplayed()
        }
        compose.onNodeWithText("광고 제거").performScrollTo().assertIsDisplayed()
        capture(if (useSdkBanner) "06-home-with-sdk-test-ad" else "01-home-with-preview-ad")
        compose.onNodeWithText("광고 제거").performClick()

        compose.onNodeWithText("홈 배너 광고 제거").assertIsDisplayed()
        compose.onNodeWithText("Google로 로그인").assertIsDisplayed()
        capture("02-ad-removal-sign-in")
        compose.onNodeWithText("Google로 로그인").performClick()

        compose.onNodeWithText("신청 가능한 요금제").assertIsDisplayed()
        compose.onNodeWithText("Google Play에서 구독 관리").assertDoesNotExist()
        compose.onNodeWithText("구매 복구").assertExists()
        capture("03-available-plan")
        val recoveryCallsBeforePayment = compose.runOnIdle { billing.recoverCalls }
        compose.onNodeWithText("Google Play로 계속").performScrollTo().performClick()
        compose.onNodeWithText("시험 결제창 열기").performClick()
        compose.onNodeWithText("구독 정보를 확인하는 중입니다.").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(1, billing.checkoutCount)
            assertEquals(0, billing.purchaseQueries)
            billing.returnedToApp.complete(Unit)
        }

        compose.onNodeWithText("홈 배너 광고가 제거됐어요").assertIsDisplayed()
        compose.onNodeWithText("구독 혜택으로 홈 배너 광고가 제거됩니다.").assertExists()
        compose.onNodeWithText("Google Play에서 구독 관리").assertExists()
        compose.onNodeWithText("Google Play로 계속").assertDoesNotExist()
        capture("04-verified-purchase")
        compose.onNodeWithText("홈으로").performClick()
        compose.onNodeWithText("정리 시작하기").assertIsDisplayed()
        compose.onNodeWithText("광고 제거").assertDoesNotExist()
        compose.onNodeWithText("광고 표시 테스트").assertDoesNotExist()
        compose.onNodeWithTag("home-admob-banner").assertDoesNotExist()
        capture("05-home-after-verification")
        compose.runOnIdle {
            assertEquals(1, billing.purchaseQueries)
            assertEquals(recoveryCallsBeforePayment, billing.recoverCalls)
        }
    }

    @Test
    fun firstHomeVisitReconcilesAPlayPurchaseMissingFromTheServer() {
        val billing = FakeBillingSession().apply { purchaseWaitingForRecovery = true }
        showJourney(billing, signedIn = true)
        compose.waitUntil(5_000L) { compose.runOnIdle { billing.recoverCalls > 0 } }
        compose.onNodeWithText("정리 시작하기").assertIsDisplayed()
        compose.onNodeWithText("광고 제거").assertDoesNotExist()
        compose.onNodeWithText("광고 표시 테스트").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, billing.recoverCalls)
            assertEquals(0, billing.checkoutCount)
        }
    }

    @Test
    fun pendingPaymentBlocksNewCheckoutUntilPlayReportsNoPurchase() {
        val billing = FakeBillingSession().apply { returnPendingFromCheckout = true }
        showJourney(billing)
        compose.onNodeWithText("광고 제거").performScrollTo().performClick()
        compose.onNodeWithText("Google로 로그인").performClick()
        compose.onNodeWithText("Google Play로 계속").performScrollTo().performClick()
        compose.onNodeWithText("시험 결제창 열기").performClick()

        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        compose.onNodeWithText("Google Play로 계속").assertDoesNotExist()
        compose.onNodeWithText("홈 배너 광고가 제거됐어요").assertDoesNotExist()
        compose.onNodeWithText("결제 상태 확인").performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        capture("07-payment-pending")

        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText("홈")).performClick()
        compose.onNodeWithText("광고 표시 테스트").assertDoesNotExist()
        compose.onNode(tab and hasText("설정")).performClick()
        compose.onNodeWithText("광고 제거").performScrollTo().performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()

        compose.runOnIdle { billing.pendingPurchase = false }
        compose.onNodeWithText("결제 상태 확인").performClick()
        compose.onNodeWithText("Google Play로 계속").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, billing.checkoutCount) }
    }

    private fun showJourney(
        billing: FakeBillingSession = FakeBillingSession(),
        signedIn: Boolean = false,
    ): FakeBillingSession {
        val identity = CloudAccountIdentity("checkout-test", "테스트 계정", null)
        val backend = object : CloudAccountBackend {
            override val account = MutableStateFlow(if (signedIn) identity else null)
            override suspend fun signIn(googleIdToken: String) {
                assertEquals("local-test-token", googleIdToken)
                account.value = identity
            }
            override fun signOut() { account.value = null }
            override suspend fun clearGoogleState() = Unit
        }
        val connection = CloudAccountConnection(
            session = CloudAccountSession(backend),
            billing = object : CloudBilling {
                override fun open(uid: String): CloudBillingSession = billing
            },
            getGoogleIdToken = { "local-test-token" },
        )
        val activity = compose.activity
        val vm = AppViewModel(activity.application)
        compose.runOnUiThread { activity.enableEdgeToEdge() }
        compose.setContent {
            GalliPickTheme(AppThemeMode.LIGHT) {
                var observation by remember { mutableStateOf<CloudAdPlanObservation?>(null) }
                var billingVisible by remember { mutableStateOf(false) }
                val ads = if (useSdkBanner) {
                    rememberGalliAdsSession(activity, connection, observation, billingVisible)
                } else {
                    GalliAdsSession(
                        previewOnly = true,
                        removal = rememberCloudAdRemovalStatus(connection, observation, billingVisible),
                    )
                }
                GalliPickApp(
                    vm, {}, {}, {}, {},
                    cloudConnection = connection,
                    cloudContent = { route, goHome ->
                        when (route) {
                            CloudRoute.BILLING -> CloudBillingSection(connection, activity, onGoHome = goHome) { uid, plan ->
                                observation = CloudAdPlanObservation(uid, plan)
                            }
                            CloudRoute.BILLING_SIGN_IN, CloudRoute.OVERVIEW -> CloudAccountCard {
                                CloudAccountSettings(connection, activity, forAdRemoval = route == CloudRoute.BILLING_SIGN_IN)
                            }
                            else -> error("Ad journey unexpectedly entered another cloud route")
                        }
                    },
                    homeBanner = { GalliHomeBanner(activity, ads) },
                    adsConfigured = ads.configured || ads.previewOnly,
                    adRemoval = ads.removal,
                    onBillingScreenVisible = { billingVisible = it },
                )
            }
        }
        return billing
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAdJourney") != "true") return
        compose.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val folder = File(compose.activity.cacheDir, "ad-journey-captures").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            screenshot.recycle()
        }
    }

    private class FakeBillingSession : CloudBillingSession {
        private val product = CloudBillingProduct("gallipick_cloud_pilot", "monthly", 1_073_741_824L)
        private var serverPlan = CloudBillingPlan(enabled = true, products = listOf(product))
        val offer = CloudBillingOffer(product, "GalliPick Cloud 1 GiB 테스트", "₩1,000", 1_000_000_000L, "KRW")
        val returnedToApp = CompletableDeferred<Unit>()
        var checkoutCount = 0
            private set
        var purchaseQueries = 0
            private set
        var recoverCalls = 0
            private set
        var purchaseWaitingForRecovery = false
        var returnPendingFromCheckout = false
        var pendingPurchase = false

        override suspend fun loadPlan(): CloudBillingPlan = serverPlan

        override suspend fun load(): CloudBillingSnapshot = CloudBillingSnapshot(
            serverPlan,
            listOf(offer),
            notice = CloudBillingNotice.PENDING.takeIf { pendingPurchase },
        )

        override suspend fun recover(): CloudBillingUpdate {
            recoverCalls++
            if (purchaseWaitingForRecovery) {
                purchaseWaitingForRecovery = false
                return verifyPurchase()
            }
            val notice = if (pendingPurchase) CloudBillingNotice.PENDING else CloudBillingNotice.NO_PURCHASE
            return CloudBillingUpdate(serverPlan, notice)
        }

        override suspend fun purchase(activity: Activity, offer: CloudBillingOffer): CloudBillingUpdate {
            assertEquals(this.offer, offer)
            checkoutCount++
            if (returnPendingFromCheckout) {
                pendingPurchase = true
                return CloudBillingUpdate(serverPlan, CloudBillingNotice.PENDING)
            }
            val callbacks = Channel<Unit>()
            try {
                return awaitCloudCheckout(
                    plan = serverPlan,
                    callbackUpdates = callbacks,
                    returnedToApp = returnedToApp,
                    isInForeground = { returnedToApp.isCompleted },
                    resolveUpdate = { error("Play did not deliver a purchase callback") },
                    queryPurchases = {
                        purchaseQueries++
                        verifyPurchase()
                    },
                )
            } finally {
                callbacks.cancel()
            }
        }

        private fun verifyPurchase(): CloudBillingUpdate {
            serverPlan = serverPlan.copy(
                activeProductId = product.productId,
                expiresAtMs = System.currentTimeMillis() + 60_000L,
                uploadAllowed = true,
                quotaBytes = product.quotaBytes,
            )
            return CloudBillingUpdate(serverPlan, CloudBillingNotice.VERIFIED, autoRenewing = true)
        }

        override fun close() = Unit
    }
}
