package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.SelectionPolicy

@Composable
internal fun FilesFooterSummary(
    summary: String,
    selectable: List<MediaItem>,
    selectAll: (List<MediaItem>) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.weight(1f, fill = false)
                    .semantics { contentDescription = "파일 목록 요약" },
            )
            if (selectable.isNotEmpty()) TextAction("현재 결과 ${selectable.size}개 선택", { selectAll(selectable) })
        }
        if (selectable.size == SelectionPolicy.MAX_SELECTION_SIZE) {
            Text("한 번에 최대 ${SelectionPolicy.MAX_SELECTION_SIZE}개까지 선택할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SelectionBar(
    selected: List<MediaItem>,
    clear: () -> Unit,
    trash: () -> Unit,
    clearOnSearchChange: Boolean = false,
) {
    if (selected.isEmpty()) return
    Column(Modifier.fillMaxWidth().galliCardSurface(backgroundColor = Panel).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).widthIn(min = 96.dp).align(Alignment.CenterVertically)) {
                Text("${selected.size}개 선택", style = MaterialTheme.typography.titleSmall)
                Text(bytesLabel(selected.sumOf { it.sizeBytes }), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            TextAction("해제", clear, Modifier.align(Alignment.CenterVertically))
            ActionButton("휴지통으로", trash, Modifier.align(Alignment.CenterVertically), filled = true, icon = Icons.Outlined.DeleteOutline)
        }
        Text("즐겨찾기·보호 파일 제외 · 이동 전 Android 확인", color = Muted, style = MaterialTheme.typography.labelSmall)
        if (clearOnSearchChange) Text("검색 조건이나 저장 위치를 바꾸면 해제됩니다.",
            color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
