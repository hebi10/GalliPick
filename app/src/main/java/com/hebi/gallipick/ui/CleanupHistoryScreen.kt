package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.CleanupHistoryAction
import com.hebi.gallipick.domain.CleanupHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CleanupHistoryScreen(
    entries: List<CleanupHistoryEntry>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    var deleteId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction("정리로 돌아가기", Icons.Rounded.ArrowBack, onClick = onBack)
                Text("정리 기록", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text("Android에서 파일 상태를 다시 확인한 이동·복원 작업만 저장합니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("기록 삭제는 기록만 지우며, 사진·동영상이나 Android 휴지통에는 영향을 주지 않습니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                EmptyState("정리 기록이 없습니다", "확인된 휴지통 이동이나 복원 작업이 이곳에 표시됩니다.")
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("총 ${entries.size}건", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmClear = true }) { Text("전체 기록 삭제") }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Column(
                            Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(GalliUiMetrics.cardCorner))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    if (entry.action == CleanupHistoryAction.RESTORE) "파일 복원" else "휴지통 이동",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(dateFormat.format(Date(entry.occurredAtMs)),
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            Text("확인 완료 ${entry.confirmedCount}개",
                                style = MaterialTheme.typography.bodyMedium)
                            if (entry.confirmedCount < entry.requestedCount) {
                                Text("전체 ${entry.requestedCount}개 요청 중 일부 확인",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            Text("확인된 파일 용량 ${bytesLabel(entry.confirmedBytes)}",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                            if (entry.action == CleanupHistoryAction.MOVE_TO_TRASH) {
                                Text("휴지통에 이동한 파일 용량이며 실제 확보 공간은 아닙니다.",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            TextButton(
                                onClick = { deleteId = entry.id },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("기록 삭제") }
                        }
                    }
                }
            }
        }
    }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("정리 기록 삭제") },
            text = { Text("선택한 기록만 삭제합니다. 원본 파일과 휴지통 상태는 변경되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteId
                    deleteId = null
                    if (id != null) onDelete(id)
                }) { Text("삭제 확인") }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("취소") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("전체 정리 기록 삭제") },
            text = { Text("모든 정리 기록을 삭제합니다. 사진이나 Android 휴지통은 변경되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("전체 삭제 확인") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}
