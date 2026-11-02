package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.domain.MediaItem

@Composable
internal fun TrashReviewDialog(moving: List<MediaItem>, kept: List<MediaItem>, restore: Boolean, excluded: Int, onDismiss: () -> Unit, onProceed: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight)) {
                TrashReviewContent(moving, kept, restore, excluded, onDismiss, onProceed)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashReviewContent(moving: List<MediaItem>, kept: List<MediaItem>, restore: Boolean, excluded: Int, onDismiss: () -> Unit, onProceed: () -> Unit, modifier: Modifier = Modifier) {
    var showKept by remember { mutableStateOf(false) }
    val showTabs = !restore && kept.isNotEmpty()
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (restore) "파일 복원" else "휴지통 이동", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconAction("확인창 닫기", Icons.Rounded.Close, onClick = onDismiss)
        }
        if (showTabs) PrimaryTabRow(selectedTabIndex = if (showKept) 1 else 0) {
            Tab(!showKept, { showKept = false }, text = { Text("이동 ${moving.size}개") })
            Tab(showKept, { showKept = true }, text = { Text("남김 ${kept.size}묶음") })
        }
        val displayed = if (showKept && showTabs) kept else moving
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = if (showKept && showTabs) "남길 파일 확인 목록" else "이동할 파일 확인 목록" },
            contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("summary") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (showKept && showTabs) "각 묶음에서 확인한 남길 사본입니다. 선택하지 않은 다른 파일도 그대로 남습니다."
                        else "${moving.size}개 · 파일 용량 ${bytesLabel(duplicateBytes(moving))}", style = MaterialTheme.typography.bodyMedium)
                    if (excluded > 0) Text("변경되었거나 보호된 ${excluded}개는 제외했습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text(if (restore) "다음 단계에서 Android 확인 후 원래 위치로 복원을 요청합니다."
                        else "다음 단계에서 Android 확인창이 열립니다. 휴지통 이동만으로 즉시 여유 공간이 늘어나지는 않을 수 있습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(displayed, key = { it.uri }) { file ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (showKept && showTabs) "남길 파일" else if (restore) "복원 대상" else "이동 대상으로 선택됨", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(file.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(file.relativePath.ifBlank { "기본 폴더" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("취소", onDismiss, Modifier.weight(1f))
            ActionButton("Android 확인", onProceed, Modifier.weight(1f), filled = true)
        }
    }
}
