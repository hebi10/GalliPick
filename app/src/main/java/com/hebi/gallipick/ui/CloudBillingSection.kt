package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudBillingException
import com.hebi.gallipick.data.cloud.CloudBillingNotice
import com.hebi.gallipick.data.cloud.CloudBillingOffer
import com.hebi.gallipick.data.cloud.CloudBillingSession
import com.hebi.gallipick.data.cloud.CloudBillingSnapshot
import com.hebi.gallipick.data.cloud.CloudBillingUpdate
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudBillingSection(
    connection: CloudAccountConnection,
    activity: Activity,
    onPlanUpdated: () -> Unit,
) {
    val billing = connection.billing ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    key(uid) {
        val billingSession = remember(billing, uid) { billing.open(uid) }
        DisposableEffect(billingSession) { onDispose(billingSession::close) }
        CloudBillingControls(billingSession, activity, onPlanUpdated)
    }
}

@Composable
private fun CloudBillingControls(
    session: CloudBillingSession,
    activity: Activity,
    onPlanUpdated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CloudBillingSnapshot?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<CloudBillingNotice?>(null) }
    var confirmation by remember { mutableStateOf<CloudBillingOffer?>(null) }

    suspend fun refresh() {
        busy = true
        notice = null
        snapshot = null
        try {
            snapshot = session.load().also { notice = it.notice }
            onPlanUpdated()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = error.billingNotice()
        } finally {
            busy = false
        }
    }

    fun runBilling(operation: suspend () -> CloudBillingUpdate) {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            try {
                val update = operation()
                update.plan?.let { plan ->
                    val offers = snapshot?.offers.orEmpty().filter { it.product in plan.products }
                    snapshot = CloudBillingSnapshot(plan, offers, autoRenewing = update.autoRenewing)
                    onPlanUpdated()
                }
                notice = update.notice
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.billingNotice()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(session) { refresh() }
    val plan = snapshot?.plan
    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("구독 정보", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (plan?.enabled == true) {
            val currentProduct = plan.products.firstOrNull { it.productId == plan.activeProductId }
            if (currentProduct != null) {
                CloudUsageCard(plan.usedBytes, plan.quotaBytes)
                if (cloudAdRemovalStatus(plan, System.currentTimeMillis()) == AdRemovalStatus.VERIFIED_AD_FREE) {
                    Text("구독 혜택으로 홈 배너 광고가 제거됩니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (!plan.uploadAllowed) Text("새 백업을 추가할 수 없는 상태입니다.", color = Muted)
                snapshot?.autoRenewing?.let {
                    Text(if (it) "Google Play 자동 갱신 켜짐" else "Google Play 자동 갱신 꺼짐",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("서버에서 확인된 이용 중인 구독이 없습니다.", color = Muted)
            }
            plan.expiresAtMs?.let { expiry ->
                val date = DateFormat.getDateInstance().format(Date(expiry))
                Text("서버에서 확인한 현재 구독 기간 종료일 $date", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (plan.activeProductId == null) {
                for (offer in snapshot?.offers.orEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(offer.title, color = Ink, style = MaterialTheme.typography.bodyMedium)
                        Text("매월 ${offer.formattedPrice} · ${bytesLabel(offer.product.quotaBytes)} · 광고 제거", color = Muted)
                        ActionButton("${offer.title} 시험 신청", { confirmation = offer }, enabled = !busy)
                    }
                }
                if (snapshot?.offers.isNullOrEmpty() && !busy) {
                    Text("현재 Google Play에서 결제할 수 있는 시험 상품이 없습니다.", color = Muted)
                }
            }
            ActionButton("구매 복구", { runBilling { session.recover() } }, enabled = !busy)
        } else if (!busy && plan != null) {
            Text("현재 시험 결제가 준비되지 않았습니다. 서버에서 허용된 요금제가 확인되면 구매 가능한 상품을 표시합니다.", color = Muted)
        }
        if (!busy) ActionButton("구독 정보 새로고침", { scope.launch { refresh() } })
        val status = if (busy) "구독 정보를 확인하는 중입니다." else notice?.displayText(plan?.activeProductId != null)
        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted) }
    }

    confirmation?.let { offer ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text("시험 구독 확인") },
            text = {
                Text("${offer.title} · 매월 ${offer.formattedPrice}. Google Play 결제창에서 시험 결제수단을 확인한 후에만 진행해 주세요.", color = Muted)
            },
            confirmButton = {
                TextAction("시험 결제창 열기", {
                    confirmation = null
                    runBilling { session.purchase(activity, offer) }
                })
            },
            dismissButton = { TextAction("취소", { confirmation = null }) },
        )
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

private fun CloudBillingNotice.displayText(hasActiveProduct: Boolean): String = when (this) {
    CloudBillingNotice.VERIFIED -> if (hasActiveProduct) "서버에서 시험 구독을 확인했습니다." else "구매 내역을 확인했습니다. 현재 사용할 수 있는 구독이 없습니다."
    CloudBillingNotice.PENDING -> "결제 결과 확인을 기다리는 중입니다. 결제가 끝난 뒤 구매 복구를 눌러 주세요."
    CloudBillingNotice.CANCELLED -> "Google Play 결제가 취소됐습니다."
    CloudBillingNotice.NO_PURCHASE -> "이 계정에 연결된 구매 내역이 없습니다."
    CloudBillingNotice.ALREADY_OWNED -> "기존 구독이 있습니다. 구매 복구로 확인해 주세요."
    CloudBillingNotice.PRICE_CHANGED -> "상품 정보가 변경됐습니다. 구독 정보를 새로고침하고 다시 확인해 주세요."
    CloudBillingNotice.ACCOUNT_CHANGED -> "계정이 변경됐습니다. 같은 계정에서 다시 확인해 주세요."
    CloudBillingNotice.UNAVAILABLE -> "Google Play 또는 시험 결제 권한을 확인해 주세요."
    CloudBillingNotice.FAILED -> "구독 정보를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요."
}
