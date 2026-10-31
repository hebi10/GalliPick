package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*
import java.util.Calendar

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
    // Keep the scroll position when a refresh temporarily replaces the grid with a loading state.
    val gridState = rememberLazyGridState()
    val oneYearAgo = remember { Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis }
    val quickFilters = remember(oneYearAgo) {
        listOf(
            "10분 이상 영상" to MediaFilter(kind = MediaKind.VIDEO, minDurationMs = 10 * 60 * 1000L),
            "1 GiB 이상" to MediaFilter(minSizeBytes = 1_073_741_824L, sortField = SortField.SIZE),
            "1년 이전 영상" to MediaFilter(kind = MediaKind.VIDEO, toDateMs = oneYearAgo),
        )
    }
    val catalogVisible = !state.loading && state.catalogError == null && state.access != MediaAccess.NONE
    val visibleItems = if (catalogVisible) state.items else emptyList()
    val filtered = if (catalogVisible) state.filtered else emptyList()
    val detail = visibleItems.find { it.uri == detailUri }
    val selected = filtered.filter { it.uri in state.selected && it.canTrash() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("파일", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextAction("필터 저장", { showSave = true })
            IconAction("필터와 정렬", Icons.Rounded.Tune, onClick = { showFilter = true })
        }
        Spacer(Modifier.height(12.dp))
        SearchField(state.filter.query, { actions.setFilter(state.filter.copy(query = it)) })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KindChip("전체", state.filter.kind == null) { actions.setFilter(state.filter.copy(kind = null, minDurationMs = null, maxDurationMs = null)) }
            KindChip("사진", state.filter.kind == MediaKind.PHOTO) { actions.setFilter(state.filter.copy(kind = MediaKind.PHOTO, minDurationMs = null, maxDurationMs = null)) }
            KindChip("동영상", state.filter.kind == MediaKind.VIDEO) { actions.setFilter(state.filter.copy(kind = MediaKind.VIDEO)) }
            Spacer(Modifier.weight(1f))
            TextAction("초기화", { actions.setFilter(MediaFilter()) })
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(quickFilters, key = { it.first }) { (label, filter) ->
                QuickFilterChip(label, state.filter == filter) { actions.setFilter(filter) }
            }
        }
        if (state.presets.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.presets, key = { it.id }) { preset ->
                    Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(24.dp)), verticalAlignment = Alignment.CenterVertically) {
                        TextAction(preset.name, { actions.setFilter(preset.filter) })
                        IconAction("${preset.name} 필터 삭제", Icons.Rounded.Close, Modifier.size(40.dp), onClick = { actions.deletePreset(preset.id) })
                    }
                }
            }
        }
        val activeSummary = filterSummary(state.filter)
        if (activeSummary.isNotEmpty()) Text(activeSummary, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size}개 · ${bytesLabel(filtered.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (filtered.any { it.canTrash() }) TextAction("모두 선택", actions.selectAll)
        }
        if (state.access == MediaAccess.NONE) {
            Box(Modifier.weight(1f)) { EmptyState("미디어 접근이 필요합니다", "허용한 파일만 이곳에 표시됩니다.", action = { ActionButton("접근 허용", requestAccess, filled = true) }) }
        } else if (state.catalogError != null) {
            Box(Modifier.weight(1f)) { EmptyState("파일을 불러오지 못했습니다", state.catalogError, Icons.Rounded.Info, action = { ActionButton("다시 시도", actions.refresh, filled = true) }) }
        } else if (state.loading) {
            Box(Modifier.weight(1f)) { EmptyState("미디어를 불러오고 있습니다", "현재 접근 권한으로 파일을 확인하고 있습니다.") }
        } else if (filtered.isEmpty()) {
            Box(Modifier.weight(1f)) { EmptyState("조건에 맞는 파일이 없습니다", "접근 범위와 필터 조건을 확인해주세요.", action = { ActionButton("필터 초기화", { actions.setFilter(MediaFilter()) }) }) }
        } else {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LazyVerticalGrid(columns = GridCells.Adaptive(if (wide) 120.dp else 132.dp), state = gridState, modifier = Modifier.weight(1f).semantics { contentDescription = "미디어 파일 목록" }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it.uri }) { item -> MediaTile(item, item.uri in state.selected, { actions.toggleSelection(item.uri) }, { detailUri = item.uri }) }
                }
                if (wide) {
                    Box(Modifier.width(280.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(20.dp))) {
                        if (detail != null) MediaDetail(detail, open, { actions.setProtected(detail, it) }, folderProtected = detail.hasFolderProtection(state.protectedFolders))
                        else EmptyState("파일 정보", "파일을 눌러 원본과 상세 정보를 확인하세요.", Icons.Rounded.Info)
                    }
                }
            }
        }
        SelectionBar(selected, actions.clearSelection, { trash(selected) })
    }
    if (showFilter) FilterDialog(state.filter, visibleItems, { actions.setFilter(it); showFilter = false }, { showFilter = false })
    if (showSave) SavePresetDialog({ actions.savePreset(it); showSave = false }, { showSave = false })
    if (!wide && detail != null) MediaDetailDialog(detail, { detailUri = null }, open, { actions.setProtected(detail, it) }, detail.hasFolderProtection(state.protectedFolders))
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(16.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        GalliIcon(Icons.Rounded.Search, null, Modifier.size(21.dp), tint = Muted)
        Spacer(Modifier.width(10.dp))
        BasicTextField(value, onChange, modifier = Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = "파일 이름 또는 폴더 검색" }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 16.sp), decorationBox = { field ->
            Box { if (value.isEmpty()) Text("파일 이름 또는 폴더 검색", color = Muted, fontSize = 16.sp); field() }
        })
        if (value.isNotEmpty()) IconAction("검색어 지우기", Icons.Rounded.Close, Modifier.size(36.dp), onClick = { onChange("") })
    }
}

