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
    val activeId = plan.activeCloudProductId(nowMillis)
    val count = plan.verifiedMediaCount?.takeIf { production && it >= 0 }
    if (!production || activeId == null) return CloudOverviewUsage(plan.usedBytes, null, count)
    val active = productionBillingProduct(activeId, plan.activeBasePlanId.orEmpty()) ?: return null
    if (active.isStandaloneAdFree || active.quotaBytes != plan.quotaBytes || active !in plan.products) return null
    return CloudOverviewUsage(plan.usedBytes, active.quotaBytes, count)
}

private sealed interface CloudOverviewUsageStatus {
    data object Loading : CloudOverviewUsageStatus
    data object Unavailable : CloudOverviewUsageStatus
    data object Failed : CloudOverviewUsageStatus
    data class Ready(val usage: CloudOverviewUsage) : CloudOverviewUsageStatus
}

@Composable
internal fun CloudOverviewStorageCard(connection: CloudAccountConnection, uid: String) {
    val billing = connection.billing ?: return
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
            } catch (_: Exception) {
                CloudOverviewUsageStatus.Failed
            }
            if (connection.session.account.value?.uid == uid) status = result
        }
    }

    CloudAccountCard {
        if (status !is CloudOverviewUsageStatus.Ready) CloudOverviewStorageHeading()
        when (val current = status) {
            CloudOverviewUsageStatus.Loading -> CloudStatusMessage("저장공간 사용량을 확인하는 중입니다.")
            CloudOverviewUsageStatus.Unavailable -> CloudStatusMessage("저장공간 정보를 사용할 수 없습니다.")
            CloudOverviewUsageStatus.Failed -> {
                CloudStatusMessage("저장공간 사용량을 확인하지 못했습니다.")
                ActionButton("다시 확인", { retry++ })
            }
            is CloudOverviewUsageStatus.Ready -> CloudOverviewStorageDetails(current.usage)
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
