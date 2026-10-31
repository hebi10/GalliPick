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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
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
internal fun MediaThumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val key = "${item.uri}:${item.fingerprint}"
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
    Box(modifier.background(Panel), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) Image(loaded.asImageBitmap(), contentDescription = item.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (item.kind == MediaKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Image, "미리보기 없음", Modifier.size(30.dp), tint = Muted)
    }
}

@Composable
internal fun SelectBox(checked: Boolean, enabled: Boolean = true, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).semantics { contentDescription = description }.toggleable(
        value = checked, enabled = enabled, role = Role.Checkbox,
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onValueChange = { onClick() },
    ), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).background(if (checked) Ink else Color.White).border(1.dp, if (enabled) Ink else Line), contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(19.dp), tint = Color.White)
            else if (!enabled) Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = Muted)
        }
    }
}

@Composable
internal fun MediaDetailDialog(item: MediaItem, onDismiss: () -> Unit, onOpen: (MediaItem) -> Unit, onProtect: (Boolean) -> Unit, folderProtected: Boolean = false) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).widthIn(max = 560.dp).fillMaxHeight(.9f), shape = RectangleShape) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("파일 정보", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconAction("닫기", Icons.Outlined.Close, onClick = onDismiss)
                }
                MediaDetail(item, onOpen, onProtect, Modifier.weight(1f), folderProtected)
            }
        }
    }
}

@Composable
internal fun MediaDetail(item: MediaItem, onOpen: (MediaItem) -> Unit, onProtect: (Boolean) -> Unit, modifier: Modifier = Modifier, folderProtected: Boolean = false) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MediaThumbnail(item, Modifier.fillMaxWidth().aspectRatio(1.2f))
        Text(item.displayName, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("원본 열기", { onOpen(item) }, Modifier.weight(1f), filled = true, icon = Icons.Outlined.OpenInNew)
            ActionButton(if (folderProtected) "폴더 보호 중" else if (item.isProtected) "보호 해제" else "보호", { onProtect(!item.isProtected) }, Modifier.weight(1f), enabled = !folderProtected, icon = Icons.Outlined.Shield)
        }
        if (folderProtected) Text("폴더 보호는 설정의 보호 폴더에서 해제할 수 있습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (item.isFavorite || item.isProtected) Text(
            if (item.isFavorite) "즐겨찾기 파일은 정리에서 제외됩니다. 즐겨찾기 변경은 갤러리에서 해주세요." else "보호한 파일은 정리에서 제외됩니다.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        DetailRow("용량", bytesLabel(item.sizeBytes))
        DetailRow("해상도", "${item.width} × ${item.height} · %.1f MP".format(item.pixels / 1_000_000.0))
        if (item.kind == MediaKind.VIDEO) DetailRow("재생 시간", durationLabel(item.durationMs))
        DetailRow("촬영일", dateLabel(item.capturedAt))
        DetailRow("추가일", dateLabel(item.dateAddedMs))
        DetailRow("수정일", dateLabel(item.dateModifiedMs))
        DetailRow("파일 형식", item.mimeType.ifEmpty { "정보 없음" })
        DetailRow("폴더", item.relativePath.ifEmpty { "기본 폴더" })
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
