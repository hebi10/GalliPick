package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
    var gridView by rememberSaveable { mutableStateOf(true) }
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
    val groupByDate = state.filter.groupByDate
    val dateGroups = remember(filtered, state.filter.dateField, state.filter.sortField, state.filter.sortDirection, groupByDate) {
        if (!groupByDate) emptyList() else {
            val dateSortField = when (state.filter.dateField) {
                DateField.CAPTURED -> SortField.CAPTURED
                DateField.ADDED -> SortField.ADDED
                DateField.MODIFIED -> SortField.MODIFIED
            }
            val ascending = state.filter.sortField != dateSortField || state.filter.sortDirection == SortDirection.ASCENDING
            MediaDateGroups.group(filtered, state.filter.dateField, ascending)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            ScrollAwayHeaderLayout(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                resetKey = state.filter to (state.catalogError != null || state.access == MediaAccess.NONE),
                header = {
                    FileToolbar(state.filter, state.presets, actions.setFilter,
                        advanced = { showFilter = true }, save = { showSave = true }, deletePreset = actions.deletePreset,
                        gridView = gridView, onGridViewChange = { gridView = it })
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val useSideDetail = wide && maxWidth >= 840.dp && maxHeight >= 420.dp
                    val sideDetail = useSideDetail && detail != null
                    val detailWidth = (maxWidth * .32f).coerceIn(280.dp, 360.dp)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            when {
                                state.access == MediaAccess.NONE -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    EmptyState("미디어 접근이 필요합니다", "허용한 파일만 이곳에 표시됩니다.", action = { ActionButton("접근 허용", requestAccess, filled = true) })
                                }
                                state.catalogError != null -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    EmptyState("파일을 불러오지 못했습니다", state.catalogError, Icons.Outlined.Info, action = { ActionButton("다시 시도", actions.refresh, filled = true) })
                                }
                                state.loading -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    EmptyState("미디어를 불러오고 있습니다", "현재 접근 권한으로 파일을 확인하고 있습니다.")
                                }
                                filtered.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    EmptyState("조건에 맞는 파일이 없습니다", "접근 범위와 필터 조건을 확인해주세요.", action = { ActionButton("필터 초기화", { actions.setFilter(MediaFilter()) }) }, scene = GalliScene.SEARCH)
                                }
                                else -> {
                                    val fontScale = LocalDensity.current.fontScale
                                    if (gridView) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Adaptive(
                                                if (wide) 152.dp else if (fontScale >= 1.5f) 132.dp else 108.dp
                                            ),
                                            state = gridState,
                                            modifier = Modifier.weight(1f).semantics { contentDescription = "미디어 파일 목록" },
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                                        ) {
                                            if (groupByDate) {
                                                dateGroups.forEach { group ->
                                                    item(key = "date:${group.date}", span = { GridItemSpan(maxLineSpan) }) {
                                                        DateGroupHeader(group.date, group.items.size, state.filter.dateField)
                                                    }
                                                    items(group.items, key = { it.uri }) { item ->
                                                        MediaTile(item, item.uri in state.selected,
                                                            { actions.toggleSelection(item.uri) }, { detailUri = item.uri },
                                                            state.filter.sortField)
                                                    }
                                                }
                                            } else {
                                                items(filtered, key = { it.uri }) { item ->
                                                    MediaTile(item, item.uri in state.selected,
                                                        { actions.toggleSelection(item.uri) }, { detailUri = item.uri },
                                                        state.filter.sortField)
                                                }
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.weight(1f).semantics { contentDescription = "미디어 파일 목록" },
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                                        ) {
                                            if (groupByDate) {
                                                dateGroups.forEach { group ->
                                                    item(key = "date:${group.date}") {
                                                        DateGroupHeader(group.date, group.items.size, state.filter.dateField)
                                                    }
                                                    listItems(group.items, key = { it.uri }) { item ->
                                                        MediaTile(item, item.uri in state.selected,
                                                            { actions.toggleSelection(item.uri) }, { detailUri = item.uri },
                                                            state.filter.sortField, listView = true,
                                                            dateMs = MediaQuery.dateOf(item, state.filter.dateField))
                                                    }
                                                }
                                            } else {
                                                listItems(filtered, key = { it.uri }) { item ->
                                                    MediaTile(item, item.uri in state.selected,
                                                        { actions.toggleSelection(item.uri) }, { detailUri = item.uri },
                                                        state.filter.sortField, listView = true)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (sideDetail && detail != null) {
                            Column(Modifier.width(detailWidth).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(20.dp))
                                .semantics { contentDescription = "파일 상세 패널" }) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("파일 정보", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    IconAction("파일 상세 닫기", Icons.Outlined.Close) { detailUri = null }
                                }
                                MediaDetail(detail, open, { actions.setProtected(detail, it) }, Modifier.weight(1f), detail.hasFolderProtection(state.protectedFolders))
                            }
                        }
                    }
                    if (!useSideDetail && detail != null) MediaDetailDialog(detail, { detailUri = null }, open, { actions.setProtected(detail, it) }, detail.hasFolderProtection(state.protectedFolders))
                }
            }

            if (catalogVisible && filtered.isNotEmpty() && selected.isEmpty()) {
                FilesFooterSummary(filtered, actions.selectAll)
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
private fun DateGroupHeader(date: String, count: Int, field: DateField) {
    val basis = when (field) {
        DateField.CAPTURED -> "촬영일"
        DateField.ADDED -> "추가일"
        DateField.MODIFIED -> "수정일"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$basis $date", modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = Ink)
        Text("${count}개", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun FilesFooterSummary(items: List<MediaItem>, selectAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "총 " + String.format(java.util.Locale.KOREA, "%,d", items.size) +
                "개 · " + bytesLabel(items.sumOf { it.sizeBytes }),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.semantics { contentDescription = "파일 목록 요약" },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (items.any { it.canTrash() }) TextAction("모두 선택", selectAll)
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    toggle: () -> Unit,
    detail: () -> Unit,
    sortField: SortField,
    listView: Boolean = false,
    dateMs: Long = item.capturedAt,
) {
    if (listView) {
        Row(
            Modifier.fillMaxWidth().galliCardSurface().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)).plainClick(action = detail)) {
                MediaThumbnail(item, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).plainClick(action = detail), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(bytesLabel(item.sizeBytes) + " · " + dateLabel(dateMs).substringBefore(" "),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (item.kind == MediaKind.VIDEO) {
                    Text(durationLabel(item.durationMs), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
            SelectBox(
                selected, item.canTrash(),
                if (item.canTrash()) item.displayName + " 선택" else item.displayName + " 즐겨찾기 또는 보호 파일",
                toggle,
            )
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .border(if (selected) 2.dp else 0.dp,
                        if (selected) Primary else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(10.dp))
                    .plainClick(action = detail),
            ) {
                MediaThumbnail(item, Modifier.fillMaxSize())
                SelectBox(
                    selected, item.canTrash(),
                    if (item.canTrash()) item.displayName + " 선택" else item.displayName + " 즐겨찾기 또는 보호 파일",
                    toggle, Modifier.align(Alignment.TopStart), indicatorAtTopStart = true,
                )
                if (item.kind == MediaKind.VIDEO) {
                    Text(durationLabel(item.durationMs), color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(Ink, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (item.isFavorite || item.isProtected) {
                    GalliIcon(
                        if (item.isFavorite) Icons.Outlined.Star else Icons.Outlined.Shield,
                        if (item.isFavorite) "즐겨찾기" else "보호됨",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(Ink, RoundedCornerShape(8.dp)).padding(4.dp).size(16.dp),
                    )
                }
            }
            Text(item.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().plainClick(action = detail))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SelectionBar(selected: List<MediaItem>, clear: () -> Unit, trash: () -> Unit) {
    if (selected.isEmpty()) return
    Column(Modifier.fillMaxWidth().galliCardSurface(backgroundColor = Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).widthIn(min = 96.dp).align(Alignment.CenterVertically)) {
                Text("${selected.size}개 선택", style = MaterialTheme.typography.titleSmall)
                Text(bytesLabel(selected.sumOf { it.sizeBytes }), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            TextAction("해제", clear, Modifier.align(Alignment.CenterVertically))
            ActionButton("휴지통으로", trash, Modifier.align(Alignment.CenterVertically), filled = true, icon = Icons.Outlined.DeleteOutline)
        }
        Text("즐겨찾기·보호 파일은 선택할 수 없습니다. 이동 전 Android 확인창이 표시됩니다.", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun SavePresetDialog(save: (String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(20.dp), containerColor = AppSurface,
        titleContentColor = Ink, textContentColor = Muted, title = { Text("현재 필터 저장") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("검색어, 조건, 정렬을 함께 저장합니다.")
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("필터 이름") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
    }, confirmButton = { ActionButton("저장", { save(name.trim()) }, enabled = name.isNotBlank(), filled = true) }, dismissButton = { TextAction("취소", dismiss) })
}
