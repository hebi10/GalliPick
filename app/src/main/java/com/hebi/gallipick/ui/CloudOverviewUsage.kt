package com.hebi.gallipick.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBillingException
import com.hebi.gallipick.data.cloud.CloudBillingNotice
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.activeCloudProductId
import com.hebi.gallipick.data.cloud.productionBillingProduct
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException

internal data class CloudOverviewUsage(val usedBytes: Long, val quotaBytes: Long?, val verifiedMediaCount: Int? = null) {
    val usedFraction: Double? get() = quotaBytes?.let { usedBytes.toDouble() / it }
}

internal fun cloudOverviewUsage(
    plan: CloudBillingPlan,
    production: Boolean,
    nowMillis: Long,
): CloudOverviewUsage? {
    if (!plan.enabled || plan.usedBytes < 0L || plan.quotaBytes < 0L || (production && !plan.backupAvailable)) {
        return null
    }
    val count = plan.verifiedMediaCount?.takeIf { production && it >= 0 }
    if (!production) return CloudOverviewUsage(plan.usedBytes, null, count)
    val active = plan.activeCloudProductId(nowMillis)?.let { activeId ->
        val product = productionBillingProduct(activeId, plan.activeBasePlanId.orEmpty()) ?: return null
        if (product.isStandaloneAdFree || product !in plan.products + plan.legacyRecoveryProducts) return null
        product
    }
    val effectiveQuota = maxOf(active?.quotaBytes ?: 0L, plan.verifiedManualCloudQuota(nowMillis))
    if (effectiveQuota > 0L && effectiveQuota != plan.quotaBytes) return null
    return CloudOverviewUsage(plan.usedBytes, effectiveQuota.takeIf { it > 0L }, count)
}

private fun CloudBillingPlan.verifiedManualCloudQuota(nowMillis: Long): Long {
    val expectedQuota = when (manualGrantKind) {
        "CLOUD_10GB" -> 10_000_000_000L
        "CLOUD_50GB" -> 50_000_000_000L
        "CLOUD_100GB" -> 100_000_000_000L
        else -> return 0L
    }
    return expectedQuota.takeIf {
        manualGrantQuotaBytes == it && manualGrantExpiresAtMs == manualAdFreeExpiresAtMs &&
            manualGrantExpiresAtMs?.let { expiry -> expiry > nowMillis } == true
    } ?: 0L
}

private sealed interface CloudOverviewUsageStatus {
    data object Loading : CloudOverviewUsageStatus
    data object Unavailable : CloudOverviewUsageStatus
    data object AccountRestricted : CloudOverviewUsageStatus
    data object Failed : CloudOverviewUsageStatus
    data class Ready(val usage: CloudOverviewUsage) : CloudOverviewUsageStatus
}

@Composable
internal fun CloudOverviewStorageCard(
    connection: CloudAccountConnection,
    uid: String,
    showLibraryStatus: Boolean,
) {
    val billing = connection.billing
    if (billing == null) {
        if (showLibraryStatus) CloudAccountCard { CloudStatusMessage("이 빌드에서 보관함 이용 상태를 확인할 수 없습니다.") }
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var retry by remember(connection, uid) { mutableIntStateOf(0) }
    var status by remember(connection, uid) { mutableStateOf<CloudOverviewUsageStatus>(CloudOverviewUsageStatus.Loading) }
    LaunchedEffect(connection, uid, lifecycle, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (connection.session.account.value?.uid != uid) return@repeatOnLifecycle
            status = CloudOverviewUsageStatus.Loading
            val result = try {
                val plan = billing.open(uid).use { it.loadOverviewPlan() }
                val usage = cloudOverviewUsage(plan, connection.usesProductionBackupPlan, System.currentTimeMillis())
                if (usage == null) CloudOverviewUsageStatus.Unavailable else CloudOverviewUsageStatus.Ready(usage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if ((error as? CloudBackupException)?.permissionDenied == true ||
                    (error as? CloudBillingException)?.notice == CloudBillingNotice.ACCOUNT_ACCESS_REQUIRED
                ) CloudOverviewUsageStatus.AccountRestricted else CloudOverviewUsageStatus.Failed
            }
            if (connection.session.account.value?.uid == uid) status = result
        }
    }

    CloudAccountCard {
        if (status !is CloudOverviewUsageStatus.Ready) CloudOverviewStorageHeading()
        when (val current = status) {
            CloudOverviewUsageStatus.Loading -> CloudStatusMessage(
                if (showLibraryStatus) "저장공간 사용량과 보관함 이용 상태를 확인하는 중입니다."
                else "저장공간 사용량을 확인하는 중입니다.",
            )
            CloudOverviewUsageStatus.Unavailable -> CloudStatusMessage(
                if (showLibraryStatus) "현재 계정에서는 저장공간과 보관함 정보를 확인할 수 없습니다."
                else "저장공간 정보를 사용할 수 없습니다.",
            )
            CloudOverviewUsageStatus.AccountRestricted -> CloudStatusMessage(
                "현재 계정은 클라우드 저장공간 정보를 확인할 수 없습니다. Google 계정을 확인해 주세요.",
            )
            CloudOverviewUsageStatus.Failed -> {
                CloudStatusMessage(
                    if (showLibraryStatus) "저장공간과 보관함 정보를 불러오지 못했습니다. 연결을 확인해 주세요."
                    else "저장공간 사용량을 확인하지 못했습니다. 연결을 확인해 주세요.",
                )
                ActionButton("다시 확인", { retry++ })
            }
            is CloudOverviewUsageStatus.Ready -> {
                CloudOverviewStorageDetails(current.usage)
                if (showLibraryStatus) CloudStatusMessage("현재 계정에서 보관함을 열 수 없습니다.")
            }
        }
    }
}

@Composable
private fun CloudOverviewStorageHeading(summary: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CloudFeatureIcon(Icons.Outlined.Storage)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("저장공간 사용량", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            summary?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CloudOverviewStorageDetails(usage: CloudOverviewUsage) {
    val context = LocalContext.current
    val used = Formatter.formatShortFileSize(context, usage.usedBytes)
    val quota = usage.quotaBytes?.let { Formatter.formatShortFileSize(context, it) }
    val fraction = usage.usedFraction
    val percentage = fraction?.let {
        val formatted = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
        if (it > 0.0 && it < 0.001) "${formatted.format(0.001)} 미만" else formatted.format(it)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CloudOverviewStorageHeading(if (quota == null) "사용 중 $used" else "$used / $quota ($percentage)")
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = Primary,
                trackColor = Panel,
            )
        } else {
            Text("이용 중인 저장공간 요금제가 없습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (usage.usedBytes > 0L) {
            Text("백업을 위해 예약한 공간이 포함됩니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        usage.verifiedMediaCount?.let { count ->
            Text("검증된 보관 항목 ${NumberFormat.getIntegerInstance().format(count)}개",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
