package com.hebi.gallipick.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountStatus
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudBillingException
import com.hebi.gallipick.data.cloud.CloudBillingNotice
import com.hebi.gallipick.data.cloud.CloudBillingOffer
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingProduct
import com.hebi.gallipick.data.cloud.CloudBillingReplacementOffer
import com.hebi.gallipick.data.cloud.CloudBillingReplacementTiming
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.CloudBillingSnapshot
import com.hebi.gallipick.data.cloud.CloudBillingUpdate
import com.hebi.gallipick.data.cloud.GALLIPICK_RETIRED_CLOUD_PILOT
import com.hebi.gallipick.data.cloud.activeCloudProductId
import com.hebi.gallipick.data.cloud.activeStandaloneAdFreeProductId
import com.hebi.gallipick.data.cloud.confirmedReplacement
import com.hebi.gallipick.data.cloud.hasServerSubscription
import com.hebi.gallipick.data.cloud.productionCloudQuotaBytes
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudBillingSection(
    connection: CloudAccountConnection,
    activity: Activity,
    onGoHome: (() -> Unit)? = null,
    onPlanUpdated: (String, CloudBillingPlan) -> Unit,
) {
    val billing = connection.billing ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val accountStatus by connection.session.status.collectAsStateWithLifecycle()
    val accountScope = LocalLifecycleOwner.current.lifecycleScope
    val changeAccount = LocalCheckoutAccountChange.current ?: connection::chooseAccount
    val chooseAccount: () -> Unit = { accountScope.launch { changeAccount(activity) } }
    val currentAccount = account
    if (currentAccount == null) {
        CloudBillingSignIn(accountStatus, chooseAccount)
        return
    }
    val uid = currentAccount.uid
    key(uid) {
        val selectedProduct = rememberSaveable { mutableStateOf<String?>(null) }
        val pendingReplacementTarget = rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
        val billingSession = remember(billing, uid) { billing.open(uid) }
        DisposableEffect(billingSession) { onDispose(billingSession::close) }
        key(billingSession) {
            CloudBillingControls(
                billingSession, activity, onGoHome, selectedProduct, pendingReplacementTarget,
                accountStatus, currentAccount.transferAccountLabel, chooseAccount, connection.usesProductionBackupPlan,
            ) { plan ->
                onPlanUpdated(uid, plan)
            }
        }
    }
}

@Composable
private fun CloudBillingSignIn(status: CloudAccountStatus, onChooseAccount: () -> Unit) {
    val signingIn = status.operation != null
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudStatusMessage("구독하려면 Google 계정으로 로그인해 주세요.")
            status.displayText(signedIn = false)?.let { CloudStatusMessage(it) }
            BillingPlanPicker(emptyList(), selectedProductId = null, unavailableLabel = "로그인 필요", onSelect = {})
        }
        BillingCheckoutFooter(
            label = if (signingIn) "Google 계정 확인 중" else "Google로 로그인",
            enabled = !signingIn,
            onAction = onChooseAccount,
        )
    }
}

