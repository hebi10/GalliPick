package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountFailure
import com.hebi.gallipick.data.cloud.CloudAccountNotice
import com.hebi.gallipick.data.cloud.CloudExitScope
import com.hebi.gallipick.data.cloud.CloudExitSnapshot
import com.hebi.gallipick.data.cloud.CloudExitState
import com.hebi.gallipick.data.cloud.activeSubscriptionProductId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class CloudExitSubscriptionState { LOADING, ACTIVE, INACTIVE, UNKNOWN }

@Composable
internal fun CloudAccountDeletionButton(connection: CloudAccountConnection) {
    CloudAccountExitAction(connection, CloudExitScope.ACCOUNT)
}

@Composable
internal fun CloudOnlyDeletionButton(connection: CloudAccountConnection) {
    CloudAccountExitAction(connection, CloudExitScope.CLOUD)
}

@Composable
private fun CloudAccountExitAction(connection: CloudAccountConnection, scope: CloudExitScope) {
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    val context = LocalContext.current
    val activity = context as? Activity
    val exitApi = connection.accountExit
    val coroutineScope = rememberCoroutineScope()
    var confirmationOpen by remember(uid, scope) { mutableStateOf(false) }
    var acknowledgePermanent by remember(uid, scope) { mutableStateOf(false) }
    var acknowledgeBilling by remember(uid, scope) { mutableStateOf(false) }
    var requestBusy by remember(uid, scope) { mutableStateOf(false) }
    var operationFailed by remember(uid, scope) { mutableStateOf<String?>(null) }
    var mailUnavailable by remember(uid, scope) { mutableStateOf(false) }
    var subscription by remember(uid, scope) { mutableStateOf(CloudExitSubscriptionState.LOADING) }
    var serverStatus by remember(uid, scope) { mutableStateOf<CloudExitSnapshot?>(null) }
    var statusFailed by remember(uid, scope) { mutableStateOf(false) }
    var refresh by remember(uid, scope) { mutableIntStateOf(0) }

    LaunchedEffect(connection, uid, scope, refresh) {
        if (exitApi == null) return@LaunchedEffect
        statusFailed = false
        serverStatus = null
        try {
            serverStatus = exitApi.status(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            statusFailed = true
        }
    }

    val pending = serverStatus?.takeIf { it.deletionPending || it.accountRemovalPending }
    val finished = serverStatus?.takeIf {
        it.scope == scope && (it.state == CloudExitState.COMPLETED || it.state == CloudExitState.RESUMED)
    }
    val actionLabel = when (scope) {
        CloudExitScope.ACCOUNT -> "회원 탈퇴"
        CloudExitScope.CLOUD -> "클라우드 데이터 삭제 요청"
    }

    when {
        exitApi != null && serverStatus == null && !statusFailed ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Primary)
                CloudStatusMessage("계정 삭제 요청 상태를 확인하는 중입니다.")
            }
        pending != null ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("삭제 요청 처리 현황", style = MaterialTheme.typography.titleSmall)
                CloudStatusMessage(pending.exitStatusText())
                ActionButton("처리 현황 새로고침", { refresh++ }, Modifier.fillMaxWidth())
            }
        finished != null && scope == CloudExitScope.CLOUD && finished.state == CloudExitState.COMPLETED ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudStatusMessage("클라우드 데이터 삭제가 완료되었습니다. 새로운 백업은 다시 활성화해야 합니다.")
                ActionButton("클라우드 백업 다시 활성화", {
                    operationFailed = null
                    if (activity == null) {
                        operationFailed = "Google 계정 재인증을 열 수 없습니다."
                    } else {
                        requestBusy = true
                        coroutineScope.launch {
                            try {
                                serverStatus = exitApi?.resumeCloudBackup(activity, uid)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                operationFailed = error.exitFailureText()
                            } finally {
                                requestBusy = false
                            }
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = !requestBusy)
            }
        finished != null && scope == CloudExitScope.CLOUD && finished.state == CloudExitState.RESUMED ->
            CloudStatusMessage("새 클라우드 백업이 다시 활성화됐습니다. 이전에 삭제한 원본은 복구할 수 없습니다.")
        statusFailed ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudStatusMessage("삭제 요청 상태를 확인하지 못했습니다. 네트워크 연결을 확인해 주세요.")
                ActionButton("상태 다시 확인", { refresh++ }, Modifier.fillMaxWidth())
                TextAction("이메일로 문의", {
                    mailUnavailable = !requestCloudExitByEmail(context, actionLabel)
                })
            }
        else ->
            ActionButton(actionLabel, {
                operationFailed = null
                acknowledgePermanent = false
                acknowledgeBilling = false
                subscription = CloudExitSubscriptionState.LOADING
                confirmationOpen = true
            }, Modifier.fillMaxWidth())
    }
    if (operationFailed != null) {
        CloudStatusMessage(checkNotNull(operationFailed))
        TextAction("이메일로 문의", { mailUnavailable = !requestCloudExitByEmail(context, actionLabel) })
    }
    if (mailUnavailable) CloudStatusMessage("메일 앱을 열 수 없습니다. 개인정보처리방침의 지원 이메일로 문의해 주세요.")

    if (!confirmationOpen) return

    LaunchedEffect(uid, confirmationOpen) {
        subscription = try {
            val plan = connection.billing?.open(uid)?.use { it.loadPlan() }
            when {
                plan == null || !plan.enabled -> CloudExitSubscriptionState.UNKNOWN
                plan.activeSubscriptionProductId(System.currentTimeMillis()) != null ->
                    CloudExitSubscriptionState.ACTIVE
                else -> CloudExitSubscriptionState.INACTIVE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CloudExitSubscriptionState.UNKNOWN
        }
    }

    AlertDialog(
        onDismissRequest = { if (!requestBusy) confirmationOpen = false },
        containerColor = AppSurface,
        titleContentColor = Ink,
        title = { Text(if (scope == CloudExitScope.ACCOUNT) "GalliPick 회원 탈퇴" else "클라우드 데이터 삭제") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (scope == CloudExitScope.ACCOUNT)
                        "GalliPick 계정과 일반·시크릿 클라우드 원본 및 연결 데이터가 삭제됩니다."
                    else
                        "일반·시크릿 클라우드 사진과 동영상 및 서버 메타데이터가 삭제됩니다. 계정과 구독은 유지됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("처리 요청이 접수되면 클라우드 접근이 제한됩니다. 삭제가 완료된 서버 원본은 복구할 수 없으며, 휴대전화에 있는 원본은 유지됩니다.",
                    color = Ink, style = MaterialTheme.typography.bodyMedium)
                when (subscription) {
                    CloudExitSubscriptionState.LOADING -> CircularProgressIndicator(color = Primary)
                    CloudExitSubscriptionState.ACTIVE ->
                        CloudStatusMessage("유효한 구독이 확인됩니다. 탈퇴·삭제와 Google Play 자동 결제 취소는 별도 절차입니다.")
                    CloudExitSubscriptionState.UNKNOWN ->
                        CloudStatusMessage("구독 상태를 확인하지 못했습니다. Google Play에서 자동 갱신 여부를 확인해 주세요.")
                    CloudExitSubscriptionState.INACTIVE ->
                        CloudStatusMessage("다른 Google 계정의 활성 구독이 있을 수 있으므로 Google Play 결제 목록을 확인해 주세요.")
                }
                CloudStatusMessage("삭제 요청은 가능한 즉시 처리하며 처리 목표는 최대 14일입니다. 요청 접수만으로 삭제가 완료된 것은 아닙니다.")
                CloudExitAcknowledgement(
                    "삭제된 클라우드 원본은 복원할 수 없음을 확인했습니다.",
                    "cloud-exit-confirm-permanent", acknowledgePermanent, !requestBusy,
                ) { acknowledgePermanent = it }
                CloudExitAcknowledgement(
                    "Google Play 자동 갱신은 직접 취소해야 함을 확인했습니다.",
                    "cloud-exit-confirm-billing", acknowledgeBilling, !requestBusy,
                ) { acknowledgeBilling = it }
            }
        },
        confirmButton = {
            ActionButton(
                if (exitApi == null && scope == CloudExitScope.ACCOUNT) "탈퇴 요청 메일 작성"
                else if (exitApi == null) "삭제 요청 메일 작성"
                else "Google 재인증 후 삭제 요청",
                {
                    operationFailed = null
                    if (exitApi == null) {
                        mailUnavailable = !requestCloudExitByEmail(context, actionLabel)
                        confirmationOpen = false
                    } else if (activity == null) {
                        operationFailed = "Google 계정 재인증을 열 수 없습니다."
                        confirmationOpen = false
                    } else {
                        requestBusy = true
                        coroutineScope.launch {
                            try {
                                serverStatus = exitApi.request(activity, uid, scope)
                                confirmationOpen = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                operationFailed = error.exitFailureText()
                                confirmationOpen = false
                            } finally {
                                requestBusy = false
                            }
                        }
                    }
                },
                filled = true,
                enabled = acknowledgePermanent && acknowledgeBilling && !requestBusy
                    && subscription != CloudExitSubscriptionState.LOADING,
            )
        },
        dismissButton = { TextAction("돌아가기", { if (!requestBusy) confirmationOpen = false }) },
    )
}

