package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.PhotoOcrIndexPolicy
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupPhase
import com.hebi.gallipick.data.cloud.CloudBackupProgress
import com.hebi.gallipick.data.cloud.CloudBackupResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private object ThumbnailCache {
    val permits = Semaphore(4)
    val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
}

@Composable
internal fun MediaThumbnail(
    item: MediaItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = item.displayName,
) {
    val resolver = LocalContext.current.contentResolver
    val key = "${item.uri}:${item.fingerprint}:${item.volumeVersion}"
    var bitmap by remember(key) { mutableStateOf(ThumbnailCache.bitmaps.get(key)) }
    var loading by remember(key) { mutableStateOf(bitmap == null) }
    LaunchedEffect(key) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) {
            ThumbnailCache.permits.withPermit {
                ThumbnailCache.bitmaps.get(key) ?: run {
                    val uri = Uri.parse(item.uri)
                    val thumbnail = try {
                        resolver.loadThumbnail(uri, Size(384, 384), null)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (item.kind == MediaKind.VIDEO) {
                            runCatching {
                                resolver.openFileDescriptor(uri, "r")?.use { input ->
                                    MediaMetadataRetriever().use { video ->
                                        video.setDataSource(input.fileDescriptor)
                                        video.getScaledFrameAtTime(
                                            -1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 384,
                                            MediaMetadataRetriever.BitmapParams(),
                                        )
                                    }
                                }
                            }.getOrNull()
                        } else null
                    }
                    thumbnail?.also { ThumbnailCache.bitmaps.put(key, it) }
                }
            }
        }
        loading = false
    }
    Box(modifier.background(Panel), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        when {
            loaded != null -> Image(
                loaded.asImageBitmap(), contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize().testTag("media-thumbnail-ready"), contentScale = contentScale,
            )
            loading -> CircularProgressIndicator(Modifier.size(20.dp).semantics {
                this.contentDescription = "미리보기 불러오는 중"
            }, strokeWidth = 2.dp)
            else -> GalliIcon(if (item.kind == MediaKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Image, "미리보기 없음", Modifier.size(30.dp), tint = Muted)
        }
    }
}

@Composable
private fun DetailPhoto(item: MediaItem, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val key = "${item.uri}:${item.fingerprint}:${item.volumeVersion}"
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableIntStateOf(0) }

    LaunchedEffect(key, attempt) {
        failed = false
        bitmap = null
        try {
            bitmap = withContext(Dispatchers.IO) { decodePreviewBitmap(resolver, Uri.parse(item.uri)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            failed = true
        } catch (_: Exception) {
            failed = true
        }
    }

    Box(
        modifier.background(Panel),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap!!.asImageBitmap(), contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
            failed -> Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("사진 미리보기를 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextButton(onClick = { attempt++ }) { Text("다시 시도") }
            }
            else -> CircularProgressIndicator(Modifier.semantics { contentDescription = "사진 미리보기 로딩 중" })
        }
    }
}

@Composable
internal fun SelectBox(
    checked: Boolean,
    enabled: Boolean = true,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(48.dp).semantics { contentDescription = description }.toggleable(
            value = checked, enabled = enabled, role = Role.Checkbox,
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = false, radius = 24.dp),
            onValueChange = { onClick() },
        ),
        contentAlignment = Alignment.Center,
    ) {
        SelectionIndicator(checked, enabled)
    }
}

@Composable
internal fun SelectionIndicator(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp)
            .background(if (checked) Ink else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, if (enabled) Ink else Line, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) GalliIcon(Icons.Outlined.Check, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onPrimary)
        else if (!enabled) GalliIcon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = Muted)
    }
}

