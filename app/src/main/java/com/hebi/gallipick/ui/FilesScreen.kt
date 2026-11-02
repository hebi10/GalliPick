package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*

internal data class FilesScreenActions(
    val setFilter: (MediaFilter) -> Unit,
    val selectAll: () -> Unit,
    val toggleSelection: (String) -> Unit,
    val clearSelection: () -> Unit,
    val savePreset: (String) -> Unit,
    val deletePreset: (String) -> Unit,
    val setProtected: (MediaItem, Boolean) -> Unit,
    val refresh: () -> Unit,
)

@Composable
internal fun FilesScreen(state: AppUiState, vm: AppViewModel, wide: Boolean, trash: (List<MediaItem>) -> Unit, open: (MediaItem) -> Unit, requestAccess: () -> Unit) {
    FilesScreenContent(state, wide, trash, open, requestAccess, FilesScreenActions(
        vm::setFilter, vm::selectAllFiltered, vm::toggleSelection, vm::clearSelection,
        vm::savePreset, vm::deletePreset, { item, protected -> vm.setProtected(item, protected) }, vm::refresh,
    ))
}

@Composable
internal fun FilesScreenContent(
    state: AppUiState,
    wide: Boolean,
    trash: (List<MediaItem>) -> Unit,
    open: (MediaItem) -> Unit,
    requestAccess: () -> Unit,
    actions: FilesScreenActions,
) {
    var showFilter by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var detailUri by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    var previousFilter by remember { mutableStateOf(state.filter) }
    LaunchedEffect(state.filter) {
        if (previousFilter != state.filter) {
            previousFilter = state.filter
            gridState.scrollToItem(0)
        }
    }
    val catalogVisible = !state.loading && state.catalogError == null && state.access != MediaAccess.NONE
    val visibleItems = if (catalogVisible) state.items else emptyList()
    val filtered = if (catalogVisible) state.filtered else emptyList()
    val detail = visibleItems.find { it.uri == detailUri }
    val selected = filtered.filter { it.uri in state.selected && it.canTrash() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            ScrollAwayHeaderLayout(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                resetKey = state.filter to (state.catalogError != null || state.access == MediaAccess.NONE),
                header = {
                    FileToolbar(state.filter, state.presets, actions.setFilter,
                        advanced = { showFilter = true }, save = { showSave = true }, deletePreset = actions.deletePreset)
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val summary = "${filtered.size}개 · ${bytesLabel(filtered.sumOf { it.sizeBytes })}" +
                            if (state.filter.query.isNotBlank()) " · 검색: ${state.filter.query}" else ""
                        Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (filtered.any { it.canTrash() }) TextAction("모두 선택", actions.selectAll)
                        else Spacer(Modifier.height(44.dp))
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val sideDetail = wide && maxWidth >= 840.dp && maxHeight >= 420.dp
                        when {
                            state.access == MediaAccess.NONE -> Box(Modifier.fillMaxSize()) {
                                EmptyState("미디어 접근이 필요합니다", "허용한 파일만 이곳에 표시됩니다.", action = { ActionButton("접근 허용", requestAccess, filled = true) })
                            }
                            state.catalogError != null -> Box(Modifier.fillMaxSize()) {
                                EmptyState("파일을 불러오지 못했습니다", state.catalogError, Icons.Rounded.Info, action = { ActionButton("다시 시도", actions.refresh, filled = true) })
                            }
                            state.loading -> Box(Modifier.fillMaxSize()) {
                                EmptyState("미디어를 불러오고 있습니다", "현재 접근 권한으로 파일을 확인하고 있습니다.")
                            }
                            filtered.isEmpty() -> Box(Modifier.fillMaxSize()) {
                                EmptyState("조건에 맞는 파일이 없습니다", "접근 범위와 필터 조건을 확인해주세요.", action = { ActionButton("필터 초기화", { actions.setFilter(MediaFilter()) }) })
                            }
                            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(if (wide) 120.dp else 132.dp), state = gridState,
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "미디어 파일 목록" },
                                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                ) {
                                    items(filtered, key = { it.uri }) { item ->
                                        MediaTile(item, item.uri in state.selected, { actions.toggleSelection(item.uri) }, { detailUri = item.uri })
                                    }
                                }
                                if (sideDetail) {
                                    Box(Modifier.width(300.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                        .background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(20.dp))) {
                                        if (detail != null) MediaDetail(detail, open, { actions.setProtected(detail, it) }, folderProtected = detail.hasFolderProtection(state.protectedFolders))
                                        else EmptyState("파일 정보", "파일을 눌러 상세 정보를 확인하세요.", Icons.Rounded.Info)
                                    }
                                }
                            }
                        }
                        if (!sideDetail && detail != null) MediaDetailDialog(detail, { detailUri = null }, open, { actions.setProtected(detail, it) }, detail.hasFolderProtection(state.protectedFolders))
                    }
                }
            }
            SelectionBar(selected, actions.clearSelection, { trash(selected) })
        }
        if (showFilter) FilterDialog(state.filter, visibleItems, {
            actions.setFilter(MediaFilterActions.sort(it, it.sortField, it.sortDirection)); showFilter = false
        }, { showFilter = false })
        if (showSave) SavePresetDialog({ actions.savePreset(it); showSave = false }, { showSave = false })
    }
}

@Composable
private fun MediaTile(item: MediaItem, selected: Boolean, toggle: () -> Unit, detail: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).plainClick(action = detail)) {
            MediaThumbnail(item, Modifier.fillMaxSize())
            SelectBox(selected, item.canTrash(), if (item.canTrash()) "${item.displayName} 선택" else "${item.displayName} 즐겨찾기 또는 보호 파일", toggle, Modifier.align(Alignment.TopEnd))
            if (item.kind == MediaKind.VIDEO) Text(durationLabel(item.durationMs), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Ink, RoundedCornerShape(12.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
            if (item.isFavorite || item.isProtected) GalliIcon(if (item.isFavorite) Icons.Rounded.Star else Icons.Rounded.Shield, if (item.isFavorite) "즐겨찾기" else "보호됨", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).background(Ink, RoundedCornerShape(12.dp)).padding(4.dp).size(16.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(item.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.plainClick(action = detail))
        Text(bytesLabel(item.sizeBytes), color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SelectionBar(selected: List<MediaItem>, clear: () -> Unit, trash: () -> Unit) {
    if (selected.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${selected.size}개 선택", style = MaterialTheme.typography.titleSmall)
                Text(bytesLabel(selected.sumOf { it.sizeBytes }), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            TextAction("해제", clear)
            ActionButton("휴지통으로", trash, filled = true, icon = Icons.Rounded.DeleteOutline)
        }
        Text("즐겨찾기·보호 파일은 선택할 수 없습니다. 이동 전 Android 확인창이 표시됩니다.", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SavePresetDialog(save: (String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(24.dp), title = { Text("현재 필터 저장") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("검색어, 조건, 정렬을 함께 저장합니다.")
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("필터 이름") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
    }, confirmButton = { ActionButton("저장", { save(name.trim()) }, enabled = name.isNotBlank(), filled = true) }, dismissButton = { TextAction("취소", dismiss) })
}
