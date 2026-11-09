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
import androidx.compose.ui.platform.testTag
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
    val startOcr: () -> Unit = {},
    val pauseOcr: () -> Unit = {},
    val resumeOcr: () -> Unit = {},
)

@Composable
internal fun FilesScreen(state: AppUiState, vm: AppViewModel, wide: Boolean, trash: (List<MediaItem>) -> Unit, open: (MediaItem) -> Unit, requestAccess: () -> Unit) {
    FilesScreenContent(state, wide, trash, open, requestAccess, FilesScreenActions(
        vm::setFilter, vm::selectAllFiltered, vm::toggleSelection, vm::clearSelection,
        vm::savePreset, vm::deletePreset, { item, protected -> vm.setProtected(item, protected) },
        vm::refresh, vm::startOcrIndex, vm::pauseOcrIndex, vm::resumeOcrIndex,
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
    var searchMode by rememberSaveable { mutableStateOf(FileSearchMode.FILES) }
    var ocrQuery by rememberSaveable { mutableStateOf("") }
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
    val ocrRows = remember(visibleItems, state.ocrEntries) {
        PhotoOcrIndexPolicy.valid(visibleItems, state.ocrEntries)
    }
    val filtered = if (!catalogVisible) emptyList() else if (searchMode == FileSearchMode.OCR) {
        val base = MediaQuery.apply(visibleItems,
            state.filter.copy(query = "", kind = MediaKind.PHOTO))
        PhotoOcrIndexPolicy.searchable(base, state.ocrEntries, ocrQuery)
    } else state.filtered
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
                resetKey = Triple(state.filter, searchMode, ocrQuery) to
                    (state.catalogError != null || state.access == MediaAccess.NONE),
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FileToolbar(state.filter, state.presets, actions.setFilter,
                            advanced = { showFilter = true }, save = { showSave = true },
                            deletePreset = actions.deletePreset, gridView = gridView,
                            onGridViewChange = { gridView = it }, searchMode = searchMode,
                            ocrQuery = ocrQuery,
                            onSearchModeChange = { searchMode = it; detailUri = null },
                            onOcrQueryChange = { ocrQuery = it },
                        )
                    }
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val screenWidth = maxWidth
                    val screenFontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val ocrSidebar = searchMode == FileSearchMode.OCR && wide &&
                        screenWidth >= 900.dp * screenFontScale
                    val sidebarWidth = (screenWidth * .28f).coerceIn(240.dp, 320.dp)
                    val useSideDetail = !ocrSidebar && wide && screenWidth >= 840.dp && maxHeight >= 420.dp
                    val sideDetail = useSideDetail && detail != null
                    val detailWidth = (screenWidth * .32f).coerceIn(280.dp, 360.dp)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (ocrSidebar) {
                            Box(Modifier.width(sidebarWidth).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                PhotoOcrIndexPanel(state, actions.startOcr, actions.pauseOcr,
                                    actions.resumeOcr, requestAccess)
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (searchMode == FileSearchMode.OCR && !ocrSidebar) {
                                val panelHeight = minOf(
                                    240.dp,
                                    maxOf(84.dp, maxHeight * .42f),
                                    maxHeight * .60f,
                                )
                                Box(
                                    Modifier.fillMaxWidth().height(panelHeight)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    PhotoOcrIndexPanel(
                                        state, actions.startOcr, actions.pauseOcr,
                                        actions.resumeOcr, requestAccess,
                                    )
                                }
                            }
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
                                searchMode == FileSearchMode.OCR && ocrQuery.isBlank() ->
                                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        EmptyState("검색어를 입력해 주세요",
                                            "사진 속 글자 모드입니다. 분석을 마친 사진에서 한국어·영문 단어를 검색합니다.",
                                            scene = GalliScene.SEARCH)
                                    }
                                filtered.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    if (searchMode == FileSearchMode.OCR) {
                                        EmptyState("일치하는 사진 속 글자가 없습니다",
                                            "현재 접근 가능하고 글자 분석을 마친 사진만 검색했습니다. 미분석·권한 제외·인식 실패 사진은 결과에 포함되지 않습니다.",
                                            scene = GalliScene.SEARCH)
                                    } else {
                                        EmptyState("조건에 맞는 파일이 없습니다",
                                            "접근 범위와 필터 조건을 확인해주세요.",
                                            action = { ActionButton("필터 초기화", { actions.setFilter(MediaFilter()) }) },
                                            scene = GalliScene.SEARCH)
                                    }
                                }
                                else -> {
                                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                                    val tabletGrid = wide && screenWidth >= 720.dp
                                    val availableGridWidth = if (sideDetail)
                                        screenWidth - detailWidth - 14.dp
                                    else if (ocrSidebar) screenWidth - sidebarWidth - 14.dp
                                    else screenWidth
                                    val tabletColumns = tabletMediaGridColumns(availableGridWidth, fontScale)
                                    if (gridView) {
                                        LazyVerticalGrid(
                                            columns = if (tabletGrid) GridCells.Fixed(tabletColumns)
                                                else GridCells.Adaptive(if (fontScale >= 1.5f) 132.dp else 108.dp),
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
                                                            state.filter.sortField, tabletGrid = tabletGrid,
                                                            ocrText = if (searchMode == FileSearchMode.OCR)
                                                                ocrRows[item.uri]?.recognizedText else null,
                                                            ocrQuery = ocrQuery)
                                                    }
                                                }
                                            } else {
                                                items(filtered, key = { it.uri }) { item ->
                                                    MediaTile(item, item.uri in state.selected,
                                                        { actions.toggleSelection(item.uri) }, { detailUri = item.uri },
                                                        state.filter.sortField, tabletGrid = tabletGrid,
                                                            ocrText = if (searchMode == FileSearchMode.OCR)
                                                                ocrRows[item.uri]?.recognizedText else null,
                                                            ocrQuery = ocrQuery)
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
                                                            dateMs = MediaQuery.dateOf(item, state.filter.dateField),
                                                            ocrText = if (searchMode == FileSearchMode.OCR)
                                                                ocrRows[item.uri]?.recognizedText else null,
                                                            ocrQuery = ocrQuery)
                                                    }
                                                }
                                            } else {
                                                listItems(filtered, key = { it.uri }) { item ->
                                                    MediaTile(item, item.uri in state.selected,
                                                        { actions.toggleSelection(item.uri) }, { detailUri = item.uri },
                                                        state.filter.sortField, listView = true,
                                                        ocrText = if (searchMode == FileSearchMode.OCR)
                                                            ocrRows[item.uri]?.recognizedText else null,
                                                        ocrQuery = ocrQuery)
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
                                MediaDetail(detail, open, { actions.setProtected(detail, it) },
                                    Modifier.weight(1f), detail.hasFolderProtection(state.protectedFolders),
                                    ocrText = if (searchMode == FileSearchMode.OCR)
                                        ocrRows[detail.uri]?.recognizedText else null,
                                    ocrQuery = ocrQuery)
                            }
                        }
                    }
                    if (!useSideDetail && detail != null) MediaDetailDialog(
                        detail, { detailUri = null }, open,
                        { actions.setProtected(detail, it) }, detail.hasFolderProtection(state.protectedFolders),
                        ocrText = if (searchMode == FileSearchMode.OCR)
                            ocrRows[detail.uri]?.recognizedText else null,
                        ocrQuery = ocrQuery,
                    )
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

