package com.hebi.gallipick.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountNotice
import com.hebi.gallipick.data.cloud.CloudAccountOperation
import com.hebi.gallipick.data.cloud.CloudAccountStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun RefreshProductionCloudBackupAccess(connection: CloudAccountConnection?) {
    val availability = connection?.backupAvailability ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(connection, uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    connection.refreshBackupAccess(uid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep verified read-only browsing metadata while the connection retries.
                }
                val access = availability.value?.takeIf { it.uid == uid }?.access
                val untilExpiry = access?.uploadExpiryMillis?.minus(System.currentTimeMillis())
                    ?.takeIf { it > 0L }
                delay(minOf(300_000L, untilExpiry?.coerceAtLeast(5_000L) ?: 300_000L))
            }
        }
    }
}

internal val LocalCheckoutAccountChange = staticCompositionLocalOf<(suspend (Activity) -> Unit)?> { null }

@Composable
internal fun CloudScreen(
    connection: CloudAccountConnection,
    onBackFromAdRemoval: (() -> Unit)? = null,
    onBillingScreenVisible: (Boolean) -> Unit = {},
    showSecretCloud: Boolean = false,
    onOverviewVisible: (Boolean) -> Unit = {},
    content: @Composable (CloudRoute) -> Unit,
) {
    val account by connection.session.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val status by connection.session.status.collectAsStateWithLifecycle()
    val access = cloudAccessState(connection.session.available, account?.uid, status.operation)
    val configuredBackup = connection.backupAvailability
    val observation = configuredBackup?.collectAsStateWithLifecycle()?.value
    val backup = connection.backup.takeIf { configuredBackup == null || observation?.uid == account?.uid }
    val namespace = observation?.access?.namespace
    var requestedRoute by rememberSaveable(account?.uid) { mutableStateOf(CloudRoute.OVERVIEW) }
    var checkoutUid by rememberSaveable { mutableStateOf<String?>(null) }
    var checkoutSignInPending by rememberSaveable { mutableStateOf(false) }
    var switchingCheckoutAccount by remember { mutableStateOf(false) }
    val canOpenBilling = connection.billing != null && access != CloudAccessState.UNAVAILABLE
    val accountUid = account?.uid
    LaunchedEffect(accountUid, access, checkoutSignInPending, switchingCheckoutAccount) {
        if (!switchingCheckoutAccount && checkoutSignInPending && access == CloudAccessState.SIGNED_IN) {
            checkoutUid = accountUid
            checkoutSignInPending = false
        } else if (!switchingCheckoutAccount && accountUid == connection.session.account.value?.uid && checkoutUid != accountUid) {
            checkoutUid = null
        }
    }
    val requested = when {
        canOpenBilling && onBackFromAdRemoval != null ->
            if (account != null) CloudRoute.BILLING else CloudRoute.BILLING_SIGN_IN
        canOpenBilling && (checkoutSignInPending || switchingCheckoutAccount ||
            (checkoutUid != null && checkoutUid == accountUid && access != CloudAccessState.SIGNING_OUT)) ->
            CloudRoute.BILLING
        else -> cloudVisibleRoute(requestedRoute, access)
    }
    val route = when (requested) {
        CloudRoute.LIBRARY, CloudRoute.BACKUP, CloudRoute.AUTO_BACKUP, CloudRoute.SPACE ->
            if (backup != null) requested else CloudRoute.OVERVIEW
        CloudRoute.SECRET -> if (showSecretCloud) requested else CloudRoute.OVERVIEW
        else -> requested
    }
    val onBack = onBackFromAdRemoval ?: {
        checkoutUid = null
        checkoutSignInPending = false
        switchingCheckoutAccount = false
        requestedRoute = CloudRoute.OVERVIEW
    }
    BackHandler(enabled = route != CloudRoute.OVERVIEW, onBack = onBack)
    val reportOverviewVisibility by rememberUpdatedState(onOverviewVisible)
    DisposableEffect(route) {
        reportOverviewVisibility(route == CloudRoute.OVERVIEW)
        onDispose { reportOverviewVisibility(true) }
    }
    val reportBillingVisibility by rememberUpdatedState(onBillingScreenVisible)
    if (route == CloudRoute.BILLING) {
        DisposableEffect(Unit) {
            reportBillingVisibility(true)
            onDispose { reportBillingVisibility(false) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 660.dp).fillMaxSize()) {
                Box(Modifier.padding(horizontal = 8.dp)) { CloudHeader(route, onBack) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalCheckoutAccountChange provides { activity ->
                        switchingCheckoutAccount = true
                        try {
                            connection.chooseAccount(activity)
                            if (switchingCheckoutAccount) checkoutUid = connection.session.account.value?.uid
                        } finally {
                            switchingCheckoutAccount = false
                        }
                    }) { content(route) }
                }
            }
        }
        return
    }

    val routeScroll = key(route) { rememberScrollState() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxHeight().widthIn(max = 660.dp).fillMaxWidth()
                .verticalScroll(routeScroll).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CloudHeader(route, onBack)
                if (route == CloudRoute.OVERVIEW) {
                    Text(
                        "사진과 동영상을 기기와 별도로 보관하고 관리합니다.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when {
                access == CloudAccessState.CHOOSING_ACCOUNT || access == CloudAccessState.SIGNING_OUT ->
                    CloudAccountLoading(access)
                access == CloudAccessState.UNAVAILABLE ->
                    CloudStatusMessage("이 빌드에서 클라우드 로그인 설정을 확인할 수 없습니다.")
                route == CloudRoute.BILLING_SIGN_IN -> content(route)
                route == CloudRoute.ACCOUNT_MANAGEMENT -> key(account?.uid) {
                    CloudAccountCard {
                        CloudAccountExitGuidance(connection, context)
                        if (access == CloudAccessState.SIGNED_IN) CloudAccountDeletionButton(connection)
                    }
                }
                route != CloudRoute.OVERVIEW -> key(namespace) { content(route) }
                else -> {
                    val onNavigate: (CloudRoute) -> Unit = {
                        checkoutUid = if (it == CloudRoute.BILLING) accountUid else null
                        checkoutSignInPending = it == CloudRoute.BILLING && access == CloudAccessState.SIGNED_OUT
                        requestedRoute = if (it == CloudRoute.BILLING) CloudRoute.OVERVIEW else it
                    }
                    val billingAvailable = connection.billing != null
                    if (access == CloudAccessState.SIGNED_IN) {
                        account?.uid?.let { uid ->
                            CloudOverviewStorageCard(connection, uid, showLibraryStatus = configuredBackup != null && backup == null)
                        }
                        if (backup != null) CloudActionGroup(CloudRoute.LIBRARY, onNavigate = onNavigate)
                        if (billingAvailable) {
                            CloudActionGroup(CloudRoute.BILLING, onNavigate = onNavigate)
                        } else {
                            CloudBillingOverviewNotice("이 빌드에서는 결제 기능을 사용할 수 없습니다.")
                        }
                        if (backup != null) {
                            CloudActionGroup(CloudRoute.BACKUP, CloudRoute.AUTO_BACKUP, CloudRoute.SPACE, onNavigate = onNavigate)
                        }
                        if (showSecretCloud) CloudActionGroup(CloudRoute.SECRET, onNavigate = onNavigate)
                        content(CloudRoute.OVERVIEW)
                    } else if (access == CloudAccessState.SIGNED_OUT) {
                        content(CloudRoute.OVERVIEW)
                        if (billingAvailable) CloudActionGroup(CloudRoute.BILLING, onNavigate = onNavigate)
                        else CloudBillingOverviewNotice("이 빌드에서는 결제 기능을 사용할 수 없습니다.")
                    }
                    if (access == CloudAccessState.SIGNED_IN || access == CloudAccessState.SIGNED_OUT) {
                        CloudActionGroup(CloudRoute.ACCOUNT_MANAGEMENT, onNavigate = onNavigate)
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
private fun CloudAccountLoading(access: CloudAccessState) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp).testTag("cloud-account-loading"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Primary)
        Text(
            if (access == CloudAccessState.CHOOSING_ACCOUNT) "Google 계정으로 로그인하는 중입니다."
            else "클라우드 계정에서 로그아웃하는 중입니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
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
    // The account card leaves composition while the account operation is in progress.
    val accountScope = LocalLifecycleOwner.current.lifecycleScope
    val signedIn = account
    val actionsEnabled = accountStatus.operation == null
    val chooseAccount: () -> Unit = { accountScope.launch { connection.chooseAccount(activity) } }
    if (forAdRemoval) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudFeatureIcon(Icons.Outlined.Cloud, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "광고 없이 이용하기",
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudFeatureIcon(Icons.Outlined.Cloud)
            Text(
                "클라우드에 원본 보관",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            "Google로 로그인해 사진·동영상 원본을 보관하고 열어 보세요.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
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
        CloudAccountActions(chooseAccount, { accountScope.launch { session.signOut() } }, actionsEnabled)
    }
    accountStatus.displayText(signedIn != null)?.let { CloudStatusMessage(it) }
}

internal fun CloudAccountStatus.displayText(signedIn: Boolean): String? =
    when (operation) {
        CloudAccountOperation.CHOOSING_ACCOUNT -> "Google 계정을 확인하는 중입니다."
        CloudAccountOperation.SIGNING_OUT -> "로그아웃하는 중입니다."
        null -> when (notice) {
            CloudAccountNotice.CANCELLED -> "Google 계정 선택을 취소했습니다."
            CloudAccountNotice.NO_GOOGLE_ACCOUNT -> "선택할 Google 계정을 찾지 못했습니다. 기기의 계정 설정을 확인해 주세요."
            CloudAccountNotice.GOOGLE_UNAVAILABLE -> "Google 계정 선택을 열 수 없습니다. Google Play 서비스를 확인해 주세요."
            CloudAccountNotice.NETWORK_ERROR -> "네트워크 연결을 확인한 뒤 다시 시도해 주세요."
            CloudAccountNotice.GOOGLE_CREDENTIAL_FAILED -> "Google 계정 확인에 실패했습니다. 다시 시도해 주세요. (G-01)"
            CloudAccountNotice.FIREBASE_GOOGLE_CONFIGURATION -> "클라우드 Google 로그인 설정에 문제가 있습니다. (G-02)"
            CloudAccountNotice.FIREBASE_SIGN_IN_FAILED -> "Google 계정으로 클라우드에 로그인하지 못했습니다. 다시 시도해 주세요. (G-03)"
            CloudAccountNotice.SIGN_IN_FAILED -> "Google 로그인에 실패했습니다. 다시 시도해 주세요."
            CloudAccountNotice.SIGN_OUT_FAILED -> "로그아웃에 실패했습니다. 다시 시도해 주세요."
            CloudAccountNotice.CREDENTIAL_CLEAR_FAILED -> if (signedIn) {
                "Google 계정 선택 상태를 초기화하지 못했습니다. 다시 시도해 주세요."
            } else {
                "로그아웃했습니다. 다음에 로그인할 때 이전 Google 계정이 제안될 수 있습니다."
            }
            null -> null
        }
    }

@Composable
private fun CloudAccountExitGuidance(connection: CloudAccountConnection, context: Context) {
    val account by connection.session.account.collectAsStateWithLifecycle()
    var actionUnavailable by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    Text(
        "구독을 취소하려면 Google Play에서 자동 갱신을 해제해 주세요.",
        color = Muted,
        style = MaterialTheme.typography.bodyMedium,
    )
    ActionButton("Google Play 구독 취소·관리", {
        actionUnavailable = !startCloudExitAction(context,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions")))
    }, Modifier.fillMaxWidth())
    if (account?.uid != null) {
        CloudOnlyDeletionButton(connection)
    } else {
        ActionButton("클라우드 데이터 삭제 요청", {
            actionUnavailable = !requestCloudExitByEmail(context, "클라우드 데이터 삭제")
        }, Modifier.fillMaxWidth())
    }
    TextAction(if (detailsExpanded) "삭제·취소 안내 접기" else "삭제·취소 안내 보기", { detailsExpanded = !detailsExpanded })
    if (detailsExpanded) {
        CloudStatusMessage("로그아웃·앱 삭제·회원 탈퇴만으로 Google Play 자동 결제가 취소되지 않습니다.")
        CloudStatusMessage("클라우드 데이터만 삭제하거나 GalliPick 계정 전체를 탈퇴할 수 있습니다. 기기에 저장된 원본은 삭제하지 않습니다.")
        CloudStatusMessage(
            if (connection.accountExit == null || account?.uid == null)
                "메일 앱에서 요청 내용을 보내야 접수됩니다. 접수 후 본인 확인을 거쳐 삭제를 진행합니다."
            else "삭제 요청 시 Google 재인증과 확인이 필요합니다. 서버 접수와 실제 삭제 완료는 구분됩니다.",
        )
    }
    if (actionUnavailable) {
        CloudStatusMessage("외부 앱을 열 수 없습니다. asdlkj0104@gmail.com으로 요청하거나 개인정보처리방침을 확인해 주세요.")
    }
}

internal fun requestCloudExitByEmail(context: Context, request: String): Boolean {
    val subject = Uri.encode("GalliPick $request 요청")
    val body = Uri.encode("요청 내용:\n로그인한 Google 계정 이메일:\n삭제 범위:\n\n원본 파일이나 결제 토큰은 첨부하지 마세요.")
    val uri = Uri.parse("mailto:asdlkj0104@gmail.com?subject=$subject&body=$body")
    return startCloudExitAction(context, Intent(Intent.ACTION_SENDTO, uri))
}

private fun startCloudExitAction(context: Context, intent: Intent): Boolean = try {
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
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