@Composable
private fun CloudBillingControls(
    session: CloudBillingSession,
    activity: Activity,
    onGoHome: (() -> Unit)?,
    selectedProduct: MutableState<String?>,
    pendingReplacementTarget: MutableState<Pair<String, String>?>,
    accountStatus: CloudAccountStatus,
    accountLabel: String,
    onChooseAccount: () -> Unit,
    requiresCloudCheckoutPermission: Boolean,
    onPlanUpdated: (CloudBillingPlan) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CloudBillingSnapshot?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<CloudBillingNotice?>(null) }
    var confirmation by remember { mutableStateOf<CloudBillingOffer?>(null) }
    var replacementConfirmation by remember { mutableStateOf<CloudBillingReplacementOffer?>(null) }
    var refreshAfterManagement by remember { mutableStateOf(false) }
    var checkingPayment by remember { mutableStateOf(false) }
    var selectedProductId by selectedProduct
    var pendingReplacement by pendingReplacementTarget
    var replacementChoice by remember { mutableStateOf<CloudBillingReplacementOffer?>(null) }
    val accountChanging = accountStatus.operation != null
    val screenBusy = busy || accountChanging

    fun replacementStatus(plan: CloudBillingPlan?): CloudBillingNotice? {
        val (productId, basePlanId) = pendingReplacement ?: return null
        val confirmed = plan?.confirmedReplacement(productId, basePlanId, System.currentTimeMillis())
        if (confirmed != null) pendingReplacement = null
        return confirmed ?: CloudBillingNotice.PENDING
    }

    suspend fun refresh(checkoutNotice: CloudBillingNotice? = null) {
        val waitingForPurchase = pendingReplacement != null || notice.awaitingPurchaseConfirmation()
        busy = true
        checkingPayment = waitingForPurchase
        notice = null
        snapshot = snapshot?.copy(offers = emptyList(), replacementOffers = emptyList())
        try {
            val refreshed = session.load()
            snapshot = refreshed
            notice = refreshed.notice.takeIf { it.canChangeAccount() }
                ?: replacementStatus(refreshed.plan) ?: refreshed.notice ?: checkoutNotice
            if (notice == null && waitingForPurchase && refreshed.plan.enabled) {
                notice = if (refreshed.plan.verifiedAdFree()) CloudBillingNotice.VERIFIED else CloudBillingNotice.NO_PURCHASE
            }
            onPlanUpdated(refreshed.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = error.billingNotice()
            notice = failure.takeIf { it.canChangeAccount() }
                ?: replacementStatus(null) ?: failure.preservePending(wasWaiting = waitingForPurchase)
        } finally {
            busy = false
            checkingPayment = false
        }
    }

    fun runBilling(
        fromCheckout: Boolean = false,
        replacementTarget: CloudBillingProduct? = null,
        operation: suspend () -> CloudBillingUpdate,
    ) {
        if (busy || accountChanging) return
        val waitingForPurchase = pendingReplacement != null || notice.awaitingPurchaseConfirmation()
        busy = true
        checkingPayment = fromCheckout || waitingForPurchase
        notice = null
        scope.launch {
            try {
                val update = operation()
                if (fromCheckout && (update.notice == CloudBillingNotice.CANCELLED ||
                        update.notice == CloudBillingNotice.PRICE_CHANGED)) {
                    refresh(checkoutNotice = update.notice)
                    return@launch
                }
                update.plan?.let { plan ->
                    snapshot = CloudBillingSnapshot(plan, emptyList(), autoRenewing = update.autoRenewing)
                    onPlanUpdated(plan)
                }
                if (replacementTarget != null && update.notice.awaitingPurchaseConfirmation()) {
                    pendingReplacement = replacementTarget.productId to replacementTarget.basePlanId
                }
                notice = update.notice.takeIf { it.canChangeAccount() }
                    ?: replacementStatus(update.plan) ?: update.notice
                if (update.notice == CloudBillingNotice.VERIFIED || update.notice == CloudBillingNotice.NO_PURCHASE) {
                    refresh(checkoutNotice = notice)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = error.billingNotice()
                notice = failure.takeIf { it.canChangeAccount() }
                    ?: replacementStatus(null) ?: failure.preservePending(wasWaiting = waitingForPurchase)
            } finally {
                busy = false
                checkingPayment = false
            }
        }
    }

    LaunchedEffect(session) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (refreshAfterManagement) {
            refreshAfterManagement = false
            scope.launch { refresh() }
        } else if (!notice.canChangeAccount() && (pendingReplacement != null || notice.awaitingPurchaseConfirmation())) {
            runBilling {
                val update = session.recover()
                if (update.notice == CloudBillingNotice.NO_PURCHASE) update.copy(notice = CloudBillingNotice.PENDING)
                else update
            }
        }
    }
    val plan = snapshot?.plan
    val nowMillis = System.currentTimeMillis()
    val verifiedCloudProductId = plan?.activeCloudProductId(nowMillis)
    val verifiedStandaloneProductId = plan?.activeStandaloneAdFreeProductId(nowMillis)
    val verifiedProductId = verifiedCloudProductId ?: verifiedStandaloneProductId
    val purchaseConfirmed = verifiedProductId != null && notice == CloudBillingNotice.VERIFIED
    val waitingForPurchase = pendingReplacement != null || notice.awaitingPurchaseConfirmation()
    val accountMismatch = notice.hasAccountMismatch()
    val accountBlocked = notice.canChangeAccount()
    val showPlanPicker = !checkingPayment && !waitingForPurchase && !accountMismatch &&
        plan?.hasServerSubscription != true && verifiedProductId == null
    val cloudCheckoutAllowed = !requiresCloudCheckoutPermission || plan?.cloudCheckoutAllowed == true
    val visibleOffers = if (showPlanPicker && !screenBusy && !notice.canChangeAccount() && plan?.enabled == true) {
        snapshot?.offers.orEmpty().filter {
            it.product in plan.products && it.product.isAvailableForNewCheckout() &&
                (it.product.isStandaloneAdFree || cloudCheckoutAllowed)
        }
    } else emptyList()
    val selectedOffer = visibleOffers.firstOrNull { it.product.selectionId == selectedProductId }
    val replacementOffers = if (!screenBusy && !waitingForPurchase && !notice.canChangeAccount() &&
        verifiedProductId != null && plan?.scheduledReplacement == null
    ) snapshot?.replacementOffers.orEmpty().filter {
        it.target.product.isAvailableForNewCheckout()
    } else emptyList()
    val selectedReplacement = replacementOffers.firstOrNull {
        it.current == replacementChoice?.current && it.target.product == replacementChoice?.target?.product
    }
    LaunchedEffect(screenBusy, selectedOffer) {
        if (!screenBusy && selectedOffer == null) selectedProductId = null
    }
    LaunchedEffect(screenBusy, selectedReplacement) {
        if (!screenBusy && selectedReplacement == null) replacementChoice = null
    }
    var managementFailed by remember(verifiedProductId) { mutableStateOf(false) }
    val recover = { runBilling { session.recover() } }
    val refreshPlans: () -> Unit = {
        if (!screenBusy) {
            managementFailed = false
            scope.launch { refresh() }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (purchaseConfirmed && !screenBusy) {
                CloudAccountCard {
                    Row(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CloudFeatureIcon(Icons.Outlined.CheckCircle)
                        BillingHeading(if (verifiedCloudProductId != null && plan?.backupAvailable == true)
                            "클라우드 구독이 활성화됐어요" else "광고가 제거됐어요")
                    }
                    onGoHome?.let { CloudPrimaryAction("홈으로", it) }
                }
            }

            if (!accountBlocked && plan?.manualGrantKind != null &&
                plan.manualGrantExpiresAtMs?.let { it > nowMillis } == true) {
                CloudAccountCard {
                    BillingHeading("관리자 지급 이용권")
                    val manualLabel = when (plan.manualGrantKind) {
                        "CLOUD_10GB" -> "Cloud 10 GB"
                        "CLOUD_50GB" -> "Cloud 50 GB"
                        "CLOUD_100GB" -> "Cloud 100 GB"
                        "AD_FREE" -> "광고 제거"
                        else -> "이용권"
                    }
                    CloudStatusMessage(manualLabel + " · " +
                        billingDateLabel(checkNotNull(plan.manualGrantExpiresAtMs)) + "까지 적용")
                    CloudStatusMessage("관리자가 별도로 지급한 무상 이용권입니다. Google Play 정기 결제나 자동 갱신과는 관계없습니다.")
                }
            }

            if (showPlanPicker) {
                val priceCheckFailed = notice == CloudBillingNotice.FAILED || notice == CloudBillingNotice.UNAVAILABLE
                val message = when {
                    accountChanging -> null
                    busy -> "Google Play 요금제와 가격을 확인하는 중입니다."
                    notice.canChangeAccount() || priceCheckFailed -> notice?.displayText()
                    plan?.enabled != true -> "현재 구독을 확인할 수 없습니다. 구독 정보를 새로고침해 주세요."
                    !cloudCheckoutAllowed -> "현재 클라우드 신규 구독을 신청할 수 없습니다."
                    visibleOffers.none { !it.product.isStandaloneAdFree } ->
                        "Google Play에서 현재 제공하는 클라우드 요금제가 없습니다."
                    else -> notice?.displayText()
                }
                message?.let { CloudStatusMessage(it) }
                if (notice.canChangeAccount()) {
                    BillingAccountAction(accountStatus, onChooseAccount)
                }
                BillingPlanPicker(
                    visibleOffers,
                    selectedOffer?.product?.selectionId,
                    unavailableLabel = when {
                        busy -> "조회 중"
                        notice.canChangeAccount() -> "계정 확인"
                        priceCheckFailed || plan?.enabled != true -> "확인 필요"
                        else -> "판매 없음"
                    },
                    onSelect = { selectedProductId = it },
                )
            } else {
                CloudAccountCard {
                    when {
                        accountBlocked -> {
                            BillingHeading("결제 계정을 확인해 주세요")
                            CloudStatusMessage(checkNotNull(notice).displayText())
                            Text("현재 연결된 계정 · $accountLabel", style = MaterialTheme.typography.bodyMedium)
                            BillingAccountAction(accountStatus, onChooseAccount)
                        }
                        busy -> {
                            BillingHeading(if (checkingPayment) "결제 확인 중" else "구독 정보")
                            CloudStatusMessage("구독 정보를 확인하는 중입니다.")
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary, trackColor = Panel)
                        }
                        waitingForPurchase -> {
                            BillingHeading("결제 확인을 기다리고 있어요")
                            CloudStatusMessage("Google Play에서 결제 내역을 확인 중입니다. 확인이 끝나면 구독 상태가 갱신됩니다.")
                            CloudPrimaryAction("결제 상태 확인", { scope.launch { refresh() } })
                        }
                        verifiedCloudProductId != null -> {
                            BillingPlanDetails(checkNotNull(plan), snapshot?.autoRenewing)
                            if (!purchaseConfirmed) notice?.let { CloudStatusMessage(it.displayText()) }
                        }
                        verifiedStandaloneProductId != null -> {
                            BillingHeading("내 구독")
                            Text(
                                billingTermLabel("광고 제거", plan?.adFreeBasePlanId),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            plan?.adFreeExpiresAtMs?.let { expiry ->
                                CloudStatusMessage("현재 구독 기간 종료일 ${billingDateLabel(expiry)}")
                            }
                            if (!purchaseConfirmed) notice?.let { CloudStatusMessage(it.displayText()) }
                        }
                        else -> {
                            BillingHeading("구독 정보")
                            when {
                                plan == null -> CloudStatusMessage(notice?.displayText() ?: "구독 정보를 확인하지 못했습니다.")
                                !plan.enabled -> CloudStatusMessage("현재 구독을 이용할 수 없습니다.")
                                notice != null -> CloudStatusMessage(checkNotNull(notice).displayText())
                                else -> CloudStatusMessage("현재 구독의 유효기간을 확인해 주세요.")
                            }
                        }
                    }
                }
            }

            if (!screenBusy && !waitingForPurchase && !accountBlocked) {
                plan?.scheduledReplacement?.let { scheduled ->
                    val target = plan.products.firstOrNull { it.productId == scheduled.productId && it.basePlanId == scheduled.basePlanId }
                    val label = when {
                        target?.isStandaloneAdFree == true -> "광고 제거"
                        target != null -> "클라우드 ${billingQuotaLabel(target.quotaBytes)}"
                        else -> "변경한 요금제"
                    }
                    CloudAccountCard {
                        BillingHeading("요금제 변경 예약")
                        CloudStatusMessage("${billingDateLabel(scheduled.effectiveAtMs)}부터 $label 요금제가 적용됩니다.")
                    }
                }
                if (replacementOffers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BillingHeading("변경 가능한 요금제")
                        CloudStatusMessage("${replacementOffers.first().current.period.label} 결제")
                        Column(Modifier.fillMaxWidth().galliCardSurface().selectableGroup()) {
                            replacementOffers.forEachIndexed { index, replacement ->
                                if (index > 0) BillingPlanDivider()
                                BillingPlanRow(
                                    replacement.target.product.pickerLabel,
                                    replacement.target,
                                    selected = replacement == selectedReplacement,
                                    onSelect = { replacementChoice = replacement },
                                )
                            }
                        }
                    }
                }
            }

            if (!screenBusy && (!waitingForPurchase || accountBlocked) && !showPlanPicker) {
                Column(Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(horizontal = 12.dp)) {
                    if (verifiedProductId != null && !accountBlocked) {
                        BillingActionRow("Google Play에서 구독 관리", Icons.Outlined.PlayArrow) {
                            refreshAfterManagement = openPlaySubscription(activity, verifiedProductId)
                            managementFailed = !refreshAfterManagement
                        }
                        HorizontalDivider(color = Line)
                    }
                    if (plan?.enabled == true && !accountBlocked) {
                        BillingActionRow("구매 복구", Icons.Outlined.Restore, recover)
                        HorizontalDivider(color = Line)
                    }
                    BillingActionRow("구독 정보 새로고침", Icons.Outlined.Refresh, refreshPlans)
                }
                if (managementFailed) CloudStatusMessage("Google Play 구독 관리 화면을 열지 못했습니다.")
            }
        }
        when {
            showPlanPicker -> BillingCheckoutFooter(
                label = when {
                    selectedOffer != null -> "${selectedOffer.product.pickerLabel} 구독하기 · ${selectedOffer.product.period.unit} ${selectedOffer.formattedPrice}"
                    screenBusy -> "요금제 확인 중"
                    visibleOffers.isEmpty() -> "현재 선택할 수 있는 요금제가 없습니다"
                    else -> "요금제를 선택해 주세요"
                },
                enabled = selectedOffer != null,
                onAction = { confirmation = selectedOffer },
                onRestore = recover.takeIf { !screenBusy && !notice.canChangeAccount() && plan?.enabled == true },
                onRefresh = refreshPlans.takeUnless { screenBusy },
            )
            replacementOffers.isNotEmpty() -> BillingCheckoutFooter(
                label = "요금제 변경",
                enabled = selectedReplacement != null,
                onAction = { replacementConfirmation = selectedReplacement },
                note = selectedReplacement?.timing?.displayText(),
            )
        }
    }

    val replacement = replacementConfirmation
    (replacement?.target ?: confirmation)?.let { offer ->
        fun clearSelection() {
            confirmation = null
            replacementConfirmation = null
        }
        AlertDialog(
            onDismissRequest = ::clearSelection,
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text(if (replacement != null) "요금제 변경 확인" else "구독 확인") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${offer.title} · ${offer.intervalLabel} ${offer.formattedPrice}",
                        color = Ink,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(offer.benefitText, color = Muted, style = MaterialTheme.typography.bodySmall)
                    replacement?.let {
                        Text(it.timing.displayText(), color = Ink, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "${offer.intervalLabel} 자동 갱신되며 Google Play에서 취소할 수 있습니다.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Google Play에서 금액과 결제수단을 확인해 주세요.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextAction("Google Play 결제창 열기", {
                    clearSelection()
                    runBilling(fromCheckout = true, replacementTarget = replacement?.target?.product) {
                        if (replacement == null) session.purchase(activity, offer)
                        else session.replace(activity, replacement)
                    }
                })
            },
            dismissButton = { TextAction("취소", ::clearSelection) },
        )
    }
}

