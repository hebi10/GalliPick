package com.hebi.gallipick.ui

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupAccess
import com.hebi.gallipick.data.cloud.CloudBackupNamespace
import com.hebi.gallipick.data.cloud.CloudBackupPhase
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.launch

internal fun cloudOriginalLimitLabel(bytes: Long): String {
    val mebibyte = 1024L * 1024L
    return if (bytes % mebibyte == 0L) "${bytes / mebibyte} MiB" else bytesLabel(bytes)
}

@Composable
internal fun CloudBackupSection(
    connection: CloudAccountConnection,
    state: AppUiState,
    requestAccess: () -> Unit,
) {
    val backup = connection.backup ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    val access = backup.accessFor(uid) ?: return
    key(uid) {
        if (access.allowsUpload(System.currentTimeMillis())) CloudBackupControls(backup, uid, access, state, requestAccess)
        else CloudAccountCard {
            CloudStatusMessage("새 백업을 추가할 수 없습니다. 요금제·결제에서 구독 상태와 남은 공간을 확인해 주세요.")
        }
    }
}

@Composable
private fun CloudBackupControls(
    backup: CloudBackup,
    uid: String,
    access: CloudBackupAccess,
    state: AppUiState,
    requestAccess: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val eligible = remember(state.access, state.items, access.maximumOriginalBytes) {
        if (state.access == MediaAccess.NONE) emptyList()
        else state.items.filter { !it.isTrashed && it.sizeBytes in 1..access.maximumOriginalBytes &&
            (it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/")) }
    }
    var showPicker by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<MediaItem?>(null) }
    var phase by remember { mutableStateOf<CloudBackupPhase?>(null) }
    var result by remember { mutableStateOf<CloudBackupResult?>(null) }
    val currentSelection = selection?.let { selected ->
        eligible.firstOrNull {
            it.uri == selected.uri && it.fingerprint == selected.fingerprint &&
                it.volumeVersion == selected.volumeVersion
        }
    }

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
                "사진·동영상 원본 보관",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            val storage = if (access.namespace == CloudBackupNamespace.PRODUCTION) "클라우드" else "테스트 클라우드"
            Text(
                "한 번에 사진이나 동영상 한 개를 선택해 ${storage}에 보관합니다.\n파일당 최대 ${cloudOriginalLimitLabel(access.maximumOriginalBytes)}입니다.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "원본에 포함된 촬영 위치 등의 정보도 함께 보관됩니다.",
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
            Text(bytesLabel(currentSelection.sizeBytes), color = Muted, style = MaterialTheme.typography.bodySmall)
            ActionButton(
                if (result == null) "선택한 원본 백업" else "원본 백업 다시 시도",
                {
                    phase = CloudBackupPhase.READING_ORIGINAL
                    result = null
                    scope.launch {
                        try {
                            result = backup.upload(currentSelection, uid) { phase = it }
                            if (result == CloudBackupResult.VERIFIED) selection = null
                        } finally {
                            phase = null
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
        val message = when (phase) {
            CloudBackupPhase.READING_ORIGINAL -> "원본 파일을 확인하는 중입니다."
            CloudBackupPhase.UPLOADING -> "원본을 전송하는 중입니다."
            CloudBackupPhase.VERIFYING -> "서버에서 원본을 검증하는 중입니다."
            null -> when (result) {
                CloudBackupResult.VERIFIED -> "서버에서 원본 보관을 확인했습니다."
                CloudBackupResult.ORIGINAL_UNAVAILABLE -> "원본을 읽을 수 없습니다. 파일과 접근 범위를 확인해 주세요."
                CloudBackupResult.UNSUPPORTED_MEDIA -> "이 형식 또는 크기의 파일은 백업에서 지원하지 않습니다."
                CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경되어 작업이 중단됐습니다."
                CloudBackupResult.QUOTA_REACHED -> "클라우드 저장 한도에 도달했습니다."
                CloudBackupResult.TEST_ACCESS_REQUIRED -> "현재 계정의 백업 권한을 확인해 주세요."
                CloudBackupResult.NETWORK_ERROR -> "네트워크 연결을 확인한 뒤 같은 파일로 다시 시도해 주세요."
                CloudBackupResult.INTEGRITY_ERROR -> "원본 크기 또는 해시를 확인하지 못했습니다."
                CloudBackupResult.DEVICE_STORAGE_ERROR -> "기기 저장공간을 확인해 주세요."
                CloudBackupResult.FAILED -> "원본 보관을 확인하지 못했습니다. 다시 시도해 주세요."
                null -> null
            }
        }
        message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted) }
    }

    if (showPicker && !state.loading && state.catalogError == null && eligible.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text("백업할 파일 선택") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(eligible, key = { it.uri }) { item ->
                        Column(Modifier.fillMaxWidth().clickable {
                            selection = item
                            result = null
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