@Composable
private fun QuickFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, modifier = Modifier.semantics { this.selected = selected; role = Role.Button }.heightIn(min = 44.dp).clip(RoundedCornerShape(24.dp)).background(if (selected) Mint else Panel).border(1.dp, if (selected) Ink else Line, RoundedCornerShape(24.dp)).plainClick(action = onClick).padding(horizontal = 13.dp, vertical = 12.dp), color = Ink, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, modifier = Modifier.semantics { this.selected = selected; role = Role.RadioButton }.heightIn(min = 44.dp).clip(RoundedCornerShape(24.dp)).background(if (selected) Ink else MaterialTheme.colorScheme.surface).border(1.dp, if (selected) Ink else Line, RoundedCornerShape(24.dp)).plainClick(action = onClick).padding(horizontal = 15.dp, vertical = 12.dp), color = if (selected) MaterialTheme.colorScheme.onPrimary else Ink, style = MaterialTheme.typography.labelLarge)
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

private fun filterSummary(filter: MediaFilter): String = buildList {
    if (filter.folder.isNotBlank()) add(filter.folder.trimEnd('/'))
    if (filter.mimeType.isNotBlank()) add(filter.mimeType)
    filter.minSizeBytes?.let { add("${bytesLabel(it)} 이상") }
    filter.maxSizeBytes?.let { add("${bytesLabel(it)} 이하") }
    filter.minDurationMs?.let { add("${durationLabel(it)} 이상") }
    filter.maxDurationMs?.let { add("${durationLabel(it)} 이하") }
    if (filter.fromDateMs != null || filter.toDateMs != null) add("기간 지정")
    filter.minPixels?.let { add("%.1f MP 이상".format(it / 1_000_000.0)) }
    filter.orientation?.let { add(when (it) { Orientation.LANDSCAPE -> "가로"; Orientation.PORTRAIT -> "세로"; Orientation.SQUARE -> "정사각형" }) }
    if (filter.favoritesOnly) add("즐겨찾기")
    if (filter.protectedOnly) add("보호 파일")
}.joinToString(" · ")
