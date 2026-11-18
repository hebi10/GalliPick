package com.hebi.gallipick.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountNotice
import com.hebi.gallipick.data.cloud.CloudAccountOperation
import kotlinx.coroutines.launch

@Composable
internal fun CloudScreen(
    connection: CloudAccountConnection,
    onBackFromAdRemoval: (() -> Unit)? = null,
    onBillingScreenVisible: (Boolean) -> Unit = {},
    content: @Composable (CloudRoute) -> Unit,
) {
    val account by connection.session.account.collectAsStateWithLifecycle()
    val status by connection.session.status.collectAsStateWithLifecycle()
    val access = cloudAccessState(connection.session.available, account?.uid, status.operation)
    var requestedRoute by rememberSaveable(account?.uid) { mutableStateOf(CloudRoute.OVERVIEW) }
    val route = if (onBackFromAdRemoval != null && connection.billing != null &&
        access != CloudAccessState.UNAVAILABLE
    ) {
        if (access == CloudAccessState.SIGNED_IN) CloudRoute.BILLING else CloudRoute.BILLING_SIGN_IN
    } else {
        cloudVisibleRoute(requestedRoute, access)
    }
    val onBack = onBackFromAdRemoval ?: {
        requestedRoute = CloudRoute.OVERVIEW
    }
    BackHandler(enabled = route != CloudRoute.OVERVIEW, onBack = onBack)
    val reportBillingVisibility by rememberUpdatedState(onBillingScreenVisible)
    if (route == CloudRoute.BILLING) {
        DisposableEffect(Unit) {
            reportBillingVisibility(true)
            onDispose { reportBillingVisibility(false) }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 660.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudHeader(route, onBack)
            when {
                access == CloudAccessState.UNAVAILABLE ->
                    CloudStatusMessage("이 빌드에서 클라우드 로그인 설정을 확인할 수 없습니다.")
                route != CloudRoute.OVERVIEW -> content(route)
                else -> {
                    content(CloudRoute.OVERVIEW)
                    val onNavigate: (CloudRoute) -> Unit = { requestedRoute = it }
                    val billingAvailable = connection.billing != null
                    if (access == CloudAccessState.SIGNED_IN) {
                        if (billingAvailable) {
                            CloudActionGroup(CloudRoute.BILLING, onNavigate = onNavigate)
                        } else {
                            CloudBillingOverviewNotice("이 빌드에서는 결제 기능을 사용할 수 없습니다.")
                        }
                        if (connection.backup != null) {
                            CloudActionGroup(CloudRoute.LIBRARY, CloudRoute.BACKUP, onNavigate = onNavigate)
                            CloudActionGroup(CloudRoute.AUTO_BACKUP, CloudRoute.SPACE, onNavigate = onNavigate)
                        }
                    } else if (access == CloudAccessState.SIGNED_OUT) {
                        CloudBillingOverviewNotice(
                            if (billingAvailable) "Google 로그인 후 요금제와 구독 상태를 확인할 수 있습니다."
                            else "이 빌드에서는 결제 기능을 사용할 수 없습니다.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudBillingOverviewNotice(message: String) {
    CloudAccountCard {
        Text(CloudRoute.BILLING.title, style = MaterialTheme.typography.titleSmall)
        CloudStatusMessage(message)
    }
}

@Composable
internal fun CloudAccountSettings(
    connection: CloudAccountConnection,
    activity: Activity,
    forAdRemoval: Boolean = false,
) {
    val session = connection.session
    if (!session.available) {
        Text("이 빌드에서 클라우드 로그인 설정을 확인할 수 없습니다.", color = Muted)
        return
    }

    val account by session.account.collectAsStateWithLifecycle()
    val accountStatus by session.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val signedIn = account
    val actionsEnabled = accountStatus.operation == null
    val chooseAccount: () -> Unit = { scope.launch { connection.chooseAccount(activity) } }
    if (forAdRemoval) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudFeatureIcon(Icons.Outlined.Cloud, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "홈 배너 광고 제거",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (connection.backup != null) "클라우드 구독에 포함됩니다."
                    else "Google 로그인 후 구독 요금제를 확인할 수 있습니다.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider(color = Line)
        Text(
            "기기 사진·동영상 정리는 로그인이나 구독 없이 이용할 수 있습니다.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        CloudPrimaryAction("Google로 로그인", chooseAccount, enabled = actionsEnabled)
    } else if (signedIn == null) {
        Text(
            "Google 계정 연결",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "클라우드에 원본을 보관하려면 로그인해 주세요. 기기에서 사진·동영상을 정리하는 기능은 로그인 없이 이용할 수 있습니다.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        CloudPrimaryAction("Google로 로그인", chooseAccount, enabled = actionsEnabled)
    } else {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).background(IconContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GalliIcon(Icons.Outlined.Person, null, Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    signedIn.displayName?.takeIf { it.isNotBlank() } ?: "Google 계정",
                    style = MaterialTheme.typography.titleSmall,
                )
                signedIn.email?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        CloudAccountActions(chooseAccount, { scope.launch { session.signOut() } }, actionsEnabled)
    }
    val statusText = when (accountStatus.operation) {
        CloudAccountOperation.CHOOSING_ACCOUNT -> "Google 계정을 확인하는 중입니다."
        CloudAccountOperation.SIGNING_OUT -> "로그아웃하는 중입니다."
        null -> when (accountStatus.notice) {
            CloudAccountNotice.CANCELLED -> "Google 계정 선택을 취소했습니다."
            CloudAccountNotice.NO_GOOGLE_ACCOUNT -> "선택할 Google 계정을 찾지 못했습니다. 기기의 계정 설정을 확인해 주세요."
            CloudAccountNotice.GOOGLE_UNAVAILABLE -> "Google 계정 선택을 열 수 없습니다. Google Play 서비스를 확인해 주세요."
            CloudAccountNotice.NETWORK_ERROR -> "네트워크 연결을 확인한 뒤 다시 시도해 주세요."
            CloudAccountNotice.SIGN_IN_FAILED -> "Google 로그인에 실패했습니다. 다시 시도해 주세요."
            CloudAccountNotice.SIGN_OUT_FAILED -> "로그아웃에 실패했습니다. 다시 시도해 주세요."
            CloudAccountNotice.CREDENTIAL_CLEAR_FAILED -> if (signedIn == null) {
                "로그아웃했습니다. 다음에 로그인할 때 이전 Google 계정이 제안될 수 있습니다."
            } else {
                "Google 계정 선택 상태를 초기화하지 못했습니다. 다시 시도해 주세요."
            }
            null -> null
        }
    }
    statusText?.let { CloudStatusMessage(it) }
}

@Composable
private fun CloudAccountActions(onChangeAccount: () -> Unit, onSignOut: () -> Unit, enabled: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val buttons: @Composable (Modifier, Modifier) -> Unit = { changeModifier, signOutModifier ->
            ActionButton("Google 계정 변경", onChangeAccount, changeModifier, enabled = enabled)
            ActionButton("로그아웃", onSignOut, signOutModifier, enabled = enabled)
        }
        if (maxWidth < 252.dp * fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons(Modifier.weight(1.2f), Modifier.weight(1f))
            }
        }
    }
}
