package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivity
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hebi.gallipick.data.cloud.cloudPhotoSupportsViewer
import com.hebi.gallipick.data.cloud.isCloudVideoStreamable
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.normalizeCloudFolderPath
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun CloudLibrarySection(
    connection: CloudAccountConnection,
    onRestored: () -> Unit,
    loadOriginals: suspend (CloudBackup, String) -> List<CloudOriginal> = CloudBackup::listOriginals,
    onOpenRestored: (Uri, String) -> Unit,
) {
    val backup = connection.backup ?: return
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    val activity = LocalActivity.current
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val onCheckAccount: (() -> Unit)? = activity?.let { owner ->
        { scope.launch { connection.chooseAccount(owner) } }
    }
    key(uid) { CloudLibraryControls(backup, uid, onRestored, onOpenRestored, onCheckAccount, loadOriginals) }
}

private data class LibraryAction(val item: CloudOriginal, val preview: Boolean)

@Composable
private fun CloudLibraryControls(
    backup: CloudBackup,
    uid: String,
    onRestored: () -> Unit,
    onOpenRestored: (Uri, String) -> Unit,
    onCheckAccount: (() -> Unit)?,
    loadOriginals: suspend (CloudBackup, String) -> List<CloudOriginal>,
) {
    var catalog by remember { mutableStateOf(emptyList<CloudOriginal>()) }
    var loading by remember { mutableStateOf(true) }
    var catalogIssue by remember { mutableStateOf<CloudCatalogIssue?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<CloudOriginal?>(null) }
    var playingVideo by remember { mutableStateOf<CloudOriginal?>(null) }
    var action by remember { mutableStateOf<LibraryAction?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var preview by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    val revision by backup.catalogRevision.collectAsStateWithLifecycle()
    val network = rememberCloudNetworkStatus(enabled = true)
    val formatDate = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LaunchedEffect(revision, reload, network.online) {
        if (!network.online) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val originals = loadOriginals(backup, uid)
            currentCoroutineContext().ensureActive()
            catalog = originals
            catalogIssue = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val issue = cloudCatalogIssue(error)
            if (issue.blocksCachedCatalog) {
                catalog = emptyList()
                folder = null
                action = null
                selected = null
                playingVideo = null
                preview = null
                restored = null
                status = null
            }
            catalogIssue = issue
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

    val byFolder = remember(catalog) {
        catalog.flatMap { original ->
            original.sourceFolders.mapNotNull(::normalizeCloudFolderPath).distinct().map { it to original }
        }.groupBy({ it.first }, { it.second }).toSortedMap()
    }
    val currentFolder = folder?.takeIf(byFolder::containsKey)
    val visible = currentFolder?.let(byFolder::get) ?: catalog
    val blocked = catalogIssue?.blocksCachedCatalog == true
    val canOpenOriginals = network.online && !loading && catalogIssue == null && action == null

    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(GalliUiMetrics.cardPadding),
        verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
    ) {
        when {
            blocked -> {
                CloudStatusMessage(if (catalogIssue == CloudCatalogIssue.ACCOUNT_RESTRICTED)
                    "현재 계정은 클라우드 보관함을 이용할 수 없습니다."
                else "보관함을 열려면 Google 계정 연결을 확인해 주세요.")
                onCheckAccount?.let { CloudPrimaryAction("계정 확인", it) }
            }
            !network.online -> CloudStatusMessage(if (catalog.isNotEmpty())
                "오프라인에서 최근 목록을 표시합니다. 원본 열람·복원에는 인터넷 연결이 필요합니다."
            else "오프라인 상태입니다. 인터넷에 연결하면 보관함을 다시 확인합니다.")
            catalogIssue != null -> {
                CloudStatusMessage(if (catalog.isNotEmpty())
                    "보관함을 새로 불러오지 못했습니다. 최근에 확인한 목록을 표시합니다."
                else "보관함을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
                CloudPrimaryAction("다시 시도", { reload++ }, enabled = !loading && action == null)
            }
            else -> ActionButton("목록 새로고침", { reload++ }, enabled = !loading && action == null,
                icon = Icons.Outlined.Refresh)
        }
        when {
            catalog.isEmpty() && (catalogIssue != null || !network.online) -> Unit
            loading && catalog.isEmpty() -> CloudStatusMessage("보관함을 확인하는 중입니다.")
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
            else -> {
                if (byFolder.isNotEmpty()) CloudLibraryFolderPicker(currentFolder, byFolder.keys, { folder = it })
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = CloudOriginal::uploadId) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                            val date = formatDate.format(Date(item.verifiedAtMs))
                            val media = if (item.mimeType.startsWith("video/")) "동영상" else "사진"
                            Text("$media · ${bytesLabel(item.sizeBytes)} · $date", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                            if (cloudPhotoSupportsViewer(item)) {
                                ActionButton("미리보기", { action = LibraryAction(item, preview = true) },
                                    enabled = canOpenOriginals)
                            }
                            if (isCloudVideoStreamable(item)) {
                                ActionButton("앱에서 바로 재생", { playingVideo = item }, enabled = canOpenOriginals)
                            }
                            ActionButton("기기에 복원", { selected = item }, enabled = canOpenOriginals)
                        }
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
                ActionButton("원본 복원", { selected = null; action = LibraryAction(item, preview = false) },
                    filled = true, enabled = canOpenOriginals)
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

@Composable
private fun CloudLibraryFolderPicker(
    current: String?,
    folders: Set<String>,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ActionButton(current?.let { "원본 폴더 · $it" } ?: "원본 폴더 · 전체", { open = true }, Modifier.fillMaxWidth())
    if (!open) return
    val sortedFolders = remember(folders) { folders.toList() }
    fun select(folder: String?) {
        open = false
        onSelect(folder)
    }
    AlertDialog(
        onDismissRequest = { open = false },
        containerColor = AppSurface,
        title = { Text("원본 폴더") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                item { TextAction("모든 원본 폴더", { select(null) }, Modifier.fillMaxWidth()) }
                items(sortedFolders, key = { it }) { folder ->
                    TextAction(folder, { select(folder) }, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextAction("닫기", { open = false }) },
    )
}

private fun cloudLibraryFailure(error: Exception): String = when ((error as? CloudBackupException)?.result) {
    CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경되어 작업이 중단됐습니다."
    CloudBackupResult.TEST_ACCESS_REQUIRED -> "현재 계정의 클라우드 보관함 이용 권한을 확인해 주세요."
    CloudBackupResult.NETWORK_ERROR -> "네트워크 연결을 확인한 뒤 다시 시도해 주세요."
    CloudBackupResult.INTEGRITY_ERROR -> "서버 원본의 버전, 크기 또는 해시가 일치하지 않습니다."
    CloudBackupResult.DEVICE_STORAGE_ERROR -> "기기 저장공간과 갤러리 상태를 확인해 주세요."
    CloudBackupResult.QUOTA_REACHED -> "요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
    else -> "클라우드 원본을 확인하지 못했습니다. 다시 시도해 주세요."
}