private enum class BillingPeriod(val label: String, val unit: String, val rateUnit: String) {
    MONTHLY("월간", "월", "월"), ANNUAL("연간", "연", "년"),
}

private val CloudBillingProduct.period: BillingPeriod
    get() = if (billingPeriod == "P1Y") BillingPeriod.ANNUAL else BillingPeriod.MONTHLY

private val CloudBillingProduct.pickerLabel: String
    get() = if (isStandaloneAdFree) "광고 제거만" else billingQuotaLabel(quotaBytes)

private val CloudBillingProduct.selectionId: String
    get() = "$productId:$basePlanId"

private fun CloudBillingProduct.isAvailableForNewCheckout(): Boolean =
    productId != GALLIPICK_RETIRED_CLOUD_PILOT &&
        (basePlanId == "monthly" || (isStandaloneAdFree && basePlanId == "annual"))

@Composable
private fun BillingPlanPicker(
    offers: List<CloudBillingOffer>,
    selectedProductId: String?,
    unavailableLabel: String,
    onSelect: (String) -> Unit,
) {
    val (adFree, cloud) = offers.partition { it.product.isStandaloneAdFree }
    val cloudQuotas = (productionCloudQuotaBytes + cloud.map { it.product.quotaBytes }).distinct().sorted()
    Column(
        Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BillingCatalogCard("클라우드 월간", "원본 백업과 보관함 · 광고 제거 포함", Icons.Outlined.Cloud) {
            cloudQuotas.forEachIndexed { index, quota ->
                if (index > 0) BillingPlanDivider()
                val offer = cloud.firstOrNull { it.product.quotaBytes == quota }
                val id = offer?.product?.selectionId
                BillingPlanRow(
                    billingQuotaLabel(quota), offer, selected = id != null && id == selectedProductId,
                    unavailableLabel = unavailableLabel, onSelect = { id?.let(onSelect) },
                )
            }
        }
        BillingCatalogCard("광고 제거", "광고 없이 이용 · 클라우드 저장공간 없음", Icons.Outlined.CreditCard) {
            BillingPeriod.entries.forEachIndexed { index, period ->
                if (index > 0) BillingPlanDivider()
                val offer = adFree.firstOrNull { it.product.period == period }
                val id = offer?.product?.selectionId
                BillingPlanRow(
                    period.label, offer, selected = id != null && id == selectedProductId,
                    unavailableLabel = unavailableLabel, onSelect = { id?.let(onSelect) },
                )
            }
        }
    }
}

