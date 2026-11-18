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
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.CloudGalleryPerformancePolicy
import com.hebi.gallipick.data.cloud.GalleryFileQuery
import com.hebi.gallipick.data.cloud.GallerySourceFilter
import com.hebi.gallipick.data.cloud.combineGalleryCatalog
import com.hebi.gallipick.data.cloud.cloudGalleryPhotos

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
internal fun FilesScreen(
    state: AppUiState, vm: AppViewModel, wide: Boolean,
    trash: (List<MediaItem>) -> Unit, open: (MediaItem) -> Unit, requestAccess: () -> Unit,
    cloudFiles: CloudFilesUiState = CloudFilesUiState(), openCloud: () -> Unit = {},
) {
    FilesScreenContent(state, wide, trash, open, requestAccess, FilesScreenActions(
        vm::setFilter, vm::selectAllFiltered, vm::toggleSelection, vm::clearSelection,
        vm::savePreset, vm::deletePreset, { item, protected -> vm.setProtected(item, protected) },
        vm::refresh, vm::startOcrIndex, vm::pauseOcrIndex, vm::resumeOcrIndex,
    ), cloudFiles = cloudFiles, openCloud = openCloud)
}

@Composable
internal fun FilesScreenContent(
    state: AppUiState,
    wide: Boolean,
    trash: (List<MediaItem>) -> Unit,
    open: (MediaItem) -> Unit,
    requestAccess: () -> Unit,
    actions: FilesScreenActions,
    cloudFiles: CloudFilesUiState = CloudFilesUiState(),
    openCloud: () -> Unit = {},
) {
    var showFilter by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var gridView by rememberSaveable { mutableStateOf(true) }
    var searchMode by rememberSaveable { mutableStateOf(FileSearchMode.FILES) }
    var ocrQuery by rememberSaveable { mutableStateOf("") }
    var detailUri by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceFilter by rememberSaveable { mutableStateOf(GallerySourceFilter.ALL) }
    var visiblePages by rememberSaveable { mutableIntStateOf(1) }
    var allowMeteredThumbnails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.filter, sourceFilter, cloudFiles.accountUid) { visiblePages = 1 }
    var chosenCloud by remember(cloudFiles.accountUid) { mutableStateOf<CloudOriginal?>(null) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(sourceFilter) { gridState.scrollToItem(0) }
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
        val base = MediaQuery.apply(visibleItems, state.filter.copy(query = ""))
        PhotoOcrIndexPolicy.searchable(base, state.ocrEntries, ocrQuery)
    } else state.filtered
    val detail = visibleItems.find { it.uri == detailUri }
    val useUnified = cloudFiles.enabled && searchMode == FileSearchMode.FILES &&
        (sourceFilter == GallerySourceFilter.CLOUD ||
            (sourceFilter == GallerySourceFilter.ALL && cloudFiles.accountUid != null))
    val galleryItems = remember(
        visibleItems, cloudFiles.snapshot, cloudFiles.accountUid, cloudFiles.verifiedMatches,
    ) {
        combineGalleryCatalog(
            visibleItems, cloudFiles.snapshot, cloudFiles.accountUid, cloudFiles.verifiedMatches,
        )
    }
    val galleryFiltered = remember(galleryItems, state.filter, sourceFilter) {
        GalleryFileQuery.apply(galleryItems, state.filter, sourceFilter)
    }
    val visibleGallery = remember(galleryFiltered, visiblePages) {
        galleryFiltered.take(CloudGalleryPerformancePolicy.visibleCount(galleryFiltered.size, visiblePages))
    }
    val autoLoadThumbnails = CloudGalleryPerformancePolicy.canLoadThumbnails(
        cloudFiles.online, cloudFiles.unmetered, allowMeteredThumbnails,
    )
    // CLOUD-only is strictly read-only even if another tab left a device selection behind.
    val selected = if (useUnified && sourceFilter == GallerySourceFilter.CLOUD) emptyList()
        else if (useUnified) galleryFiltered.mapNotNull { it.device }
            .filter { it.uri in state.selected && it.canTrash() }
        else filtered.filter { it.uri in state.selected && it.canTrash() }
    val selectedUris = selected.mapTo(hashSetOf()) { it.uri }
    val verifiedSelected = if (useUnified) galleryFiltered.count { entry ->
        entry.source == com.hebi.gallipick.data.cloud.GallerySource.BOTH &&
            (entry.device?.uri?.let { it in selectedUris } == true)
    } else 0
    val fileSections = remember(filtered, state.filter) {
        FileSections.local(filtered, state.filter)
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            ScrollAwayHeaderLayout(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                resetKey = Triple(state.filter, searchMode, ocrQuery) to
                    Triple(state.catalogError != null || state.access == MediaAccess.NONE,
                        sourceFilter, cloudFiles.accountUid),
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FileToolbar(state.filter, state.presets, actions.setFilter,
                            advanced = { showFilter = true }, save = { showSave = true },
                            deletePreset = actions.deletePreset, gridView = gridView,
                            onGridViewChange = { gridView = it }, searchMode = searchMode,
                            ocrQuery = ocrQuery,
                            onSearchModeChange = { searchMode = it; detailUri = null },
                            onOcrQueryChange = { ocrQuery = it },
                            sourceFilter = sourceFilter, showSourceFilter = cloudFiles.enabled,
                            onSourceFilterChange = { option ->
                                if (sourceFilter != option) {
                                    sourceFilter = option
                                    detailUri = null
                                    chosenCloud = null
                                    if (option == GallerySourceFilter.CLOUD) actions.clearSelection()
                                }
                            },
                        )
                        if (cloudFiles.enabled && searchMode == FileSearchMode.FILES) {
                            if (cloudFiles.accountUid != null && sourceFilter != GallerySourceFilter.DEVICE &&
                                cloudFiles.matchTotal > 0) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    val progress = when {
                                        cloudFiles.matchChecking -> "기기·클라우드 원본 대조 중"
                                        cloudFiles.matchFailed -> "원본 비교 중 문제가 발생했습니다"
                                        else -> "백업 원본 비교"
                                    }
                                    Text("$progress · ${cloudFiles.matchChecked}/${cloudFiles.matchTotal}",
                                        Modifier.weight(1f),
                                        color = Muted, style = MaterialTheme.typography.bodySmall)
                                    if (!cloudFiles.matchChecking && cloudFiles.matchChecked < cloudFiles.matchTotal) {
                                        TextAction(
                                            if (cloudFiles.matchFailed) "다시 비교" else "다음 비교",
                                            cloudFiles.matchNext,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val screenWidth = maxWidth
                    val availableBodyHeight = maxHeight
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
                                    maxOf(84.dp, availableBodyHeight * .42f),
                                    availableBodyHeight * .60f,
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
                                useUnified -> {
                                    when {
                                        sourceFilter == GallerySourceFilter.CLOUD && cloudFiles.accountUid == null ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 로그인이 필요합니다",
                                                    "기기 파일은 로그인 없이 계속 사용할 수 있습니다.",
                                                    action = { ActionButton("클라우드에서 로그인", openCloud, filled = true) })
                                            }
                                        galleryFiltered.isEmpty() && !cloudFiles.online && cloudFiles.accountUid != null ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("인터넷 연결이 필요합니다",
                                                    "이번 앱 사용 중 불러온 클라우드 목록이 없습니다. 기기 파일은 계속 이용할 수 있습니다.")
                                            }
                                        galleryFiltered.isEmpty() && cloudFiles.loading ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 파일을 불러오는 중입니다",
                                                    "기기 파일은 별도로 확인할 수 있습니다.")
                                            }
                                        galleryFiltered.isEmpty() && cloudFiles.failed ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 목록을 불러오지 못했습니다",
                                                    "네트워크와 Google 계정을 확인해 주세요.",
                                                    action = { ActionButton("클라우드 다시 시도", cloudFiles.retry, filled = true) })
                                            }
                                        galleryFiltered.isEmpty() ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("조건에 맞는 파일이 없습니다",
                                                    "클라우드와 기기 저장 위치 또는 필터를 확인해 주세요.",
                                                    action = { ActionButton("필터 초기화", { actions.setFilter(MediaFilter()) }) },
                                                    scene = GalliScene.SEARCH)
                                            }
                                        else -> {
                                            val tabletGrid = wide && screenWidth >= 720.dp
                                            val availableGridWidth = if (sideDetail)
                                                screenWidth - detailWidth - 14.dp
                                            else if (ocrSidebar) screenWidth - sidebarWidth - 14.dp
                                            else screenWidth
                                            Column(Modifier.weight(1f).fillMaxWidth()) {
                                                if (cloudFiles.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                                if (!cloudFiles.online) {
                                                    Text(
                                                        if (cloudFiles.cachedCatalog)
                                                            "오프라인 · 이전에 불러온 파일 정보만 표시합니다. 클라우드 원본 감상에는 인터넷 연결이 필요합니다."
                                                        else "오프라인 · 현재 접근 가능한 기기 파일만 표시합니다.",
                                                        color = Muted, style = MaterialTheme.typography.bodySmall,
                                                    )
                                                } else if (!cloudFiles.unmetered && !allowMeteredThumbnails &&
                                                    galleryFiltered.any { it.cloud != null }) {
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text("모바일 데이터 사용 중 · 자동 썸네일 요청을 제한했습니다.",
                                                            Modifier.weight(1f), color = Muted,
                                                            style = MaterialTheme.typography.bodySmall)
                                                        TextAction("이번 화면에서 허용", { allowMeteredThumbnails = true })
                                                    }
                                                }
                                                if (cloudFiles.failed && cloudFiles.online) {
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text("클라우드 일부 항목을 확인하지 못했습니다.",
                                                            Modifier.weight(1f), color = Muted,
                                                            style = MaterialTheme.typography.bodySmall)
                                                        TextAction("다시 시도", cloudFiles.retry)
                                                    }
                                                }
                                                if (sourceFilter == GallerySourceFilter.ALL &&
                                                    state.access == MediaAccess.NONE) {
                                                    Text("기기 사진 접근이 제한되어 클라우드 파일만 표시됩니다.",
                                                        color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                UnifiedFilesList(
                                                    entries = visibleGallery, filter = state.filter, source = sourceFilter,
                                                    gridView = gridView, wide = wide, availableWidth = availableGridWidth,
                                                    gridState = gridState, selected = state.selected,
                                                    toggleDevice = actions.toggleSelection, openDevice = { detailUri = it },
                                                    openCloud = { chosenCloud = it },
                                                    backup = cloudFiles.backup, accountUid = cloudFiles.accountUid,
                                                    autoLoadThumbnails = autoLoadThumbnails,
                                                    modifier = Modifier.weight(1f),
                                                    gridColumns = state.fileGridColumns,
                                                )
                                                if (visibleGallery.size < galleryFiltered.size) {
                                                    Row(Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically) {
                                                        Text("표시 ${visibleGallery.size}/${galleryFiltered.size}개",
                                                            style = MaterialTheme.typography.bodySmall, color = Muted)
                                                        TextAction("다음 파일 보기", { visiblePages++ })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
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
                                    if (gridView) {
                                        LazyVerticalGrid(
                                            columns = fileGridCells(state.fileGridColumns, availableGridWidth, fontScale, tabletGrid),
                                            state = gridState,
                                            modifier = Modifier.weight(1f).semantics { contentDescription = "미디어 파일 목록" },
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                                        ) {
                                            if (fileSections.isNotEmpty()) {
                                                fileSections.forEach { group ->
                                                    item(key = "section:${group.title}", span = { GridItemSpan(maxLineSpan) }) {
                                                        FileSectionHeader(group.title, group.items.size, state.filter.dateField.takeIf { group.isDate })
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
                                            if (fileSections.isNotEmpty()) {
                                                fileSections.forEach { group ->
                                                    item(key = "section:${group.title}") {
                                                        FileSectionHeader(group.title, group.items.size, state.filter.dateField.takeIf { group.isDate })
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

            if (useUnified && galleryFiltered.isNotEmpty() && selected.isEmpty()) {
                Text("목록 ${galleryFiltered.size}개 · 기기 파일만 선택하여 정리할 수 있습니다.",
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            } else if (!useUnified && catalogVisible && filtered.isNotEmpty() && selected.isEmpty()) {
                FilesFooterSummary(filtered, actions.selectAll)
            }
            if (verifiedSelected > 0 && selected.isNotEmpty()) {
                Text("선택한 기기 파일 중 ${verifiedSelected}개는 서버의 검증된 백업 기록과 내용이 일치합니다. " +
                    "클라우드 필터에서 원본을 열어 확인한 뒤, Android 확인창에서 기기 사본을 정리해 주세요.",
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            SelectionBar(selected, actions.clearSelection, { trash(selected) })
        }
        if (showFilter) FilterDialog(state.filter, visibleItems, {
            actions.setFilter(MediaFilterActions.sort(it, it.sortField, it.sortDirection)); showFilter = false
        }, { showFilter = false })
        if (showSave) SavePresetDialog({ actions.savePreset(it); showSave = false }, { showSave = false })
        chosenCloud?.let { original ->
            val uid = cloudFiles.accountUid
            val backup = cloudFiles.backup
            if (original.mimeType.startsWith("image/") && uid != null && backup != null) {
                val photos = cloudGalleryPhotos(galleryFiltered, original)
                CloudPhotoViewer(
                    uid = uid, original = original,
                    photos = photos,
                    backup = backup, choose = { chosenCloud = it }, dismiss = { chosenCloud = null },
                    offline = !cloudFiles.online,
                )
            } else if (original.mimeType.startsWith("video/") && uid != null && backup != null) {
                CloudVideoViewer(
                    uid = uid, original = original, backup = backup,
                    dismiss = { chosenCloud = null }, offline = !cloudFiles.online,
                )
            } else AlertDialog(
                onDismissRequest = { chosenCloud = null },
                containerColor = AppSurface,
                title = { Text(original.displayName) },
                text = { Text("클라우드에 보관된 원본입니다. 현재 보관함에서 확인하거나 기기에 복원할 수 있습니다.",
                    color = Muted) },
                confirmButton = {
                    ActionButton("클라우드 보관함 열기", {
                        chosenCloud = null
                        openCloud()
                    }, filled = true)
                },
                dismissButton = { TextAction("닫기", { chosenCloud = null }) },
            )
        }
    }
}

@Composable
internal fun FileSectionHeader(title: String, count: Int, dateField: DateField?) {
    if (dateField != null) {
        DateGroupHeader(title, count, dateField)
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("${count}개", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
internal fun DateGroupHeader(date: String, count: Int, field: DateField) {
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
internal fun MediaTile(
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
    sourceLabel: String? = null,
    allowSelection: Boolean = true,
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
                if (sourceLabel != null) Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = Muted)
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
            if (allowSelection) SelectBox(
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
                if (allowSelection) SelectBox(
                    selected, item.canTrash(),
                    if (item.canTrash()) item.displayName + " 선택" else item.displayName + " 즐겨찾기 또는 보호 파일",
                    toggle, Modifier.align(Alignment.TopStart), indicatorAtTopStart = true,
                )
                if (sourceLabel != null) {
                    Text(sourceLabel, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(AppSurface, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = Ink)
                }
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