@Composable
private fun CloudExitAcknowledgement(
    label: String,
    tag: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget).testTag(tag)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun CloudExitSnapshot.exitStatusText(): String = when (state) {
    CloudExitState.REQUESTED -> "삭제 요청이 접수됐습니다. 클라우드 접근은 제한되며 서버 처리를 기다리고 있습니다."
    CloudExitState.IN_PROGRESS, CloudExitState.RUNNING -> "클라우드 데이터를 삭제하는 중입니다."
    CloudExitState.RETRY -> "일부 데이터 삭제를 재시도하고 있습니다."
    CloudExitState.MANUAL_REVIEW -> "삭제 처리에 문제가 있어 담당자가 확인해야 합니다. 지원 이메일로 문의해 주세요."
    CloudExitState.COMPLETED -> "데이터 삭제가 완료됐습니다."
    CloudExitState.RESUMED -> "클라우드 백업이 다시 활성화됐습니다."
    CloudExitState.NONE -> "삭제 요청 내역이 없습니다."
}

private fun Exception.exitFailureText(): String = when (this) {
    is CloudAccountFailure -> if (notice == CloudAccountNotice.CANCELLED)
        "Google 계정 재인증을 취소했습니다." else "Google 계정 재인증에 실패했습니다."
    else -> "서버에서 삭제 요청을 접수하지 못했습니다. 계정이나 네트워크 상태를 확인해 주세요."
}