@Composable
private fun BillingCatalogCard(
    title: String,
    description: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(8.dp)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GalliIcon(icon, null, Modifier.size(20.dp))
                BillingHeading(title)
            }
            Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        content()
    }
}

@Composable
private fun BillingPlanRow(
    label: String,
    offer: CloudBillingOffer?,
    selected: Boolean,
    unavailableLabel: String = "판매 없음",
    onSelect: () -> Unit,
) {
    val enabled = offer != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(SelectedContainer) else Modifier)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted),
        )
        Text(label, Modifier.weight(1f), color = if (enabled) Ink else Muted,
            style = MaterialTheme.typography.titleSmall)
        Text(
            offer?.let { "${it.formattedPrice}/${it.product.period.rateUnit}" } ?: unavailableLabel,
            modifier = Modifier.weight(1f),
            color = if (enabled) Ink else Muted,
            style = if (enabled) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun BillingPlanDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Line)
}

@Composable
private fun BillingCheckoutFooter(
    label: String,
    enabled: Boolean,
    onAction: () -> Unit,
    onRestore: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    note: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = Line)
        if (note != null) {
            Text(note, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted,
                style = MaterialTheme.typography.bodyMedium)
        }
        ActionButton(
            label, onAction,
            Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 52.dp).testTag("cloud-billing-cta"),
            filled = true, enabled = enabled,
        )
        if (onRefresh != null && onRestore != null) {
            Row(
                Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                TextAction("구매 복구", onRestore, Modifier.weight(1f), MaterialTheme.typography.bodyMedium)
                TextAction("구독 정보 새로고침", onRefresh, Modifier.weight(1.65f), MaterialTheme.typography.bodyMedium)
            }
        } else if (onRefresh != null) TextAction("구독 정보 새로고침", onRefresh)
        else if (onRestore != null) TextAction("구매 복구", onRestore)
    }
}

