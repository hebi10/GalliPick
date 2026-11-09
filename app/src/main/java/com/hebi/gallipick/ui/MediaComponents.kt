package com.hebi.gallipick.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.PhotoOcrIndexPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private object ThumbnailCache {
    val permits = Semaphore(4)
    val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
}

@Composable
internal fun MediaThumbnail(item: MediaItem, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val resolver = LocalContext.current.contentResolver
    val key = "${item.uri}:${item.fingerprint}:${item.volumeVersion}"
    var bitmap by remember(key) { mutableStateOf(ThumbnailCache.bitmaps.get(key)) }
    LaunchedEffect(key) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) {
            ThumbnailCache.permits.withPermit {
                ThumbnailCache.bitmaps.get(key) ?: try {
                    resolver.loadThumbnail(Uri.parse(item.uri), Size(384, 384), null).also { ThumbnailCache.bitmaps.put(key, it) }
                } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Panel), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), contentDescription = item.displayName, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        else GalliIcon(if (item.kind == MediaKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Image, "미리보기 없음", Modifier.size(30.dp), tint = Muted)
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
        modifier.clip(RoundedCornerShape(12.dp)).background(Panel),
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
    indicatorAtTopStart: Boolean = false,
) {
    Box(
        modifier.size(48.dp).semantics { contentDescription = description }.toggleable(
            value = checked, enabled = enabled, role = Role.Checkbox,
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(bounded = false, radius = 24.dp),
            onValueChange = { onClick() },
        ),
        contentAlignment = if (indicatorAtTopStart) Alignment.TopStart else Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp)
                .then(if (indicatorAtTopStart) Modifier.offset(x = 4.dp, y = 4.dp).testTag("grid-selection-indicator") else Modifier)
                .background(if (checked) Ink else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, if (enabled) Ink else Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) GalliIcon(Icons.Outlined.Check, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onPrimary)
            else if (!enabled) GalliIcon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = Muted)
        }
    }
}

@Composable
internal fun MediaDetailDialog(item: MediaItem, onDismiss: () -> Unit,
    onOpen: (MediaItem) -> Unit, onProtect: (Boolean) -> Unit,
    folderProtected: Boolean = false, ocrText: String? = null, ocrQuery: String = "",
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.safeDrawingPadding().widthIn(max = 1100.dp).fillMaxWidth(.96f).fillMaxHeight(.94f), shape = RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("파일 상세", style = MaterialTheme.typography.titleLarge)
                        Text(if (item.kind == MediaKind.VIDEO) "동영상 정보" else "사진 정보", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconAction("닫기", Icons.Outlined.Close, onClick = onDismiss)
                }
                MediaDetail(item, onOpen, onProtect, Modifier.weight(1f),
                    folderProtected, ocrText = ocrText, ocrQuery = ocrQuery)
            }
        }
    }
}

@Composable
internal fun MediaDetail(item: MediaItem, onOpen: (MediaItem) -> Unit,
    onProtect: (Boolean) -> Unit, modifier: Modifier = Modifier,
    folderProtected: Boolean = false, ocrText: String? = null, ocrQuery: String = "",
) {
    var previewOpen by remember(item.uri, item.fingerprint, item.volumeVersion) { mutableStateOf(false) }
    if (previewOpen) MediaPreviewDialog(item, onDismiss = { previewOpen = false }, onOpen = onOpen)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val beside = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxHeight < 500.dp)
        val thumbnailHeight = (maxWidth / 1.2f).coerceAtMost(320.dp)
        val previewAction: @Composable (Modifier) -> Unit = { actionModifier ->
            ActionButton("앱에서 미리보기", { previewOpen = true }, actionModifier, filled = true,
                icon = if (item.kind == MediaKind.VIDEO) Icons.Outlined.PlayArrow else Icons.Outlined.ZoomIn)
        }
        val metadata: @Composable (Modifier) -> Unit = { detailModifier ->
            Column(detailModifier.then(if (beside) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${if (item.kind == MediaKind.VIDEO) "동영상" else "사진"} · ${bytesLabel(item.sizeBytes)}", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                if (!beside) previewAction(Modifier.fillMaxWidth())
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val protectLabel = if (folderProtected) "폴더 보호 중" else if (item.isProtected) "보호 해제" else "보호"
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
        if (beside) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.kind == MediaKind.PHOTO) DetailPhoto(item, Modifier.weight(1f).fillMaxWidth())
                else MediaThumbnail(item, Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
                previewAction(Modifier.fillMaxWidth())
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
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.widthIn(min = 76.dp),
            style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis)
    }
}
