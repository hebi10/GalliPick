package com.hebi.gallipick.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.data.cloud.CloudAccountOperation
import com.hebi.gallipick.data.cloud.CloudBillingPlan

internal enum class CloudRoute(val title: String, val description: String) {
    OVERVIEW("클라우드", ""),
    LIBRARY("내 보관함", "클라우드에 확인된 사진과 동영상을 살펴봅니다."),
    BACKUP("원본 백업", "기기에서 선택한 원본을 검증 후 보관합니다."),
    AUTO_BACKUP("자동 백업", "선택 폴더의 백업 조건과 진행 상태를 확인합니다."),
    SPACE("공간 확보 검토", "검증된 서버 원본과 기기 파일을 비교합니다."),
    BILLING("구독 시험", "Google Play의 시험 구독 상태를 확인합니다."),
}

internal enum class CloudAccessState {
    UNAVAILABLE, SIGNED_OUT, CHOOSING_ACCOUNT, SIGNING_OUT, SIGNED_IN,
}

internal enum class CloudSubscriptionUiState {
    LOADING, NONE, ACTIVE, EXPIRED, RESTRICTED, ERROR,
}

internal enum class CloudPurchaseUiState {
    READY, PLAY_OPEN, PENDING, VERIFYING, VERIFIED, CANCELLED, ERROR,
}

internal fun cloudAccessState(
    available: Boolean,
    accountUid: String?,
    operation: CloudAccountOperation?,
): CloudAccessState = when {
    !available -> CloudAccessState.UNAVAILABLE
    operation == CloudAccountOperation.CHOOSING_ACCOUNT -> CloudAccessState.CHOOSING_ACCOUNT
    operation == CloudAccountOperation.SIGNING_OUT -> CloudAccessState.SIGNING_OUT
    accountUid.isNullOrBlank() -> CloudAccessState.SIGNED_OUT
    else -> CloudAccessState.SIGNED_IN
}

internal fun cloudVisibleRoute(
    requested: CloudRoute,
    access: CloudAccessState,
): CloudRoute = if (access == CloudAccessState.SIGNED_IN) requested else CloudRoute.OVERVIEW

internal fun cloudSubscriptionUiState(
    loading: Boolean,
    failed: Boolean,
    plan: CloudBillingPlan?,
    nowMillis: Long,
): CloudSubscriptionUiState = when {
    loading -> CloudSubscriptionUiState.LOADING
    failed || plan == null -> CloudSubscriptionUiState.ERROR
    !plan.enabled -> CloudSubscriptionUiState.RESTRICTED
    plan.activeProductId == null -> CloudSubscriptionUiState.NONE
    plan.expiresAtMs != null && plan.expiresAtMs <= nowMillis -> CloudSubscriptionUiState.EXPIRED
    else -> CloudSubscriptionUiState.ACTIVE
}

@Composable
internal fun CloudHeader(route: CloudRoute, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (route != CloudRoute.OVERVIEW) {
            TextAction("뒤로", onBack)
        }
        Text(
            route.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
internal fun CloudStatusMessage(message: String) {
    Text(
        message,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun CloudAccountCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
internal fun CloudUsageCard(usedBytes: Long, quotaBytes: Long) {
    val verifiedUsage = usedBytes.coerceAtLeast(0)
    val verifiedQuota = quotaBytes.coerceAtLeast(0)
    Text("서버에서 확인된 사용량 ${bytesLabel(verifiedUsage)} / ${bytesLabel(verifiedQuota)}", color = Ink)
}

@Composable
internal fun CloudActionCard(route: CloudRoute, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .galliCardSurface(AppSurface)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("cloud-route-${route.name}")
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(route.title, style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(route.description, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
internal fun CloudPrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ActionButton(label, onClick, modifier.fillMaxWidth(), filled = true, enabled = enabled)
}
