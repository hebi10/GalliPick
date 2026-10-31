package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind

@Composable
internal fun TrashScreen(
    items: List<MediaItem>,
    onRestore: (List<MediaItem>) -> Unit,
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(items) { selected = selected.intersect(items.mapTo(mutableSetOf()) { it.uri }) }
    val selectedItems = items.filter { it.uri in selected }
    val toggle: (MediaItem) -> Unit = { item ->
        selected = if (item.uri in selected) selected - item.uri else selected + item.uri
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconAction("뒤로", Icons.Rounded.ArrowBack, onClick = onBack)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("휴지통", style = MaterialTheme.typography.headlineMedium)
                    Text("${items.size}개 · ${bytesLabel(items.sumOf { it.sizeBytes })}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "휴지통은 Android가 관리합니다. 보관 기간이 지나면 파일이 자동 삭제될 수 있으며, 복원할 때 시스템 확인이 표시됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp),
            )
            if (items.isEmpty()) {
                EmptyState("휴지통이 비어 있습니다", "휴지통으로 이동한 사진과 동영상이 여기에 표시됩니다.", Icons.Rounded.DeleteOutline)
            } else {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val allSelected = selectedItems.size == items.size
                    SelectBox(
                        checked = allSelected,
                        description = "휴지통 전체 선택",
                        onClick = { selected = if (!allSelected) items.mapTo(mutableSetOf()) { it.uri } else emptySet() },
                    )
                    Text("전체 선택", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("${selectedItems.size}개 선택", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.uri }) { item ->
                        TrashRow(item, item.uri in selected) { toggle(item) }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        if (selectedItems.isEmpty()) "복원할 파일을 선택하세요." else "선택한 ${selectedItems.size}개 · ${bytesLabel(selectedItems.sumOf { it.sizeBytes })}",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    ActionButton(
                        "선택한 파일 복원", onClick = { onRestore(selectedItems) },
                        modifier = Modifier.fillMaxWidth(), filled = true,
                        enabled = selectedItems.isNotEmpty(), icon = Icons.Rounded.RestoreFromTrash,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashRow(item: MediaItem, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Panel else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) Ink else Line, RoundedCornerShape(16.dp))
            .plainClick(action = onToggle).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectBox(checked = selected, description = "${item.displayName} 복원 선택", onClick = onToggle)
        MediaThumbnail(item, Modifier.size(76.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (item.kind == MediaKind.VIDEO) "동영상" else "사진"} · ${bytesLabel(item.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            if (item.relativePath.isNotBlank()) {
                Text(item.relativePath, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.isFavorite || item.isProtected) {
                Text(
                    listOfNotNull("즐겨찾기".takeIf { item.isFavorite }, "보호됨".takeIf { item.isProtected }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
        }
    }
}