@Composable
internal fun MediaDetailDialog(item: MediaItem, onDismiss: () -> Unit,
    onOpen: (MediaItem) -> Unit, onProtect: (Boolean) -> Unit,
    folderProtected: Boolean = false, ocrText: String? = null, ocrQuery: String = "",
    cloudBackup: CloudBackup? = null, accountUid: String? = null,
    cloudVerified: Boolean = false, onRequestCloud: () -> Unit = {},
    cloudDetailAccess: DetailCloudAccess = defaultDetailCloudAccess(cloudBackup, accountUid),
    cloudAccountEmail: String? = null,
    cloudNotice: String? = null,
) {
    MediaViewerDialog(onDismiss) { contentModifier ->
        Column(contentModifier) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("파일 상세", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                IconAction("닫기", Icons.Outlined.Close, onClick = onDismiss)
            }
            MediaDetail(item, onOpen, onProtect, Modifier.weight(1f),
                folderProtected, ocrText = ocrText, ocrQuery = ocrQuery,
                cloudBackup = cloudBackup, accountUid = accountUid,
                cloudVerified = cloudVerified, onRequestCloud = onRequestCloud,
                cloudDetailAccess = cloudDetailAccess, cloudAccountEmail = cloudAccountEmail, cloudNotice = cloudNotice)
        }
    }
}

