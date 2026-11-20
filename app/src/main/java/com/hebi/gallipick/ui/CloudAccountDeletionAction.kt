package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.activeSubscriptionProductId
import kotlinx.coroutines.CancellationException

private enum class CloudExitSubscriptionState { LOADING, ACTIVE, INACTIVE, UNKNOWN }

@Composable
internal fun CloudAccountDeletionButton(connection: CloudAccountConnection) {
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    val context = LocalContext.current
    var confirmationOpen by remember(uid) { mutableStateOf(false) }
    var mailUnavailable by remember(uid) { mutableStateOf(false) }
    var subscription by remember(uid) { mutableStateOf(CloudExitSubscriptionState.LOADING) }

    ActionButton("회원 탈퇴", {
        subscription = CloudExitSubscriptionState.LOADING
        confirmationOpen = true
    }, Modifier.fillMaxWidth())
    if (mailUnavailable) CloudStatusMessage("메일 앱을 열 수 없습니다. 개인정보처리방침의 문의 이메일로 탈퇴를 요청해 주세요.")

    if (confirmationOpen) {
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
            onDismissRequest = { confirmationOpen = false },
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text("GalliPick 회원 탈퇴") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("회원 탈퇴를 요청하면 클라우드 사진·동영상과 시크릿 클라우드 데이터가 삭제 대상에 포함됩니다.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("데이터 삭제가 완료되면 복원할 수 없습니다. 휴대전화에 저장된 원본은 삭제하지 않습니다.",
                        color = Ink, style = MaterialTheme.typography.bodyMedium)
                    when (subscription) {
                        CloudExitSubscriptionState.LOADING ->
                            CircularProgressIndicator(color = Primary)
                        CloudExitSubscriptionState.ACTIVE ->
                            Text("현재 유효한 구독이 확인됩니다. 회원 탈퇴를 요청하더라도 Google Play 자동 결제는 별도로 취소해야 합니다.",
                                color = Ink, style = MaterialTheme.typography.bodySmall)
                        CloudExitSubscriptionState.UNKNOWN ->
                            Text("구독 상태를 확인하지 못했습니다. Google Play에서 자동 갱신 여부를 별도로 확인해 주세요.",
                                color = Ink, style = MaterialTheme.typography.bodySmall)
                        CloudExitSubscriptionState.INACTIVE ->
                            Text("Google Play에 다른 계정의 활성 구독이 있을 수도 있으니 결제 목록을 확인해 주세요.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("탈퇴 처리 정책: 접수 후 접근을 중단하고 최대 14일 이내 삭제하는 절차를 준비 중입니다. 현재는 메일로 요청하며 자동 삭제가 완료된 상태가 아닙니다.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                ActionButton("탈퇴 요청 메일 작성", {
                    mailUnavailable = !requestCloudExitByEmail(context, "회원 탈퇴 및 연관 데이터 삭제")
                    confirmationOpen = false
                }, filled = true)
            },
            dismissButton = {
                TextAction("돌아가기", { confirmationOpen = false })
            },
        )
    }
}
