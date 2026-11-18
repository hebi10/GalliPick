package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudOriginal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudFileDetailDialog(
    original: CloudOriginal,
    uid: String?,
    backup: CloudBackup?,
    offline: Boolean,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onRestored: () -> Unit = {},
    autoLoadThumbnails: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var saving by remember(original.uploadId, uid) { mutableStateOf(false) }
    var message by remember(original.uploadId, uid) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.safeDrawingPadding().widthIn(max = 760.dp).fillMaxWidth(.96f).fillMaxHeight(.94f),
            color = AppSurface,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("파일 상세", style = MaterialTheme.typography.titleLarge)
                        Text(if (original.mimeType.startsWith("video/")) "동영상 정보" else "사진 정보",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    IconAction("닫기", Icons.Outlined.Close, onClick = onDismiss)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(220.dp).background(Panel),
                        contentAlignment = Alignment.Center) {
                        if (original.mimeType.startsWith("image/")) {
                            CloudPhotoThumbnail(original, uid, backup, Modifier.fillMaxSize(),
                                open = onPreview, autoLoad = !offline && autoLoadThumbnails)
                        } else {
                            GalliIcon(Icons.Outlined.Movie, "클라우드 동영상", tint = Muted)
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val narrow = maxWidth < 272.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        if (narrow) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton("미리보기", onPreview, Modifier.fillMaxWidth(), filled = true,
                                    enabled = !offline && uid != null && backup != null,
                                    icon = Icons.Outlined.PlayArrow)
                                ActionButton("보관 완료", {}, Modifier.fillMaxWidth(), enabled = false,
                                    icon = Icons.Outlined.CloudDone)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton("미리보기", onPreview, Modifier.weight(1f), filled = true,
                                    enabled = !offline && uid != null && backup != null,
                                    icon = Icons.Outlined.PlayArrow)
                                ActionButton("보관 완료", {}, Modifier.weight(1f), enabled = false,
                                    icon = Icons.Outlined.CloudDone)
                            }
                        }
                    }
                    Text(original.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("클라우드에 보관됨", color = Muted, style = MaterialTheme.typography.bodySmall)
                    CloudFileInfoRow("용량", bytesLabel(original.sizeBytes))
                    CloudFileInfoRow("파일 형식", original.mimeType)
                    CloudFileInfoRow("촬영일", original.capturedAtMs?.let(::dateLabel) ?: "정보 없음")
                    CloudFileInfoRow("원본 폴더", original.sourceFolders.joinToString(", ").ifBlank { "미분류" })
                    CloudFileInfoRow("저장 위치", "클라우드")
                    ActionButton(if (saving) "기기에 저장 중" else "기기에 저장", {
                        if (uid != null && backup != null && !offline && !saving) {
                            saving = true
                            message = null
                            scope.launch {
                                try {
                                    backup.restore(original, uid)
                                    onRestored()
                                    message = "원본 파일을 기기에 저장했습니다."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    message = "기기에 저장하지 못했습니다. 연결과 저장 공간을 확인해 주세요."
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !offline && uid != null && backup != null && !saving,
                        icon = Icons.Outlined.Download)
                    if (offline) Text("인터넷 연결 후 미리보기 또는 기기에 저장할 수 있습니다.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun CloudFileInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.widthIn(min = 76.dp), color = Muted,
            style = MaterialTheme.typography.labelMedium)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
