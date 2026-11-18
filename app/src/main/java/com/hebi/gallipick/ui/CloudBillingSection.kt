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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.CloudBillingSnapshot
import com.hebi.gallipick.data.cloud.CloudBillingUpdate
import com.hebi.gallipick.data.cloud.activeCloudProductId
import com.hebi.gallipick.data.cloud.activeStandaloneAdFreeProductId
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
        val billingSession = remember(billing, uid) { billing.open(uid) }
        DisposableEffect(billingSession) { onDispose(billingSession::close) }
        CloudBillingControls(billingSession, activity, onGoHome) { plan -> onPlanUpdated(uid, plan) }
    }
}

@Composable
private fun CloudBillingControls(
    session: CloudBillingSession,
    activity: Activity,
    onGoHome: (() -> Unit)?,
    onPlanUpdated: (CloudBillingPlan) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CloudBillingSnapshot?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<CloudBillingNotice?>(null) }
    var confirmation by remember { mutableStateOf<CloudBillingOffer?>(null) }
    var refreshAfterManagement by remember { mutableStateOf(false) }
    var checkingPayment by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val waitingForPurchase = notice.awaitingPurchaseConfirmation()
        busy = true
        checkingPayment = waitingForPurchase
        notice = null
        snapshot = null
        try {
            val refreshed = session.load()
            snapshot = refreshed
            notice = refreshed.notice
            if (notice == null && waitingForPurchase && refreshed.plan.enabled) {
                notice = if (refreshed.plan.verifiedAdFree()) CloudBillingNotice.VERIFIED else CloudBillingNotice.NO_PURCHASE
            }
            onPlanUpdated(refreshed.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = error.billingNotice().preservePending(wasWaiting = waitingForPurchase)
        } finally {
            busy = false
            checkingPayment = false
        }
    }

    fun runBilling(fromCheckout: Boolean = false, operation: suspend () -> CloudBillingUpdate) {
        if (busy) return
        val waitingForPurchase = notice.awaitingPurchaseConfirmation()
        busy = true
        checkingPayment = fromCheckout || waitingForPurchase
        notice = null
        scope.launch {
            try {
                val update = operation()
                update.plan?.let { plan ->
                    val offers = snapshot?.offers.orEmpty().filter { it.product in plan.products }
                    snapshot = CloudBillingSnapshot(plan, offers, autoRenewing = update.autoRenewing)
                    onPlanUpdated(plan)
                }
                notice = update.notice
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.billingNotice().preservePending(wasWaiting = waitingForPurchase)
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
        } else if (notice.awaitingPurchaseConfirmation()) {
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
    val waitingForPurchase = verifiedProductId == null && notice.awaitingPurchaseConfirmation()
    val showOffers = !busy && !waitingForPurchase && notice != CloudBillingNotice.PRICE_CHANGED &&
        notice != CloudBillingNotice.PURCHASE_LINKED_TO_ANOTHER_ACCOUNT &&
        plan?.enabled == true && verifiedProductId == null
    val testPurchasesOnly = plan?.testPurchasesOnly != false
    var managementFailed by remember(verifiedProductId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (purchaseConfirmed) {
            CloudAccountCard {
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CloudFeatureIcon(Icons.Outlined.CheckCircle)
                    BillingHeading("홈 배너 광고가 제거됐어요")
                }
                onGoHome?.let { CloudPrimaryAction("홈으로", it) }
            }
        }

        if (showOffers) {
            CloudAccountCard {
                BillingHeading("신청 가능한 요금제")
                notice?.let { CloudStatusMessage(it.displayText()) }
                val offers = snapshot?.offers.orEmpty()
                offers.forEachIndexed { index, offer ->
                    if (index > 0) HorizontalDivider(color = Line)
                    Text(offer.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${offer.intervalLabel} ${offer.formattedPrice}",
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        offer.benefitText,
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CloudStatusMessage("${offer.intervalLabel} 자동 갱신되며 Google Play에서 관리하거나 취소할 수 있습니다.")
                    val purchaseLabel = if (offer.product.isStandaloneAdFree) {
                        if (offer.product.billingPeriod == "P1Y") "광고 제거 연간 구독" else "광고 제거 월간 구독"
                    } else "Google Play로 계속"
                    CloudPrimaryAction(purchaseLabel, { confirmation = offer })
                }
                if (offers.isEmpty()) {
                    CloudStatusMessage(if (testPurchasesOnly) "현재 Google Play에서 결제할 수 있는 시험 상품이 없습니다."
                        else "현재 Google Play에서 결제할 수 있는 요금제가 없습니다.")
                }
                CloudStatusMessage("기기에서 사진을 정리하는 기능은 무료로 계속 이용할 수 있습니다.")
            }
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
                        CloudStatusMessage("Google Play에서 결제 내역을 확인할 수 있습니다. 결제가 확인되면 홈 배너 광고가 자동으로 사라집니다.")
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
                            !plan.enabled -> CloudStatusMessage(if (testPurchasesOnly) "현재 시험 결제를 이용할 수 없습니다."
                                else "현재 구독을 이용할 수 없습니다.")
                            notice != null -> CloudStatusMessage(checkNotNull(notice).displayText())
                            else -> CloudStatusMessage("현재 구독의 유효기간을 확인해 주세요.")
                        }
                    }
                }
            }
        }

        if (!busy && !waitingForPurchase) {
            Column(Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(horizontal = 12.dp)) {
                if (verifiedProductId != null) {
                    BillingActionRow("Google Play에서 구독 관리", Icons.Outlined.PlayArrow) {
                        refreshAfterManagement = openPlaySubscription(activity, verifiedProductId)
                        managementFailed = !refreshAfterManagement
                    }
                    HorizontalDivider(color = Line)
                }
                if (plan?.enabled == true) {
                    BillingActionRow("구매 복구", Icons.Outlined.Restore) { runBilling { session.recover() } }
                    HorizontalDivider(color = Line)
                }
                BillingActionRow("구독 정보 새로고침", Icons.Outlined.Refresh) {
                    managementFailed = false
                    scope.launch { refresh() }
                }
            }
            if (managementFailed) CloudStatusMessage("Google Play 구독 관리 화면을 열지 못했습니다.")
        }
    }

    confirmation?.let { offer ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text(if (testPurchasesOnly) "시험 구독 확인" else "구독 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${offer.title} · ${offer.intervalLabel} ${offer.formattedPrice}", color = Ink)
                    Text(offer.benefitText, color = Muted)
                    Text("${offer.intervalLabel} 자동 갱신되며 Google Play에서 취소할 수 있습니다.", color = Muted)
                    val paymentHelp = if (testPurchasesOnly) {
                        "Google Play 결제창에서 시험 결제수단을 확인한 뒤 진행해 주세요."
                    } else "Google Play 결제창에서 금액과 결제수단을 확인한 뒤 진행해 주세요."
                    Text(paymentHelp, color = Muted)
                }
            },
            confirmButton = {
                TextAction(if (testPurchasesOnly) "시험 결제창 열기" else "결제창 열기", {
                    confirmation = null
                    runBilling(fromCheckout = true) { session.purchase(activity, offer) }
                })
            },
            dismissButton = { TextAction("취소", { confirmation = null }) },
        )
    }
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
    CloudBillingNotice.PENDING, CloudBillingNotice.ALREADY_OWNED -> "결제 결과를 확인하고 있습니다."
    CloudBillingNotice.CANCELLED -> "Google Play 결제가 취소됐습니다. 원하면 요금제를 다시 선택할 수 있습니다."
    CloudBillingNotice.NO_PURCHASE -> "이 계정에 연결된 구매 내역이 없습니다. 이미 결제했다면 구매할 때 연결한 계정을 확인해 주세요."
    CloudBillingNotice.PRICE_CHANGED -> "상품 정보가 변경됐습니다. 구독 정보를 새로고침하고 다시 확인해 주세요."
    CloudBillingNotice.ACCOUNT_CHANGED -> "계정이 변경됐습니다. 같은 계정에서 다시 확인해 주세요."
    CloudBillingNotice.PURCHASE_LINKED_TO_ANOTHER_ACCOUNT -> "다른 GalliPick 계정에 연결된 Google Play 구독이 있습니다. 구매할 때 연결한 계정을 확인해 주세요."
    CloudBillingNotice.UNAVAILABLE -> "Google Play 결제 이용 자격을 확인해 주세요."
    CloudBillingNotice.FAILED -> "구독 정보를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요."
}