@Composable
private fun BillingAccountAction(status: CloudAccountStatus, onChooseAccount: () -> Unit) {
    status.displayText(signedIn = true)?.let { CloudStatusMessage(it) }
    ActionButton("Google 계정 변경", onChooseAccount, Modifier.fillMaxWidth(), enabled = status.operation == null)
}

private fun CloudBillingNotice?.hasAccountMismatch(): Boolean =
    this == CloudBillingNotice.PURCHASE_LINKED_TO_ANOTHER_ACCOUNT || this == CloudBillingNotice.ACCOUNT_CHANGED

private fun CloudBillingNotice?.canChangeAccount(): Boolean =
    hasAccountMismatch() || this == CloudBillingNotice.ACCOUNT_ACCESS_REQUIRED

private fun CloudBillingReplacementTiming.displayText(): String = when (this) {
    CloudBillingReplacementTiming.IMMEDIATE_PRORATED -> "Google Play에서 계산한 차액으로 즉시 변경됩니다."
    CloudBillingReplacementTiming.NEXT_RENEWAL -> "현재 구독을 유지하고 다음 갱신일부터 새 요금제가 적용됩니다."
}

private fun CloudBillingPlan.verifiedAdFree(): Boolean =
    cloudAdRemovalStatus(this, System.currentTimeMillis()) == AdRemovalStatus.VERIFIED_AD_FREE