@Composable
internal fun MediaDetail(item: MediaItem, onOpen: (MediaItem) -> Unit,
    onProtect: (Boolean) -> Unit, modifier: Modifier = Modifier,
    folderProtected: Boolean = false, ocrText: String? = null, ocrQuery: String = "",
    cloudBackup: CloudBackup? = null, accountUid: String? = null,
    cloudVerified: Boolean = false, onRequestCloud: () -> Unit = {},
    cloudDetailAccess: DetailCloudAccess = defaultDetailCloudAccess(cloudBackup, accountUid),
    cloudAccountEmail: String? = null,
    cloudNotice: String? = null,
) {
    var previewOpen by remember(item.uri, item.fingerprint, item.volumeVersion) { mutableStateOf(false) }
    if (previewOpen) MediaPreviewDialog(item, onDismiss = { previewOpen = false }, onOpen = onOpen)
    val scope = rememberCoroutineScope()
    val uploadKey = listOf(item.uri, item.fingerprint, item.volumeVersion, accountUid)
    var uploadPhase by remember(uploadKey) { mutableStateOf<CloudBackupPhase?>(null) }
    var uploadProgress by remember(uploadKey) { mutableStateOf<CloudBackupProgress?>(null) }
    var uploadBusy by remember(uploadKey) { mutableStateOf(false) }
    var uploadResult by remember(uploadKey) { mutableStateOf<CloudBackupResult?>(null) }
    var uploaded by remember(uploadKey) { mutableStateOf(false) }
    var confirmUpload by remember(uploadKey) { mutableStateOf(false) }
    var uploadCancelled by remember(uploadKey) { mutableStateOf(false) }
    var uploadJob by remember(uploadKey) { mutableStateOf<Job?>(null) }
    DisposableEffect(uploadKey) {
        onDispose { uploadJob?.cancel() }
    }
    val backedUp = cloudVerified || uploaded
    val resumeAvailable = accountUid?.let { uid ->
        cloudBackup?.accessFor(uid)?.let { access ->
            item.sizeBytes > access.maximumOriginalBytes && access.allowsResumableUpload(System.currentTimeMillis())
        }
    } == true
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val beside = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxHeight < 500.dp)
        val thumbnailHeight = (maxWidth / 1.2f).coerceAtMost(320.dp)
        val previewAction: @Composable (Modifier) -> Unit = { actionModifier ->
            ActionButton("미리보기", { previewOpen = true }, actionModifier, filled = true,
                icon = if (item.kind == MediaKind.VIDEO) Icons.Outlined.PlayArrow else Icons.Outlined.ZoomIn)
        }
        if (confirmUpload && cloudDetailAccess == DetailCloudAccess.READY && cloudBackup != null && accountUid != null) {
            AlertDialog(
                onDismissRequest = { confirmUpload = false },
                title = { Text("클라우드에 보관") },
                text = {
                    val limit = if (resumeAvailable) "요금제의 남은 용량을 사용합니다. 중단 후 같은 파일을 선택하면 이어서 올릴 수 있습니다."
                        else "현재 이용 환경의 파일 크기와 저장 용량 기준이 적용됩니다."
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                        CloudOriginalTransferNotice(
                            accountLabel = cloudAccountEmail ?: "현재 로그인한 Google 계정",
                            originalSize = bytesLabel(item.sizeBytes),
                        )
                        Text("모바일 데이터가 사용될 수 있습니다. $limit")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUpload = false
                        if (!uploadBusy && !backedUp) {
                            val backup = cloudBackup
                            val uid = accountUid
                            uploadBusy = true
                            uploadCancelled = false
                            uploadResult = null
                            uploadProgress = null
                            uploadJob = scope.launch {
                                try {
                                    val result = backup.upload(item, uid, onProgress = { uploadProgress = it }) { uploadPhase = it }
                                    uploadResult = result
                                    if (result == CloudBackupResult.VERIFIED) uploaded = true
                                } catch (_: CancellationException) {
                                    uploadCancelled = true
                                } catch (_: Exception) {
                                    uploadResult = CloudBackupResult.FAILED
                                } finally {
                                    uploadBusy = false
                                    uploadPhase = null
                                    uploadProgress = null
                                    uploadJob = null
                                }
                            }
                        }
                    }) { Text("보관 시작") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUpload = false }) { Text("취소") }
                },
            )
        }
        val cloudAction: @Composable (Modifier) -> Unit = { actionModifier ->
            val ableToAct = when (cloudDetailAccess) {
                DetailCloudAccess.READY -> cloudBackup != null && accountUid != null
                DetailCloudAccess.SIGN_IN, DetailCloudAccess.CHECK_ACCESS, DetailCloudAccess.PLAN_REQUIRED -> true
                else -> false
            }
            ActionButton(
                when {
                    backedUp -> "보관 완료"
                    uploadBusy -> "보관 중"
                    cloudDetailAccess == DetailCloudAccess.SIGN_IN -> "로그인하여 보관"
                    cloudDetailAccess == DetailCloudAccess.PLAN_REQUIRED -> "요금제·이용 권한 확인"
                    cloudDetailAccess == DetailCloudAccess.CHECK_ACCESS -> "보관 권한 다시 확인"
                    cloudDetailAccess == DetailCloudAccess.CHECKING -> "이용 상태 확인 중"
                    else -> "클라우드 보관"
                },
                onClick = {
                    if (cloudDetailAccess == DetailCloudAccess.READY) confirmUpload = true
                    else onRequestCloud()
                },
                modifier = actionModifier,
                enabled = ableToAct && !uploadBusy && !backedUp,
                icon = Icons.Outlined.CloudUpload,
            )
        }
        val actions: @Composable (Modifier) -> Unit = { actionModifier ->
            BoxWithConstraints(actionModifier.fillMaxWidth()) {
                val narrow = maxWidth < 272.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        previewAction(Modifier.fillMaxWidth())
                        cloudAction(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        previewAction(Modifier.weight(1f))
                        cloudAction(Modifier.weight(1f))
                    }
                }
            }
        }
        val metadata: @Composable (Modifier) -> Unit = { detailModifier ->
            Column(
                detailModifier.then(if (beside) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                cloudNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
                if (ocrText != null) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("사진 속 글자 · 검색 일치",
                            style = MaterialTheme.typography.titleSmall, color = Ink)
                        Text(PhotoOcrIndexPolicy.snippet(ocrText, ocrQuery, 450),
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                if (!beside) actions(Modifier.fillMaxWidth())
                if (uploadBusy) {
                    TextAction("보관 중지", {
                        uploadCancelled = true
                        uploadJob?.cancel()
                    })
                    Text(when (uploadPhase) {
                        CloudBackupPhase.READING_ORIGINAL -> "원본 확인 중"
                        CloudBackupPhase.UPLOADING -> "클라우드 업로드 중"
                        CloudBackupPhase.VERIFYING -> "서버에서 원본 검증 중"
                        null -> "클라우드 보관 준비 중"
                    }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    uploadProgress?.let { progress ->
                        Text("${bytesLabel(progress.uploadedBytes)} / ${bytesLabel(progress.totalBytes)}",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (uploadCancelled) {
                    val message = if (resumeAvailable) "보관을 중단했습니다. 같은 파일에서 클라우드 보관을 다시 누르면 이어서 올릴 수 있습니다."
                        else "보관을 중단했습니다. 보관함에서 완료 여부를 확인해 주세요."
                    Text(message,
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                } else if (uploadResult != null && !backedUp) {
                    val message = when (uploadResult) {
                        CloudBackupResult.QUOTA_REACHED -> "클라우드 저장 용량이 부족합니다."
                        CloudBackupResult.VERIFICATION_PENDING -> "전송을 마쳤습니다. 서버에서 원본을 검증하고 있습니다."
                        CloudBackupResult.UNSUPPORTED_MEDIA -> "이 크기 또는 형식의 파일은 현재 보관할 수 없습니다."
                        CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경되어 보관을 중단했습니다."
                        CloudBackupResult.NETWORK_ERROR -> "네트워크 연결을 확인해 주세요."
                        CloudBackupResult.INTEGRITY_ERROR -> "파일 검증에 실패했습니다. 원본을 확인해 주세요."
                        CloudBackupResult.TEST_ACCESS_REQUIRED -> "현재 계정에서는 백업할 수 없습니다."
                        else -> "클라우드 보관에 실패했습니다. 다시 시도해 주세요."
                    }
                    Text(message, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val protectLabel = if (folderProtected) "폴더 보호 중" else if (item.isProtected) "보호 해제" else "정리에서 보호"
                    val stacked = maxWidth < 350.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    if (stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("파일 열기", { onOpen(item) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.OpenInNew)
                            ActionButton(protectLabel, { onProtect(!item.isProtected) }, Modifier.fillMaxWidth(),
                                enabled = !folderProtected, icon = Icons.Outlined.Shield)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("파일 열기", { onOpen(item) }, Modifier.weight(1f), icon = Icons.Outlined.OpenInNew)
                            ActionButton(protectLabel, { onProtect(!item.isProtected) }, Modifier.weight(1f),
                                enabled = !folderProtected, icon = Icons.Outlined.Shield)
                        }
                    }
                }
                if (folderProtected || item.isProtected) {
                    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GalliIllustration(GalliScene.PROTECT, Modifier.size(60.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("정리에서 보호 중", style = MaterialTheme.typography.titleSmall, color = Ink)
                            Text(if (folderProtected) "폴더 보호는 설정의 보호 폴더에서 해제할 수 있습니다." else "이 파일은 정리 선택에서 제외됩니다.",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (item.isFavorite) Text("즐겨찾기 파일은 정리에서 제외됩니다. 즐겨찾기 변경은 갤러리에서 해주세요.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                Column(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DetailRow("용량", bytesLabel(item.sizeBytes))
                    DetailRow("해상도", "${item.width} × ${item.height} · %.1f MP".format(item.pixels / 1_000_000.0))
                    if (item.kind == MediaKind.VIDEO) DetailRow("재생 시간", durationLabel(item.durationMs))
                    DetailRow(if (item.dateTakenMs > 0) "촬영일" else "저장일", dateLabel(item.capturedAt))
                    DetailRow("추가일", dateLabel(item.dateAddedMs))
                    DetailRow("수정일", dateLabel(item.dateModifiedMs))
                    DetailRow("파일 형식", item.mimeType.ifEmpty { "정보 없음" })
                    DetailRow("폴더", item.relativePath.ifEmpty { "기본 폴더" })
                }
            }
        }
        if (beside) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.kind == MediaKind.PHOTO) DetailPhoto(item, Modifier.weight(1f).fillMaxWidth())
                else MediaThumbnail(item, Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
                actions(Modifier.fillMaxWidth())
            }
            metadata(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (item.kind == MediaKind.PHOTO) DetailPhoto(item, Modifier.fillMaxWidth().height(thumbnailHeight))
            else MediaThumbnail(item, Modifier.fillMaxWidth().height(thumbnailHeight), contentScale = ContentScale.Fit)
            metadata(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.widthIn(min = 76.dp),
            style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis)
    }
}
