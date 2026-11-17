package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.cloudPhotoSupportsViewer
import com.hebi.gallipick.data.cloud.isCloudVideoStreamable
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudOriginal
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

@Composable
internal fun CloudLibrarySection(
    connection: CloudAccountConnection,
    onRestored: () -> Unit,
    onOpenRestored: (Uri, String) -> Unit,
) {
    val backup = connection.backup ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    key(uid) { CloudLibraryControls(backup, uid, onRestored, onOpenRestored) }
}

private data class LibraryAction(val item: CloudOriginal, val preview: Boolean)

@Composable
private fun CloudLibraryControls(
    backup: CloudBackup,
    uid: String,
    onRestored: () -> Unit,
    onOpenRestored: (Uri, String) -> Unit,
) {
    var catalog by remember { mutableStateOf(emptyList<CloudOriginal>()) }
    var loading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<CloudOriginal?>(null) }
    var playingVideo by remember { mutableStateOf<CloudOriginal?>(null) }
    var action by remember { mutableStateOf<LibraryAction?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var preview by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    val revision by backup.catalogRevision.collectAsStateWithLifecycle()
    val formatDate = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LaunchedEffect(revision, reload) {
        loading = true
        catalogError = null
        try {
            catalog = backup.listOriginals(uid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Keep a previous in-session catalog if the network goes offline.
            // CloudLibrarySection is keyed by UID: never reuse across accounts.
            catalogError = cloudLibraryFailure(error)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(action) {
        val request = action ?: return@LaunchedEffect
        status = if (request.preview) "검증된 원본을 미리 보는 중입니다." else "원본을 내려받아 검증하는 중입니다."
        restored = null
        try {
            if (request.preview) {
                preview = request.item.displayName to backup.previewPhoto(request.item, uid)
                status = null
            } else {
                val uri = backup.restore(request.item, uid)
                restored = uri to request.item.mimeType
                status = "확인한 원본을 기기에 저장했습니다."
                onRestored()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status = cloudLibraryFailure(error)
        } finally {
            if (action == request) action = null
        }
    }

    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(GalliUiMetrics.cardPadding),
        verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
    ) {
        ActionButton(
            "목록 새로고침",
            { reload++ },
            enabled = !loading && action == null,
            icon = Icons.Outlined.Refresh,
        )
        if (catalogError != null && catalog.isNotEmpty()) {
            Text("현재 연결되지 않아 이전 목록을 표시합니다. 원본 열람·복원은 인터넷 연결이 필요합니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        when {
            loading && catalog.isEmpty() -> Text("보관함을 확인하는 중입니다.", color = Muted)
            catalogError != null && catalog.isEmpty() -> Text(checkNotNull(catalogError), color = Muted)
            catalog.isEmpty() -> Box(
                Modifier.fillMaxWidth().heightIn(min = 420.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CloudFeatureIcon(Icons.Outlined.PhotoLibrary, size = 96.dp)
                    Text(
                        "보관한 원본이 없어요",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "클라우드에 보관한 사진과 동영상이 여기에 표시돼요.",
                        color = Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(catalog, key = CloudOriginal::uploadId) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                        val date = formatDate.format(Date(item.verifiedAtMs))
                        val media = if (item.mimeType.startsWith("video/")) "동영상" else "사진"
                        Text("$media · ${bytesLabel(item.sizeBytes)} · $date", color = Muted,
                            style = MaterialTheme.typography.bodySmall)
                        if (cloudPhotoSupportsViewer(item)) {
                            ActionButton("미리보기", { action = LibraryAction(item, preview = true) },
                                enabled = action == null)
                        }
                        if (isCloudVideoStreamable(item)) {
                            ActionButton("앱에서 바로 재생", { playingVideo = item }, enabled = action == null)
                        }
                        ActionButton("기기에 복원", { selected = item }, enabled = action == null)
                    }
                }
            }
        }
        if (action != null) TextAction("작업 취소", { action = null; status = "작업 취소를 요청했습니다." })
        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted) }
        restored?.let { (uri, type) -> ActionButton("복원한 파일 보기", { onOpenRestored(uri, type) }) }
    }

    playingVideo?.let { item ->
        CloudVideoViewer(uid, item, backup, dismiss = { playingVideo = null })
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text("이 원본을 기기에 복원할까요?") },
            text = { Text("${item.displayName}\n${bytesLabel(item.sizeBytes)} · 기기 갤러리에 새 파일로 저장됩니다.") },
            confirmButton = {
                ActionButton("원본 복원", { selected = null; action = LibraryAction(item, preview = false) }, filled = true)
            },
            dismissButton = { TextAction("취소", { selected = null }) },
        )
    }

    preview?.let { (name, image) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            containerColor = AppSurface,
            titleContentColor = Ink,
            title = { Text(name) },
            text = { Image(image.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) },
            confirmButton = { TextAction("닫기", { preview = null }) },
        )
    }
}

private fun cloudLibraryFailure(error: Exception): String = when ((error as? CloudBackupException)?.result) {
    CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경되어 작업이 중단됐습니다."
    CloudBackupResult.TEST_ACCESS_REQUIRED -> "보관함 사용 권한이 있는 테스트 계정으로 로그인해 주세요."
    CloudBackupResult.NETWORK_ERROR -> "네트워크 연결을 확인한 뒤 다시 시도해 주세요."
    CloudBackupResult.INTEGRITY_ERROR -> "서버 원본의 버전, 크기 또는 해시가 일치하지 않습니다."
    CloudBackupResult.DEVICE_STORAGE_ERROR -> "기기 저장공간과 갤러리 상태를 확인해 주세요."
    CloudBackupResult.QUOTA_REACHED -> "요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
    else -> "클라우드 원본을 확인하지 못했습니다. 다시 시도해 주세요."
}