private fun CloudBillingNotice?.awaitingPurchaseConfirmation(): Boolean =
    this == CloudBillingNotice.PENDING || this == CloudBillingNotice.ALREADY_OWNED

private fun CloudBillingNotice.preservePending(wasWaiting: Boolean): CloudBillingNotice {
    if (!wasWaiting) return this
    return when (this) {
        CloudBillingNotice.FAILED, CloudBillingNotice.UNAVAILABLE, CloudBillingNotice.ACCOUNT_ACCESS_REQUIRED ->
            CloudBillingNotice.PENDING
        else -> this
    }
}

@Composable
private fun BillingHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun BillingPlanDetails(plan: CloudBillingPlan, autoRenewing: Boolean?) {
    BillingHeading("내 구독")
    val subscriptionQuota = plan.products.firstOrNull { it.productId == plan.activeProductId }?.quotaBytes
        ?: plan.quotaBytes
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudFeatureIcon(Icons.Outlined.Cloud)
        Text(
            billingTermLabel("클라우드 ${billingQuotaLabel(subscriptionQuota)}", plan.activeBasePlanId),
            modifier = Modifier.weight(1f),
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "이용 중",
            modifier = Modifier.background(Primary, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = OnPrimary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Text("구독 혜택으로 광고가 제거됩니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
    HorizontalDivider(color = Line)
    Text(
        "클라우드 사용량 ${billingQuotaLabel(plan.usedBytes)} / ${billingQuotaLabel(plan.quotaBytes)}",
        color = Ink,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (plan.quotaBytes > 0L) {
        val usedFraction = (plan.usedBytes.toDouble() / plan.quotaBytes).coerceIn(0.0, 1.0).toFloat()
        LinearProgressIndicator({ usedFraction }, Modifier.fillMaxWidth(), color = Primary, trackColor = Panel)
    }
    if (!plan.uploadAllowed) CloudStatusMessage("새 백업을 추가할 수 없는 상태입니다.")
    plan.expiresAtMs?.let { expiry ->
        Text("현재 구독 기간 종료일 ${billingDateLabel(expiry)}", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    autoRenewing?.let {
        Text(
            if (it) "Google Play 자동 갱신 켜짐" else "Google Play 자동 갱신 꺼짐",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun billingTermLabel(label: String, basePlanId: String?): String = when (basePlanId) {
    "monthly" -> "$label · 월간"
    "annual" -> "$label · 연간"
    else -> label
}

@Composable
private fun BillingActionRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalliIcon(icon, null, Modifier.size(22.dp))
        Text(label, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.bodyMedium)
        GalliIcon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Muted)
    }
}

private fun billingQuotaLabel(bytes: Long): String {
    val gb = 1_000_000_000L
    val gib = 1_073_741_824L
    return when {
        bytes >= gb && bytes % gb == 0L -> "${bytes / gb} GB"
        bytes >= gib && bytes % gib == 0L -> "${bytes / gib} GiB"
        else -> bytesLabel(bytes)
    }
}

private fun billingDateLabel(expiry: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.KOREA).format(Date(expiry))

private val CloudBillingOffer.intervalLabel: String
    get() = if (product.billingPeriod == "P1Y") "매년" else "매월"

private val CloudBillingOffer.benefitText: String
    get() = if (product.isStandaloneAdFree) {
        "광고 제거 · 클라우드 저장공간 없음"
    } else "광고 제거 · 클라우드 보관 ${billingQuotaLabel(product.quotaBytes)}"

private fun openPlaySubscription(activity: Activity, productId: String): Boolean {
    val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
        .appendQueryParameter("sku", productId)
        .appendQueryParameter("package", activity.packageName)
        .build()
    val intent = Intent(Intent.ACTION_VIEW, uri)
    return try {
        activity.startActivity(Intent(intent).setPackage("com.android.vending"))
        true
    } catch (_: ActivityNotFoundException) {
        try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

private fun Exception.billingNotice(): CloudBillingNotice = when (this) {
    is CloudBillingException -> notice
    is CloudBackupException -> when (result) {
        CloudBackupResult.ACCOUNT_CHANGED -> CloudBillingNotice.ACCOUNT_CHANGED
        CloudBackupResult.TEST_ACCESS_REQUIRED -> if (permissionDenied) CloudBillingNotice.ACCOUNT_ACCESS_REQUIRED
            else CloudBillingNotice.UNAVAILABLE
        else -> CloudBillingNotice.FAILED
    }
    else -> CloudBillingNotice.FAILED
}

private fun CloudBillingNotice.displayText(): String = when (this) {
    CloudBillingNotice.VERIFIED -> "구매 내역을 확인했습니다. 현재 사용할 수 있는 구독이 없습니다."
    CloudBillingNotice.CHANGE_SCHEDULED -> "Google Play에서 요금제 변경 예약을 확인했습니다."
    CloudBillingNotice.PENDING, CloudBillingNotice.ALREADY_OWNED -> "결제 결과를 확인하고 있습니다."
    CloudBillingNotice.CANCELLED -> "Google Play 결제가 취소됐습니다. 원하면 요금제를 다시 선택할 수 있습니다."
    CloudBillingNotice.NO_PURCHASE -> "이 계정에 연결된 구매 내역이 없습니다. 이미 결제했다면 구매할 때 연결한 계정을 확인해 주세요."
    CloudBillingNotice.PRICE_CHANGED -> "상품 정보가 변경됐습니다. 구독 정보를 새로고침하고 다시 확인해 주세요."
    CloudBillingNotice.ACCOUNT_CHANGED -> "계정이 변경됐습니다. 같은 계정에서 다시 확인해 주세요."
    CloudBillingNotice.PURCHASE_LINKED_TO_ANOTHER_ACCOUNT -> "다른 GalliPick 계정에 연결된 Google Play 구독이 있습니다. 구매할 때 연결한 계정을 확인해 주세요."
    CloudBillingNotice.ACCOUNT_ACCESS_REQUIRED -> "현재 계정에는 결제 이용 권한이 없습니다. 내부 테스트에서는 승인된 Google 계정으로 로그인해 주세요."
    CloudBillingNotice.UNAVAILABLE -> "Google Play 결제 정보를 불러올 수 없습니다. Play 스토어 상태를 확인해 주세요."
    CloudBillingNotice.FAILED -> "구독 정보를 확인하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요."
}
