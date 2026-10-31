package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.domain.*

@Composable
internal fun DuplicatesScreen(state: AppUiState, vm: AppViewModel, trash: (List<MediaItem>) -> Unit, open: (MediaItem) -> Unit) {
    var detailUri by remember { mutableStateOf<String?>(null) }
    val liveItems = remember(state.items) { state.items.associateBy { it.uri } }
    val groups = remember(state.duplicates, state.items) {
        state.duplicates.map { group -> group.copy(items = group.items.mapNotNull { liveItems[it.uri] }) }.filter { it.items.size > 1 }
    }
    val candidates = remember(groups) { groups.flatMap(DuplicatePolicy::deletionCandidates).map { it.uri }.toSet() }
    val selected = state.items.filter { it.uri in state.selected && it.uri in candidates && it.canTrash() }
    val scan = state.scan
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        SectionTitle("완전히 같은 파일", "파일 전체의 SHA-256 해시로 중복 사본을 비교합니다.")
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(scanTitle(scan), style = MaterialTheme.typography.titleMedium)
                    if (scan.total > 0) Text("${scan.completed} / ${scan.total}개 · 건너뜀 ${scan.skipped}개", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                when (scan.phase) {
                    ScanPhase.RUNNING, ScanPhase.QUEUED -> ActionButton("일시정지", vm::pauseScan, icon = Icons.Rounded.Pause)
                    ScanPhase.PAUSED -> ActionButton("계속 검사", vm::resumeScan, filled = true, icon = Icons.Rounded.PlayArrow)
                    else -> ActionButton(if (scan.phase == ScanPhase.COMPLETE) "다시 검사" else "검사 시작", vm::startScan, filled = true, enabled = state.items.isNotEmpty(), icon = Icons.Rounded.Search)
                }
            }
            if (scan.total > 0) {
                val progress = ((scan.completed + scan.skipped).toFloat() / scan.total).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(12.dp)).background(Line)) {
                    if (progress > 0) Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Ink, RoundedCornerShape(12.dp)))
                }
            }
            if (scan.message.isNotBlank()) Text(scan.message, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(12.dp))
        Text("비슷한 사진 검색은 현재 지원하지 않습니다. 즐겨찾기·보호 파일과 추천 보관본은 선택에서 제외합니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(12.dp))
        if (groups.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${groups.size}개 중복 묶음", style = MaterialTheme.typography.titleMedium)
                    Text("추천 사본 ${candidates.size}개 · ${bytesLabel(DuplicatePolicy.reclaimableBytes(groups))}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                TextAction("추천 선택", vm::selectRecommendedDuplicates)
            }
            Text("보관 추천: 보호 → 즐겨찾기 → Camera 폴더 → 촬영일 순", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(bottom = 10.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                groups.forEachIndexed { index, group ->
                    val keep = DuplicatePolicy.recommendedKeep(group)
                    item("header:${group.hash}") {
                        Text("${index + 1} · ${group.items.size}개 파일 · 파일당 ${bytesLabel(keep.sizeBytes)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(group.items, key = { "${group.hash}:${it.uri}" }) { item ->
                        val isKeeper = item.uri == keep.uri
                        val selectedCopy = item.uri in state.selected && !isKeeper
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (isKeeper || selectedCopy) Panel else MaterialTheme.colorScheme.surface)
                                .border(1.dp, if (isKeeper || selectedCopy) Ink else Line, RoundedCornerShape(16.dp)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MediaThumbnail(item, Modifier.size(66.dp).plainClick { detailUri = item.uri })
                            Column(Modifier.weight(1f).plainClick { detailUri = item.uri }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(item.relativePath.trimEnd('/').ifEmpty { "기본 폴더" }, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                Text(when { isKeeper -> "보관 추천"; item.isFavorite -> "즐겨찾기 · 선택 제외"; item.isProtected -> "보호 파일 · 선택 제외"; else -> "사본 · ${bytesLabel(item.sizeBytes)}" }, style = MaterialTheme.typography.labelSmall)
                            }
                            SelectBox(selectedCopy, !isKeeper && item.canTrash(), if (isKeeper) "보관 추천 파일" else "${item.displayName} 선택", { vm.toggleSelection(item.uri) })
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                EmptyState(if (scan.phase == ScanPhase.COMPLETE) "확인된 중복 파일이 없습니다" else "중복 사본을 찾아보세요", if (scan.phase == ScanPhase.COMPLETE) "접근이 허용된 미디어에서 검사한 결과입니다." else "검사를 시작하면 같은 내용의 파일이 묶여 표시됩니다.", Icons.Rounded.CopyAll)
            }
        }
        SelectionBar(selected, vm::clearSelection, { trash(selected) })
    }
    val detail = detailUri?.let(liveItems::get)
    if (detail != null) MediaDetailDialog(detail, { detailUri = null }, open, { vm.setProtected(detail, it) }, detail.hasFolderProtection(state.protectedFolders))
}

private fun scanTitle(scan: ScanState): String = when (scan.phase) {
    ScanPhase.IDLE -> "아직 검사하지 않았습니다"
    ScanPhase.QUEUED -> "검사를 준비하고 있습니다"
    ScanPhase.RUNNING -> "중복 파일을 검사하고 있습니다"
    ScanPhase.PAUSED -> "검사를 일시정지했습니다"
    ScanPhase.COMPLETE -> "검사를 마쳤습니다"
    ScanPhase.FAILED -> "검사를 완료하지 못했습니다"
}