internal fun tabletMediaGridColumns(availableWidth: androidx.compose.ui.unit.Dp, fontScale: Float): Int {
    val minimumTileWidth = 212f * fontScale.coerceIn(1f, 1.5f)
    return (availableWidth.value / minimumTileWidth).toInt().coerceIn(1, 5)
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
    tabletGrid: Boolean = false,
    ocrText: String? = null,
    ocrQuery: String = "",
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
                if (ocrText != null) {
                    Text("인식된 글자: " + PhotoOcrIndexPolicy.snippet(ocrText, ocrQuery),
                        style = MaterialTheme.typography.labelSmall, color = Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
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
        Column(Modifier.fillMaxWidth().then(
            if (tabletGrid) Modifier.testTag("tablet-media-${item.id}") else Modifier,
        ), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
            Text(item.displayName, style = if (tabletGrid) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.labelSmall, maxLines = if (tabletGrid) 2 else 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().plainClick(action = detail))
            if (tabletGrid) {
                Text(bytesLabel(item.sizeBytes), style = MaterialTheme.typography.labelSmall,
                    color = Muted, maxLines = 1)
            }
            if (ocrText != null) {
                Text("인식된 글자: " + PhotoOcrIndexPolicy.snippet(ocrText, ocrQuery, 72),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
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
