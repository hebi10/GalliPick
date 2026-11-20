package com.hebi.gallipick.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
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
    val uid = account?.uid ?: return
    key(uid) {
        val preferredPeriod = rememberSaveable { mutableStateOf<BillingPeriod?>(null) }
        val selectedProduct = rememberSaveable { mutableStateOf<String?>(null) }
        val pendingReplacementTarget = rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
        val billingSession = remember(billing, uid) { billing.open(uid) }
        DisposableEffect(billingSession) { onDispose(billingSession::close) }
        key(billingSession) {
            CloudBillingControls(billingSession, activity, onGoHome, preferredPeriod, selectedProduct, pendingReplacementTarget) { plan ->
                onPlanUpdated(uid, plan)
            }
        }
    }
}

@Composable
private fun CloudBillingControls(
    session: CloudBillingSession,
    activity: Activity,
    onGoHome: (() -> Unit)?,
    preferredPeriod: MutableState<BillingPeriod?>,
    selectedProduct: MutableState<String?>,
    pendingReplacementTarget: MutableState<Pair<String, String>?>,
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
    var requestedPeriod by preferredPeriod
    var selectedProductId by selectedProduct
    var pendingReplacement by pendingReplacementTarget
    var replacementChoice by remember { mutableStateOf<CloudBillingReplacementOffer?>(null) }

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
        snapshot = null
        try {
            val refreshed = session.load()
            snapshot = refreshed
            notice = replacementStatus(refreshed.plan) ?: refreshed.notice ?: checkoutNotice
            if (notice == null && waitingForPurchase && refreshed.plan.enabled) {
                notice = if (refreshed.plan.verifiedAdFree()) CloudBillingNotice.VERIFIED else CloudBillingNotice.NO_PURCHASE
            }
            onPlanUpdated(refreshed.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = replacementStatus(null) ?: error.billingNotice().preservePending(wasWaiting = waitingForPurchase)
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
        if (busy) return
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
                    val offers = snapshot?.offers.orEmpty().filter { it.product in plan.products }
                    snapshot = CloudBillingSnapshot(plan, offers, autoRenewing = update.autoRenewing)
                    onPlanUpdated(plan)
                }
                if (replacementTarget != null && update.notice.awaitingPurchaseConfirmation()) {
                    pendingReplacement = replacementTarget.productId to replacementTarget.basePlanId
                }
                notice = replacementStatus(update.plan) ?: update.notice
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = replacementStatus(null) ?: error.billingNotice().preservePending(wasWaiting = waitingForPurchase)
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
        } else if (pendingReplacement != null || notice.awaitingPurchaseConfirmation()) {
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
    val accountMismatch = notice == CloudBillingNotice.PURCHASE_LINKED_TO_ANOTHER_ACCOUNT ||
        notice == CloudBillingNotice.ACCOUNT_CHANGED
    val showOffers = !busy && !waitingForPurchase && !accountMismatch &&
        plan?.enabled == true && !plan.hasServerSubscription && verifiedProductId == null
    val visibleOffers = snapshot?.offers.orEmpty().filter {
        it.product in plan?.products.orEmpty() && it.product.productId != GALLIPICK_RETIRED_CLOUD_PILOT &&
            (it.product.isStandaloneAdFree || it.product.basePlanId == "monthly")
    }
    val period = requestedPeriod ?: if (visibleOffers.isNotEmpty() && visibleOffers.all {
        it.product.period == BillingPeriod.ANNUAL
    }) BillingPeriod.ANNUAL else BillingPeriod.MONTHLY
    val periodOffers = if (showOffers) visibleOffers.filter { it.product.period == period } else emptyList()
    val selectedOffer = periodOffers.firstOrNull { it.product.productId == selectedProductId }
    val replacementOffers = if (!busy && !waitingForPurchase && !accountMismatch &&
        verifiedProductId != null && plan?.scheduledReplacement == null
    ) snapshot?.replacementOffers.orEmpty().filter {
        it.target.product.isStandaloneAdFree || it.target.product.basePlanId == "monthly"
    } else emptyList()
    val selectedReplacement = replacementOffers.firstOrNull {
        it.current == replacementChoice?.current && it.target.product == replacementChoice?.target?.product
    }
    LaunchedEffect(busy, selectedOffer) {
        if (!busy && selectedOffer == null) selectedProductId = null
    }
    LaunchedEffect(busy, selectedReplacement) {
        if (!busy && selectedReplacement == null) replacementChoice = null
    }
    var managementFailed by remember(verifiedProductId) { mutableStateOf(false) }
    val recover = { runBilling { session.recover() } }
    val refreshPlans: () -> Unit = {
        managementFailed = false
        scope.launch { refresh() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (purchaseConfirmed) {
                CloudAccountCard {
                    Row(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CloudFeatureIcon(Icons.Outlined.CheckCircle)
                        BillingHeading(if (verifiedCloudProductId != null && plan?.backupAvailable == true)
                            "클라우드 구독이 활성화됐어요" else "홈 배너 광고가 제거됐어요")
                    }
                    onGoHome?.let { CloudPrimaryAction("홈으로", it) }
                }
            }

            if (plan?.manualGrantKind != null &&
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

            if (showOffers) {
                BillingPlanPicker(
                    visibleOffers, period, selectedOffer?.product?.productId,
                    unavailableMessage = cloudCatalogUnavailableMessage(plan, notice),
                    onPeriodChange = { next ->
                        requestedPeriod = next
                        if (visibleOffers.none { it.product.productId == selectedProductId && it.product.period == next }) {
                            selectedProductId = null
                        }
                    },
                ) {
                    requestedPeriod = period
                    selectedProductId = it
                }
                notice?.let { CloudStatusMessage(it.displayText()) }
            } else {
                CloudAccountCard {
                    when {
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
                            BillingHeading("광고 제거 구독 이용 중")
                            CloudStatusMessage("광고 제거가 적용되어 있습니다. 클라우드 저장공간은 제공되지 않습니다.")
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

            if (!busy && !waitingForPurchase) {
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
                } else if (!showOffers && plan?.scheduledReplacement == null && !accountMismatch) {
                    if (verifiedProductId == null || verifiedProductId == GALLIPICK_RETIRED_CLOUD_PILOT ||
                        (verifiedStandaloneProductId != null && plan?.backupAvailable != true)) {
                        CloudReleaseCatalog(plan, notice)
                    } else if (plan?.backupAvailable == true) {
                        CloudAccountCard {
                            BillingHeading("요금제 변경")
                            CloudStatusMessage("현재 이 구독에서 변경할 수 있는 Google Play 요금제가 없습니다.")
                        }
                    }
                }
            }

            if (!busy && !waitingForPurchase && !showOffers) {
                Column(Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(horizontal = 12.dp)) {
                    if (verifiedProductId != null) {
                        BillingActionRow("Google Play에서 구독 관리", Icons.Outlined.PlayArrow) {
                            refreshAfterManagement = openPlaySubscription(activity, verifiedProductId)
                            managementFailed = !refreshAfterManagement
                        }
                        HorizontalDivider(color = Line)
                    }
                    if (plan?.enabled == true) {
                        BillingActionRow("구매 복구", Icons.Outlined.Restore, recover)
                        HorizontalDivider(color = Line)
                    }
                    BillingActionRow("구독 정보 새로고침", Icons.Outlined.Refresh, refreshPlans)
                }
                if (managementFailed) CloudStatusMessage("Google Play 구독 관리 화면을 열지 못했습니다.")
            }
        }
        when {
            showOffers -> BillingCheckoutFooter(
                label = selectedOffer?.let {
                    "${it.product.pickerLabel} 구독하기 · ${it.product.period.unit} ${it.formattedPrice}"
                } ?: if (periodOffers.isEmpty()) "현재 신청할 수 없습니다" else "요금제를 선택해 주세요",
                enabled = selectedOffer != null,
                onAction = { confirmation = selectedOffer },
                recoveryActions = recover to refreshPlans,
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${offer.title} · ${offer.intervalLabel} ${offer.formattedPrice}", color = Ink)
                    Text(offer.benefitText, color = Muted)
                    replacement?.let { Text(it.timing.displayText(), color = Ink) }
                    Text("${offer.intervalLabel} 자동 갱신되며 Google Play에서 취소할 수 있습니다.", color = Muted)
                    Text("Google Play에서 금액과 결제수단을 확인해 주세요.", color = Muted)
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

@Composable
private fun BillingPlanPicker(
    offers: List<CloudBillingOffer>,
    period: BillingPeriod,
    selectedId: String?,
    unavailableMessage: String,
    onPeriodChange: (BillingPeriod) -> Unit,
    onSelect: (String) -> Unit,
) {
    val offersByProduct = offers.groupBy { it.product.productId }.values.sortedBy { it.first().product.quotaBytes }
    val (adFree, cloud) = offersByProduct.partition { it.first().product.isStandaloneAdFree }
    val launchRates = cloudLaunchRates.filterNot { rate ->
        cloud.any { it.first().product.quotaBytes == rate.quotaBytes }
    }
    val cloudRows = (cloud.map { it.first().product.quotaBytes to it } +
        launchRates.map { it.quotaBytes to emptyList<CloudBillingOffer>() }).sortedBy { it.first }
    val toggleShape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Row(
            Modifier.fillMaxWidth().background(IconContainer, toggleShape)
                .padding(horizontal = 4.dp, vertical = 2.dp).selectableGroup(),
        ) {
            BillingPeriod.entries.forEach { option ->
                val selected = option == period
                Box(
                    Modifier.weight(1f).heightIn(min = GalliUiMetrics.touchTarget)
                        .clip(RoundedCornerShape(11.dp))
                        .then(if (selected) Modifier.background(AppSurface) else Modifier)
                        .selectable(selected, role = Role.Tab, onClick = { onPeriodChange(option) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option.label, style = MaterialTheme.typography.titleMedium, color = Ink,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("클라우드 요금제", color = Ink, style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
                modifier = Modifier.semantics { heading() })
            Text("홈 배너 광고 제거 포함", color = Muted,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp))
        }
        Spacer(Modifier.height(15.dp))
        Column(Modifier.fillMaxWidth().galliCardSurface(cornerRadius = 12.dp).padding(5.dp)) {
            cloudRows.forEachIndexed { index, (quota, terms) ->
                if (index > 0) BillingPlanDivider()
                val offer = terms.firstOrNull { it.product.period == period }
                val rate = launchRates.firstOrNull { it.quotaBytes == quota }
                BillingPlanRow(
                    billingQuotaLabel(quota), offer,
                    selected = offer != null && offer.product.productId == selectedId,
                    referencePrice = rate?.priceLabel(period),
                    onSelect = { offer?.let { onSelect(it.product.productId) } },
                )
            }
        }
        adFree.forEach { terms ->
            Spacer(Modifier.height(16.dp))
            val product = terms.first().product
            Box(Modifier.fillMaxWidth().galliCardSurface(cornerRadius = 12.dp).padding(horizontal = 5.dp, vertical = 2.dp)) {
                BillingPlanRow(product.pickerLabel, terms.firstOrNull { it.product.period == period },
                    selected = product.productId == selectedId, onSelect = { onSelect(product.productId) })
            }
        }
        if (launchRates.isNotEmpty()) {
            Column(Modifier.padding(start = 4.dp, top = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cloud.isEmpty()) CloudStatusMessage(unavailableMessage)
                CloudStatusMessage("비활성 요금제에 표시된 가격은 한국 출시 예정가입니다.")
            }
        }
    }
}

@Composable
private fun BillingPlanRow(
    label: String,
    offer: CloudBillingOffer?,
    selected: Boolean,
    onSelect: () -> Unit,
    referencePrice: String? = null,
) {
    val enabled = offer != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(SelectedContainer) else Modifier)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted),
        )
        Text(label, Modifier.weight(1f), color = if (enabled) Ink else Muted,
            style = MaterialTheme.typography.titleMedium)
        Text(
            offer?.let { "${it.formattedPrice}/${it.product.period.rateUnit}" } ?: referencePrice ?: "신청 불가",
            modifier = Modifier.weight(1f),
            color = if (enabled) Ink else Muted,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
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
    recoveryActions: Pair<() -> Unit, () -> Unit>? = null,
    note: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(color = Line)
        recoveryActions?.let { (onRestore, onRefresh) ->
            Row(
                Modifier.widthIn(max = 260.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                TextAction("구매 복구", onRestore, Modifier.weight(1f), MaterialTheme.typography.bodyMedium)
                Text("·", color = Muted)
                TextAction("구독 정보 새로고침", onRefresh, Modifier.weight(1.65f), MaterialTheme.typography.bodyMedium)
            }
        }
        if (note != null) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { CloudStatusMessage(note) }
        }
        if (recoveryActions == null) Spacer(Modifier.height(4.dp))
        ActionButton(
            label, onAction,
            Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cloud-billing-cta"),
            filled = true, enabled = enabled,
            labelStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        )
    }
}

@Composable
private fun CloudReleaseCatalog(plan: CloudBillingPlan?, notice: CloudBillingNotice?) {
    CloudAccountCard {
        BillingHeading("신청할 수 없는 클라우드 요금제")
        CloudStatusMessage(cloudCatalogUnavailableMessage(plan, notice))
        CloudStatusMessage("Cloud 신규 결제는 월간만 제공합니다. 실제 판매 금액은 Google Play에서 확인할 수 있습니다.")
        cloudLaunchRates.forEachIndexed { index, rate ->
            if (index > 0) HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(billingQuotaLabel(rate.quotaBytes), Modifier.weight(1f), color = Ink,
                    style = MaterialTheme.typography.titleSmall)
                Column(horizontalAlignment = Alignment.End) {
                    Text("월 ${rate.monthly}", color = Ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun cloudCatalogUnavailableMessage(plan: CloudBillingPlan?, notice: CloudBillingNotice?): String = when {
    plan?.enabled != true -> "클라우드 결제 정보를 현재 확인할 수 없습니다."
    !plan.cloudCheckoutAllowed -> "현재 클라우드 신규 구독을 신청할 수 없습니다."
    notice == CloudBillingNotice.UNAVAILABLE || notice == CloudBillingNotice.FAILED ->
        "Google Play 상품을 확인하지 못했습니다. 구독 정보를 새로고침해 주세요."
    else -> "Google Play에서 현재 제공하는 클라우드 요금제가 없습니다."
}

private data class CloudLaunchRate(val quotaBytes: Long, val monthly: String) {
    fun priceLabel(period: BillingPeriod): String? =
        if (period == BillingPeriod.MONTHLY) "${monthly}/월" else null
}

private val cloudLaunchRates = listOf(
    CloudLaunchRate(10_000_000_000L, "₩1,900"),
    CloudLaunchRate(50_000_000_000L, "₩5,900"),
    CloudLaunchRate(100_000_000_000L, "₩10,900"),
)

private fun CloudBillingReplacementTiming.displayText(): String = when (this) {
    CloudBillingReplacementTiming.IMMEDIATE_PRORATED -> "Google Play에서 계산한 차액으로 즉시 변경됩니다."
    CloudBillingReplacementTiming.NEXT_RENEWAL -> "현재 구독을 유지하고 다음 갱신일부터 새 요금제가 적용됩니다."
}

private fun CloudBillingPlan.verifiedAdFree(): Boolean =
    cloudAdRemovalStatus(this, System.currentTimeMillis()) == AdRemovalStatus.VERIFIED_AD_FREE

private fun CloudBillingNotice?.awaitingPurchaseConfirmation(): Boolean =
    this == CloudBillingNotice.PENDING || this == CloudBillingNotice.ALREADY_OWNED

private fun CloudBillingNotice.preservePending(wasWaiting: Boolean): CloudBillingNotice =
    if (wasWaiting && (this == CloudBillingNotice.FAILED || this == CloudBillingNotice.UNAVAILABLE)) {
        CloudBillingNotice.PENDING
    } else this

@Composable
private fun BillingHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun BillingPlanDetails(plan: CloudBillingPlan, autoRenewing: Boolean?) {
    BillingHeading("이용 중인 요금제")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudFeatureIcon(Icons.Outlined.Cloud)
        Text(
            "클라우드 ${billingQuotaLabel(plan.quotaBytes)}",
            modifier = Modifier.weight(1f),
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "이용 중",
            modifier = Modifier.background(Primary, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = OnPrimary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Text("구독 혜택으로 홈 배너 광고가 제거됩니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
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
    } else "홈 배너 광고 제거 · 클라우드 보관 ${billingQuotaLabel(product.quotaBytes)}"

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
        CloudBackupResult.TEST_ACCESS_REQUIRED -> CloudBillingNotice.UNAVAILABLE
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
    CloudBillingNotice.UNAVAILABLE -> "Google Play 결제 이용 자격을 확인해 주세요."
    CloudBillingNotice.FAILED -> "구독 정보를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요."
}
