package com.hebi.gallipick.ui

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountNotice
import com.hebi.gallipick.data.cloud.CloudAccountOperation
import kotlinx.coroutines.launch

@Composable
internal fun CloudAccountSettings(connection: CloudAccountConnection, activity: Activity) {
    val session = connection.session
    if (!session.available) {
        Text("이 빌드에서 클라우드 로그인 설정을 확인할 수 없습니다.", color = Muted)
        return
    }

    val account by session.account.collectAsStateWithLifecycle()
    val accountStatus by session.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val signedIn = account
    if (signedIn == null) {
        Text("Google 계정으로 로그인할 수 있습니다. 기기에서 사진·동영상을 정리하는 기능은 로그인 없이 이용할 수 있습니다.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
    } else {
        Text(signedIn.displayName?.takeIf { it.isNotBlank() } ?: "Google 계정",
            style = MaterialTheme.typography.bodyMedium)
        signedIn.email?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
    ActionButton(
        if (signedIn == null) "Google로 로그인" else "Google 계정 변경",
        { scope.launch { connection.chooseAccount(activity) } },
        Modifier.fillMaxWidth(),
        filled = signedIn == null,
        enabled = accountStatus.operation == null,
    )
    if (signedIn != null) {
        ActionButton(
            "로그아웃",
            { scope.launch { session.signOut() } },
            Modifier.fillMaxWidth(),
            enabled = accountStatus.operation == null,
        )
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
    statusText?.let { message ->
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
