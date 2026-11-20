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
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.hebi.gallipick.data.cloud.CloudBillingReplacementOffer
import com.hebi.gallipick.data.cloud.CloudBillingReplacementTiming
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
            compose.onNodeWithText("광고").assertIsDisplayed()
        }
        compose.onNodeWithText("광고 제거").performScrollTo().assertIsDisplayed()
        capture(if (useSdkBanner) "06-home-with-sdk-test-ad" else "01-home-with-preview-ad")
        compose.onNodeWithText("광고 제거").performClick()

        compose.onNodeWithText("홈 배너 광고 제거").assertIsDisplayed()
        compose.onNodeWithText("Google로 로그인").assertIsDisplayed()
        capture("02-ad-removal-sign-in")
        compose.onNodeWithText("Google로 로그인").performClick()

        compose.onNodeWithText("클라우드 요금제").assertIsDisplayed()
        compose.onNodeWithText("GalliPick Cloud 1 GiB 테스트").assertDoesNotExist()
        compose.onNodeWithText("Google Play에서 구독 관리").assertDoesNotExist()
        compose.onNodeWithText("구매 복구").assertExists()
        capture("03-available-plan")
        val recoveryCallsBeforePayment = compose.runOnIdle { billing.recoverCalls }
        selectMonthlyTenGbAndOpenQuote()
        compose.onNodeWithText("Google Play 결제창 열기").performClick()
        compose.onNodeWithText("구독 정보를 확인하는 중입니다.").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(1, billing.checkoutCount)
            assertEquals(0, billing.purchaseQueries)
            billing.returnedToApp.complete(Unit)
        }

        compose.onNodeWithText("홈 배너 광고가 제거됐어요").assertIsDisplayed()
        compose.onNodeWithText("구독 혜택으로 홈 배너 광고가 제거됩니다.").assertExists()
        compose.onNodeWithText("Google Play에서 구독 관리").assertExists()
        billingCta().assertDoesNotExist()
        capture("04-verified-purchase")
        compose.onNodeWithText("홈으로").performClick()
        compose.onNodeWithText("정리 시작하기").assertIsDisplayed()
        compose.onNodeWithText("광고 제거").assertDoesNotExist()
        compose.onNodeWithText("광고").assertDoesNotExist()
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
        compose.onNodeWithText("광고").assertDoesNotExist()
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
        selectMonthlyTenGbAndOpenQuote()
        compose.onNodeWithText("Google Play 결제창 열기").performClick()

        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        billingCta().assertDoesNotExist()
        compose.onNodeWithText("홈 배너 광고가 제거됐어요").assertDoesNotExist()
        compose.onNodeWithText("결제 상태 확인").performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        capture("07-payment-pending")

        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("광고").assertDoesNotExist()
        compose.onNode(tab and hasText("설정")).performClick()
        compose.onNodeWithText("광고 제거").performScrollTo().performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()

        compose.runOnIdle { billing.pendingPurchase = false }
        compose.onNodeWithText("결제 상태 확인").performClick()
        planChoice("10 GB").performScrollTo().assertIsDisplayed()
        billingCta().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, billing.checkoutCount) }
    }

    @Test
    fun activePlanUpgradeUsesTheReplacementAction() {
        val billing = FakeBillingSession(hasCurrentCloudPlan = true)
        showJourney(billing, signedIn = true)
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText("클라우드")).performClick()
        compose.onNodeWithText("요금제·결제").performScrollTo().performClick()
        compose.onNodeWithText("월간 결제").performScrollTo().assertIsDisplayed()
        billingPeriod("연간").assertDoesNotExist()
        val option = planChoice("50 GB")
        billingCta().assertIsNotEnabled()
        option.performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText("요금제 변경 확인").assertDoesNotExist()
        capture("08-cloud-plan-change")
        billingCta().assertIsEnabled().assert(hasText("요금제 변경")).performClick()
        compose.onNodeWithText("요금제 변경 확인").assertIsDisplayed()
        compose.onNodeWithText("Google Play 결제창 열기").performClick()
        compose.runOnIdle {
            assertEquals(1, billing.replacementCount)
            assertEquals(0, billing.checkoutCount)
        }
        option.performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pendingPlanChangeWaitsForTheExactTargetBasePlan() {
        val billing = FakeBillingSession(hasCurrentCloudPlan = true).apply { returnPendingFromReplacement = true }
        showJourney(billing, signedIn = true)
        openCloudBilling()
        planChoice("50 GB").performScrollTo().performClick()
        billingCta().performClick()
        compose.onNodeWithText("Google Play 결제창 열기").performClick()

        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        compose.onNodeWithText("결제 상태 확인").performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        compose.onNodeWithText("클라우드 구독이 활성화됐어요").assertDoesNotExist()
        billingCta().assertDoesNotExist()

        compose.runOnIdle { billing.reportReplacementPlan(basePlanId = "annual") }
        compose.onNodeWithText("결제 상태 확인").performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertIsDisplayed()
        billingCta().assertDoesNotExist()

        compose.runOnIdle { billing.reportReplacementPlan(basePlanId = "monthly") }
        compose.onNodeWithText("결제 상태 확인").performClick()
        compose.onNodeWithText("결제 확인을 기다리고 있어요").assertDoesNotExist()
        compose.onNodeWithText("클라우드 구독이 활성화됐어요").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, billing.replacementCount)
            assertEquals(0, billing.checkoutCount)
        }
    }

    @Test
    fun newSignedInCloudAccountOpensCheckoutOnlyAfterChoosingTheBillingRoute() {
        val billing = FakeBillingSession(includeAnnualPlan = true, includeExtraPlans = true)
        showJourney(billing, signedIn = true)
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText("클라우드")).performClick()
        compose.onNodeWithText("요금제·결제").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, billing.checkoutCount)
            assertEquals(0, billing.replacementCount)
        }

        compose.onNodeWithText("요금제·결제").performClick()
        planChoice("10 GB").performScrollTo().assertIsDisplayed()
        planChoice("50 GB").performScrollTo().assertIsDisplayed()
        planChoice("100 GB").performScrollTo().assertIsDisplayed()
        billingCta().assertIsDisplayed().assertIsNotEnabled()

        planChoice("10 GB").performScrollTo().performClick()
        billingCta().assertIsEnabled().assert(hasText("10 GB 구독하기 · 월 ₩1,900")).performClick()
        compose.onNodeWithText("GalliPick Cloud 10 GB · 매월 ₩1,900").assertIsDisplayed()
        compose.onNodeWithText("Google Play 결제창 열기").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, billing.checkoutCount) }
    }

    @Test
    fun globalBillingPeriodKeepsAvailableSelectionAndOnlyAnchoredActionOpensTheQuote() {
        val billing = FakeBillingSession(includeAnnualPlan = true, includeExtraPlans = true)
        showJourney(billing, signedIn = true)
        openCloudBilling()
        billingPeriod("월간").assertIsSelected()
        billingPeriod("연간").assertIsNotSelected()
        val ctaBounds = billingCta().assertIsDisplayed().assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        listOf("10 GB", "50 GB", "100 GB", "광고 제거만").forEach { label ->
            planChoice(label).performScrollTo().assertIsDisplayed().assertIsNotSelected()
        }
        val scrolledCta = billingCta().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("The checkout button should stay anchored when the list scrolls", ctaBounds.top, scrolledCta.top, 1f)

        val tenGb = planChoice("10 GB")
        tenGb.performScrollTo().assert(hasText("₩1,900/월")).performClick().assertIsSelected()
        billingCta().assertIsEnabled().assert(hasText("10 GB 구독하기 · 월 ₩1,900"))
        compose.onNodeWithText("Google Play 결제창 열기").assertDoesNotExist()

        billingPeriod("연간").performScrollTo().performClick().assertIsSelected()
        tenGb.performScrollTo().assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText("₩19,900/년").assertDoesNotExist()
        billingCta().assertIsNotEnabled()
        planChoice("광고 제거만").performScrollTo().assertIsDisplayed().assert(hasText("₩9,900/년"))
            .performClick().assertIsSelected()
        billingCta().assertIsEnabled().assert(hasText("광고 제거만 구독하기 · 연 ₩9,900"))
        compose.runOnIdle { assertEquals(0, billing.checkoutCount) }
    }

    @Test
    fun switchingToAnUnavailablePeriodClearsTheSelectedPlan() {
        showJourney(FakeBillingSession(includeAnnualPlan = true, includeExtraPlans = true), signedIn = true)
        openCloudBilling()
        planChoice("50 GB").performScrollTo().performClick().assertIsSelected()
        billingCta().assertIsEnabled()

        billingPeriod("연간").performScrollTo().performClick().assertIsSelected()
        planChoice("10 GB").performScrollTo().assertIsNotSelected()
        billingCta().assertIsNotEnabled()

        billingPeriod("월간").performScrollTo().performClick().assertIsSelected()
        planChoice("50 GB").performScrollTo().assertIsNotSelected()
        billingCta().assertIsNotEnabled()
    }

    @Test
    fun noOfficialPlayOfferShowsPlannedPriceWithoutCheckout() {
        val billing = FakeBillingSession(hasOffers = false, cloudCheckoutAllowed = false)
        showJourney(billing, signedIn = true)
        openCloudBilling()
        val plans = listOf("10 GB", "50 GB", "100 GB")
        billingPeriod("월간").assertIsSelected()
        billingPeriod("연간").assertIsNotSelected()
        plans.forEach { label ->
            planChoice(label).performScrollTo().assertIsDisplayed().assertIsNotEnabled().assertIsNotSelected()
        }
        planChoice("10 GB").performScrollTo().assert(hasText("₩1,900/월"))
        billingCta().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("현재 클라우드 신규 구독을 신청할 수 없습니다.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("한국 출시 예정가", substring = true).performScrollTo().assertIsDisplayed()
        capture("09-billing-unavailable-monthly")

        billingPeriod("연간").performScrollTo().performClick().assertIsSelected()
        billingPeriod("월간").assertIsNotSelected()
        plans.forEach { label ->
            planChoice(label).performScrollTo().assertIsDisplayed().assertIsNotEnabled().assertIsNotSelected()
        }
        planChoice("10 GB").performScrollTo().assert(hasText("신청 불가"))
        compose.onNodeWithText("₩19,900/년").assertDoesNotExist()
        compose.onNodeWithText("₩1,900/월").assertDoesNotExist()
        billingCta().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Google Play 결제창 열기").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, billing.checkoutCount) }
        capture("10-billing-unavailable-annual")
    }

    @Test
    fun changedPlayPriceRequiresASecondConfirmationUsingTheNewQuote() {
        val billing = FakeBillingSession().apply { changePriceOnCheckout = true }
        showJourney(billing, signedIn = true)
        openCloudBilling()
        selectMonthlyTenGbAndOpenQuote()
        compose.onNodeWithText("Google Play 결제창 열기").performClick()

        planChoice("10 GB").performScrollTo().assertIsDisplayed().assert(hasText("₩2,100/월"))
        compose.onNodeWithText("₩1,900/월").assertDoesNotExist()
        compose.onNodeWithText("Google Play 결제창 열기").assertDoesNotExist()
        planChoice("10 GB").performClick()
        billingCta().assert(hasText("10 GB 구독하기 · 월 ₩2,100")).performClick()
        compose.onNodeWithText("GalliPick Cloud 10 GB · 매월 ₩2,100").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, billing.checkoutCount) }
    }

    private fun planChoice(label: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText(label),
    )

    private fun billingPeriod(label: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(label),
    )

    private fun billingCta() = compose.onNodeWithTag("cloud-billing-cta")

    private fun selectMonthlyTenGbAndOpenQuote() {
        planChoice("10 GB").performScrollTo().performClick()
        billingCta().assertIsEnabled().performClick()
    }

    private fun openCloudBilling() {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText("클라우드")).performClick()
        compose.onNodeWithText("요금제·결제").performScrollTo().performClick()
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

    private class FakeBillingSession(
        private val hasCurrentCloudPlan: Boolean = false,
        private val includeAnnualPlan: Boolean = false,
        private val hasOffers: Boolean = true,
        includeExtraPlans: Boolean = false,
        cloudCheckoutAllowed: Boolean = true,
    ) : CloudBillingSession {
        private val product = CloudBillingProduct("gallipick_cloud_10gb", "monthly", 10_000_000_000L)
        private val annual = product.copy(basePlanId = "annual")
        private var offer = CloudBillingOffer(product, "GalliPick Cloud 10 GB", "₩1,900", 1_900_000_000L, "KRW")
        private val annualOffer = CloudBillingOffer(annual, "GalliPick Cloud 10 GB", "₩19,900", 19_900_000_000L, "KRW")
        private val annualAdFreeOffer = CloudBillingOffer(
            CloudBillingProduct("gallipick_ad_free", "annual", 0),
            "광고 제거", "₩9,900", 9_900_000_000L, "KRW",
        )
        private val fifty = CloudBillingOffer(
            CloudBillingProduct("gallipick_cloud_50gb", "monthly", 50_000_000_000L),
            "GalliPick Cloud 50 GB", "₩5,900", 5_900_000_000L, "KRW",
        )
        private val extraOffers = if (includeExtraPlans) listOf(
            fifty,
            CloudBillingOffer(CloudBillingProduct("gallipick_cloud_100gb", "monthly", 100_000_000_000L),
                "GalliPick Cloud 100 GB", "₩10,900", 10_900_000_000L, "KRW"),
            CloudBillingOffer(CloudBillingProduct("gallipick_ad_free", "monthly", 0),
                "광고 제거", "₩1,000", 1_000_000_000L, "KRW"),
        ) else emptyList()
        private var serverPlan = CloudBillingPlan(enabled = true,
            products = listOf(product, annual) + extraOffers.map { it.product } +
                listOfNotNull(annualAdFreeOffer.product.takeIf { includeAnnualPlan && includeExtraPlans }),
            cloudCheckoutAllowed = cloudCheckoutAllowed)
        private val retiredOffer = CloudBillingOffer(
            CloudBillingProduct("gallipick_cloud_pilot", "monthly", 1_073_741_824L),
            "GalliPick Cloud 1 GiB 테스트", "₩1,000", 1_000_000_000L, "KRW",
        )
        private val replacementOffer = CloudBillingReplacementOffer(product, fifty, CloudBillingReplacementTiming.IMMEDIATE_PRORATED)
        init {
            if (hasCurrentCloudPlan) {
                verifyPurchase()
                serverPlan = serverPlan.copy(backupAvailable = true,
                    products = listOf(product, fifty.product, fifty.product.copy(basePlanId = "annual")))
            }
        }
        val returnedToApp = CompletableDeferred<Unit>()
        var checkoutCount = 0
            private set
        var purchaseQueries = 0
            private set
        var recoverCalls = 0
            private set
        var replacementCount = 0
            private set
        var purchaseWaitingForRecovery = false
        var returnPendingFromCheckout = false
        var pendingPurchase = false
        var changePriceOnCheckout = false
        var returnPendingFromReplacement = false

        override suspend fun loadPlan(): CloudBillingPlan = serverPlan

        override suspend fun load(): CloudBillingSnapshot = CloudBillingSnapshot(
            serverPlan,
            if (!hasOffers) emptyList() else listOfNotNull(
                offer, retiredOffer, annualOffer.takeIf { includeAnnualPlan },
                annualAdFreeOffer.takeIf { includeAnnualPlan && includeExtraPlans },
            ) + extraOffers,
            notice = CloudBillingNotice.PENDING.takeIf { pendingPurchase },
            replacementOffers = listOf(replacementOffer).filter { hasCurrentCloudPlan },
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
            if (changePriceOnCheckout) {
                changePriceOnCheckout = false
                this.offer = offer.copy(formattedPrice = "₩2,100", priceAmountMicros = 2_100_000_000L)
                return CloudBillingUpdate(serverPlan, CloudBillingNotice.PRICE_CHANGED)
            }
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

        override suspend fun replace(activity: Activity, offer: CloudBillingReplacementOffer): CloudBillingUpdate {
            assertEquals(replacementOffer, offer)
            replacementCount++
            val notice = if (returnPendingFromReplacement) CloudBillingNotice.PENDING else CloudBillingNotice.CANCELLED
            return CloudBillingUpdate(serverPlan, notice)
        }

        fun reportReplacementPlan(basePlanId: String) {
            serverPlan = serverPlan.copy(activeProductId = fifty.product.productId, activeBasePlanId = basePlanId,
                quotaBytes = fifty.product.quotaBytes)
        }

        private fun verifyPurchase(): CloudBillingUpdate {
            serverPlan = serverPlan.copy(
                activeProductId = product.productId,
                activeBasePlanId = product.basePlanId,
                expiresAtMs = System.currentTimeMillis() + 60_000L,
                uploadAllowed = true,
                quotaBytes = product.quotaBytes,
            )
            return CloudBillingUpdate(serverPlan, CloudBillingNotice.VERIFIED, autoRenewing = true)
        }

        override fun close() = Unit
    }
}
