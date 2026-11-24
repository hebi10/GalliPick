package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.data.cloud.CloudBillingOffer
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudBillingProduct
import com.hebi.gallipick.data.cloud.CloudBillingReplacementOffer
import com.hebi.gallipick.data.cloud.CloudBillingReplacementTiming
import com.hebi.gallipick.data.cloud.productionCloudQuotaBytes
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class BillingPeriod(val label: String, val unit: String, val rateUnit: String) {
    MONTHLY("월간", "월", "월"), ANNUAL("연간", "연", "년"),
}

internal val CloudBillingProduct.period: BillingPeriod
    get() = if (billingPeriod == "P1Y") BillingPeriod.ANNUAL else BillingPeriod.MONTHLY

internal val CloudBillingProduct.pickerLabel: String
    get() = if (isStandaloneAdFree) "광고 제거만" else billingQuotaLabel(quotaBytes)

internal val CloudBillingProduct.selectionId: String
    get() = "$productId:$basePlanId"

@Composable
internal fun BillingPlanPicker(
    offers: List<CloudBillingOffer>,
    selectedProductId: String?,
    unavailableLabel: String,
    cloudUnavailableLabel: String = unavailableLabel,
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
                    unavailableLabel = cloudUnavailableLabel, onSelect = { id?.let(onSelect) },
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
    BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(SelectedContainer) else Modifier)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val stackPrice = maxWidth < 172.dp * LocalDensity.current.fontScale
        val foreground = if (enabled) Ink else Muted
        val value = offer?.let { "${it.formattedPrice}/${it.product.period.rateUnit}" } ?: unavailableLabel
        val valueStyle = if (enabled) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(selectedColor = Primary, unselectedColor = Muted),
            )
            if (stackPrice) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, color = foreground, style = MaterialTheme.typography.titleSmall)
                    Text(value, color = foreground, style = valueStyle)
                }
            } else {
                Text(label, Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.titleSmall)
                Text(value, color = foreground, style = valueStyle, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun BillingPlanDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Line)
}

@Composable
internal fun BillingCheckoutFooter(
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
            BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                if (maxWidth < 220.dp * LocalDensity.current.fontScale) {
                    Column {
                        TextAction("구매 복구", onRestore, Modifier.fillMaxWidth())
                        TextAction("구독 정보 새로고침", onRefresh, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextAction("구매 복구", onRestore, Modifier.weight(1f), MaterialTheme.typography.bodyMedium)
                        TextAction("구독 정보 새로고침", onRefresh, Modifier.weight(1.65f), MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else if (onRefresh != null) TextAction("구독 정보 새로고침", onRefresh)
        else if (onRestore != null) TextAction("구매 복구", onRestore)
    }
}

@Composable
internal fun BillingHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun BillingPlanDetails(plan: CloudBillingPlan, autoRenewing: Boolean?) {
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
internal fun BillingActionRow(label: String, icon: ImageVector, onClick: () -> Unit) {
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

internal fun billingQuotaLabel(bytes: Long): String {
    val gb = 1_000_000_000L
    val gib = 1_073_741_824L
    return when {
        bytes >= gb && bytes % gb == 0L -> "${bytes / gb} GB"
        bytes >= gib && bytes % gib == 0L -> "${bytes / gib} GiB"
        else -> bytesLabel(bytes)
    }
}

internal fun billingDateLabel(expiry: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.KOREA).format(Date(expiry))

private val CloudBillingOffer.intervalLabel: String
    get() = if (product.billingPeriod == "P1Y") "매년" else "매월"

private val CloudBillingOffer.benefitText: String
    get() = if (product.isStandaloneAdFree) {
        "광고 제거 · 클라우드 저장공간 없음"
    } else "광고 제거 · 클라우드 보관 ${billingQuotaLabel(product.quotaBytes)}"

internal fun CloudBillingReplacementTiming.displayText(): String = when (this) {
    CloudBillingReplacementTiming.IMMEDIATE_PRORATED -> "Google Play에서 계산한 차액으로 즉시 변경됩니다."
    CloudBillingReplacementTiming.NEXT_RENEWAL -> "현재 구독을 유지하고 다음 갱신일부터 새 요금제가 적용됩니다."
}

@Composable
internal fun BillingConfirmationDialog(
    offer: CloudBillingOffer,
    replacement: CloudBillingReplacementOffer?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextAction("Google Play 결제창 열기", onConfirm)
        },
        dismissButton = { TextAction("취소", onDismiss) },
    )
}
