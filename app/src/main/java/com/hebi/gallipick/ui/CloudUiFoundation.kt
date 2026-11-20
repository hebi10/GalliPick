package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.data.cloud.CloudAccountOperation
import com.hebi.gallipick.data.cloud.CloudBillingPlan

internal enum class CloudRoute(val title: String, val description: String) {
    OVERVIEW("클라우드", ""),
    LIBRARY("내 보관함", "클라우드에 보관한 사진과 동영상을 봅니다."),
    BACKUP("원본 백업", "선택한 원본을 클라우드에 보관합니다."),
    SECRET("시크릿 클라우드", "비밀번호로 잠긴 사진과 동영상을 보관합니다."),
    AUTO_BACKUP("자동 백업", "선택 폴더의 자동 백업을 설정합니다."),
    SPACE("공간 확보 검토", "백업이 확인된 기기 파일을 검토합니다."),
    BILLING_SIGN_IN("광고 제거", "클라우드 구독 혜택 보기"),
    BILLING("요금제·결제", "요금제를 선택하고 구독을 신청하거나 관리합니다."),
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
        Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (route != CloudRoute.OVERVIEW) {
            IconAction("뒤로", Icons.AutoMirrored.Outlined.ArrowBack, onClick = onBack)
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
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(GalliUiMetrics.cardPadding),
        verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
    ) {
        content()
    }
}

@Composable
internal fun CloudFeatureIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier.size(size).background(IconContainer, RoundedCornerShape(GalliUiMetrics.navigationCorner)),
        contentAlignment = Alignment.Center,
    ) {
        GalliIcon(icon, null, Modifier.size(size * 0.55f))
    }
}

@Composable
internal fun CloudActionGroup(vararg routes: CloudRoute, onNavigate: (CloudRoute) -> Unit) {
    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface),
    ) {
        routes.forEachIndexed { index, route ->
            if (index > 0) {
                HorizontalDivider(Modifier.padding(horizontal = GalliUiMetrics.cardPadding), color = Line)
            }
            Row(
                Modifier.fillMaxWidth()
                    .testTag("cloud-route-${route.name}")
                    .semantics { role = Role.Button }
                    .plainClick { onNavigate(route) }
                    .padding(horizontal = GalliUiMetrics.cardPadding, vertical = GalliUiMetrics.cardSpacing),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CloudFeatureIcon(route.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(route.title, style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(route.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                GalliIcon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    null,
                    Modifier.size(20.dp),
                    tint = Muted,
                )
            }
        }
    }
}

private val CloudRoute.icon: ImageVector get() = when (this) {
    CloudRoute.OVERVIEW -> Icons.Outlined.Cloud
    CloudRoute.LIBRARY -> Icons.Outlined.PhotoLibrary
    CloudRoute.BACKUP -> Icons.Outlined.CloudUpload
    CloudRoute.SECRET -> Icons.Outlined.Lock
    CloudRoute.AUTO_BACKUP -> Icons.Outlined.Sync
    CloudRoute.SPACE -> Icons.Outlined.Storage
    CloudRoute.BILLING_SIGN_IN -> Icons.Outlined.Cloud
    CloudRoute.BILLING -> Icons.Outlined.CreditCard
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
