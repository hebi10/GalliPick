package com.hebi.gallipick.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupAccess
import com.hebi.gallipick.data.cloud.CloudBackupPhase
import com.hebi.gallipick.data.cloud.CloudBackupProgress
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun cloudOriginalLimitLabel(bytes: Long): String {
    val mebibyte = 1024L * 1024L
    return if (bytes % mebibyte == 0L) "${bytes / mebibyte} MiB" else bytesLabel(bytes)
}

internal val CloudAccountIdentity.transferAccountLabel: String
    get() = email?.takeIf { it.isNotBlank() }
        ?: displayName?.takeIf { it.isNotBlank() }
        ?: "현재 로그인한 Google 계정"

@Composable
internal fun CloudOriginalTransferNotice(
    accountLabel: String,
    originalSize: String,
    privateVault: Boolean = false,
    lastKnownRemainingBytes: Long? = null,
) {
    val destination = if (privateVault) "시크릿 클라우드" else "클라우드 보관함"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("보관 위치 · $destination", style = MaterialTheme.typography.bodyMedium)
        Text("대상 계정 · $accountLabel", style = MaterialTheme.typography.bodyMedium)
        Text("원본 크기 · $originalSize", style = MaterialTheme.typography.bodyMedium)
        lastKnownRemainingBytes?.let { remaining ->
            val space = Formatter.formatShortFileSize(LocalContext.current, remaining)
            Text("남은 저장공간 · $space (마지막 확인)", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "촬영 위치 등 원본에 포함된 정보도 함께 보관합니다.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun CloudBackupSection(
    connection: CloudAccountConnection,
    state: AppUiState,
    requestAccess: () -> Unit,
) {
    val backup = connection.backup ?: return
    val observedAccount by connection.session.account.collectAsStateWithLifecycle()
    val account = observedAccount ?: return
    val uid = account.uid
    val access = backup.accessFor(uid) ?: return
    key(uid) {
        if (access.allowsManualUpload(System.currentTimeMillis())) {
            CloudBackupControls(backup, uid, access, state, requestAccess, account.transferAccountLabel)
        }
        else CloudAccountCard {
            CloudStatusMessage("새 백업을 추가할 수 없습니다. 요금제·결제에서 구독 상태와 남은 공간을 확인해 주세요.")
        }
    }
}

@Composable
internal fun CloudBackupControls(
    backup: CloudBackup,
    uid: String,
    access: CloudBackupAccess,
    state: AppUiState,
    requestAccess: () -> Unit,
    accountLabel: String,
    privateVault: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val supportsResume = access.allowsResumableUpload(System.currentTimeMillis())
    val maximumBytes = access.maximumManualOriginalBytes(System.currentTimeMillis())
    val eligible = remember(state.access, state.items, maximumBytes) {
        if (state.access == MediaAccess.NONE) emptyList()
        else state.items.filter { !it.isTrashed && it.sizeBytes in 1..maximumBytes &&
            (it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")) }
    }
    var showPicker by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<MediaItem?>(null) }
    var phase by remember { mutableStateOf<CloudBackupPhase?>(null) }
    var progress by remember { mutableStateOf<CloudBackupProgress?>(null) }
    var result by remember { mutableStateOf<CloudBackupResult?>(null) }
    var paused by remember { mutableStateOf(false) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }
    val currentSelection = selection?.let { selected ->
        eligible.firstOrNull {
            it.uri == selected.uri && it.fingerprint == selected.fingerprint &&
                it.volumeVersion == selected.volumeVersion
        }
    }
    val selectedResumable = supportsResume && currentSelection?.let { it.sizeBytes > access.maximumOriginalBytes } == true

    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(GalliUiMetrics.cardPadding),
        verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CloudFeatureIcon(Icons.Outlined.CloudUpload, size = 96.dp)
            Text(
                if (privateVault) "사진·동영상 시크릿 보관" else "사진·동영상 원본 보관",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            val storage = if (privateVault) "시크릿 클라우드" else "클라우드"
            Text(
                if (supportsResume) "요금제 저장공간을 사용합니다. 대용량 백업이 중단되면 같은 파일을 선택해 이어서 올릴 수 있습니다."
                else "한 번에 사진이나 동영상 한 개를 선택해 ${storage}에 보관합니다.\n파일당 최대 ${cloudOriginalLimitLabel(maximumBytes)}입니다.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (state.access == MediaAccess.NONE) {
            CloudPrimaryAction("사진·동영상 접근 허용", requestAccess)
        } else {
            when {
                state.loading -> Text("기기 파일을 확인하는 중입니다.", color = Muted)
                state.catalogError != null -> Text("기기 파일을 확인하지 못했습니다.", color = Muted)
                eligible.isEmpty() -> Text("현재 접근 범위에 보관할 수 있는 파일이 없습니다.", color = Muted)
            }
            ActionButton(
                "파일 선택",
                { showPicker = true },
                Modifier.fillMaxWidth(),
                filled = currentSelection == null,
                enabled = !state.loading && state.catalogError == null && eligible.isNotEmpty() && phase == null,
            )
        }
        if (currentSelection != null) {
            Text(currentSelection.displayName, style = MaterialTheme.typography.bodyMedium)
            CloudOriginalTransferNotice(
                accountLabel, bytesLabel(currentSelection.sizeBytes), privateVault, access.lastKnownRemainingBytes,
            )
            ActionButton(
                when (result) {
                    null -> if (paused) "이어서 올리기" else "선택한 원본 백업"
                    CloudBackupResult.VERIFICATION_PENDING -> "검증 상태 확인"
                    else -> if (selectedResumable) "이어서 올리기" else "원본 백업 다시 시도"
                },
                {
                    phase = CloudBackupPhase.READING_ORIGINAL
                    progress = null
                    result = null
                    paused = false
                    uploadJob = scope.launch {
                        try {
                            result = backup.upload(currentSelection, uid, onProgress = { progress = it }) { phase = it }
                            if (result == CloudBackupResult.VERIFIED) selection = null
                        } finally {
                            phase = null
                            progress = null
                            uploadJob = null
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                filled = true,
                enabled = !state.loading && state.catalogError == null && phase == null,
            )
        } else if (selection != null && phase == null) {
            Text("파일 상태가 바뀌었습니다. 다시 선택해 주세요.", color = Muted)
        }
        progress?.let { transfer ->
            val fraction = (transfer.uploadedBytes.toDouble() / transfer.totalBytes).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("${bytesLabel(transfer.uploadedBytes)} / ${bytesLabel(transfer.totalBytes)}",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (selectedResumable && phase == CloudBackupPhase.UPLOADING) {
            TextAction("업로드 일시중지", onClick = {
                paused = true
                uploadJob?.cancel()
            })
        }
        val message = when (phase) {
            CloudBackupPhase.READING_ORIGINAL -> "원본 파일을 확인하는 중입니다."
            CloudBackupPhase.UPLOADING -> "원본을 전송하는 중입니다."
            CloudBackupPhase.VERIFYING -> "서버에서 원본을 검증하는 중입니다."
            null -> when (result) {
                CloudBackupResult.VERIFIED -> "서버에서 원본 보관을 확인했습니다."
                CloudBackupResult.VERIFICATION_PENDING -> "전송을 마쳤습니다. 서버에서 원본을 검증하고 있습니다."
                CloudBackupResult.ORIGINAL_UNAVAILABLE -> "원본을 읽을 수 없습니다. 파일과 접근 범위를 확인해 주세요."
                CloudBackupResult.UNSUPPORTED_MEDIA -> "이 형식 또는 크기의 파일은 백업에서 지원하지 않습니다."
                CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경되어 작업이 중단됐습니다."
                CloudBackupResult.QUOTA_REACHED -> "클라우드 저장 한도에 도달했습니다."
                CloudBackupResult.TEST_ACCESS_REQUIRED -> "현재 계정의 백업 권한을 확인해 주세요."
                CloudBackupResult.NETWORK_ERROR -> if (selectedResumable) "연결을 확인한 뒤 같은 파일을 선택해 전송을 이어 주세요."
                    else "네트워크 연결을 확인한 뒤 같은 파일로 다시 시도해 주세요."
                CloudBackupResult.INTEGRITY_ERROR -> "원본 크기 또는 해시를 확인하지 못했습니다."
                CloudBackupResult.DEVICE_STORAGE_ERROR -> "기기 저장공간을 확인해 주세요."
                CloudBackupResult.FAILED -> "원본 보관을 확인하지 못했습니다. 다시 시도해 주세요."
                null -> if (paused) "업로드를 일시중지했습니다. 같은 파일에서 이어서 올릴 수 있습니다." else null
            }
        }
        message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted) }
    }

    if (showPicker && !state.loading && state.catalogError == null && eligible.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            properties = DialogProperties(securePolicy = if (privateVault) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text("백업할 파일 선택") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(eligible, key = { it.uri }) { item ->
                        Column(Modifier.fillMaxWidth().clickable {
                            selection = item
                            result = null
                            paused = false
                            showPicker = false
                        }.padding(vertical = 12.dp)) {
                            Text(item.displayName, color = Ink)
                            Text("${bytesLabel(item.sizeBytes)} · ${item.relativePath}", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextAction("닫기", { showPicker = false }) },
        )
    }
}
