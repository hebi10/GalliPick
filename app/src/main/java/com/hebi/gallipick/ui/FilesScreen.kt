package com.hebi.gallipick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppSettings
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
    val selectAll: (List<MediaItem>) -> Unit,
    val toggleSelection: (String) -> Unit,
    val clearSelection: () -> Unit,
    val savePreset: (String) -> Unit,
    val deletePreset: (String) -> Unit,
    val setProtected: (MediaItem, Boolean) -> Unit,
    val refresh: () -> Unit,
    val startOcr: () -> Unit = {},
    val pauseOcr: () -> Unit = {},
    val resumeOcr: () -> Unit = {},
    val setFileListView: (Boolean) -> Unit = {},
    val addSelected: (List<MediaItem>) -> Unit = { items -> items.forEach { toggleSelection(it.uri) } },
)

@Composable
internal fun FilesScreen(
    state: AppUiState, vm: AppViewModel, wide: Boolean,
    trash: (List<MediaItem>) -> Unit, open: (MediaItem) -> Unit, requestAccess: () -> Unit,
    cloudFiles: CloudFilesUiState = CloudFilesUiState(), openCloud: () -> Unit = {},
    resumeDetailVersion: Triple<String, String, String>? = null,
    onDetailResumed: () -> Unit = {},
    onCloudSignInFromDetail: (MediaItem) -> Unit = {},
    showSearch: Boolean = false,
    onOpenPermissionSettings: (() -> Unit)? = null,
) {
    FilesScreenContent(state, wide, trash, open, requestAccess, FilesScreenActions(
        vm::setFilter, vm::selectVisibleFiles, vm::toggleSelection, vm::clearSelection,
        vm::savePreset, vm::deletePreset, { item, protected -> vm.setProtected(item, protected) },
        vm::refresh, vm::startOcrIndex, vm::pauseOcrIndex, vm::resumeOcrIndex,
        setFileListView = vm::setFileListView,
        addSelected = vm::addFileSelection,
    ), cloudFiles = cloudFiles, openCloud = openCloud,
        resumeDetailVersion = resumeDetailVersion, onDetailResumed = onDetailResumed,
        onCloudSignInFromDetail = onCloudSignInFromDetail, showSearch = showSearch,
        onOpenPermissionSettings = onOpenPermissionSettings)
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
    resumeDetailVersion: Triple<String, String, String>? = null,
    onDetailResumed: () -> Unit = {},
    onCloudSignInFromDetail: (MediaItem) -> Unit = {},
    showSearch: Boolean = false,
    onOpenPermissionSettings: (() -> Unit)? = null,
) {
    var showFilter by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val gridView = !state.fileListView
    var searchMode by rememberSaveable { mutableStateOf(FileSearchMode.FILES) }
    var ocrQuery by rememberSaveable { mutableStateOf("") }
    var ocrFilter by rememberSaveable(stateSaver = Saver<MediaFilter, String>(
        save = { AppSettings.encodeFilter(it).toString() },
        restore = { AppSettings.decodeFilter(org.json.JSONObject(it)) },
    )) { mutableStateOf(MediaFilter()) }
    val activeFilter = if (searchMode == FileSearchMode.OCR) ocrFilter else state.filter
    var startSelection by remember { mutableStateOf(false) }
    val clearSelection = {
        startSelection = false
        actions.clearSelection()
    }
    val updateFilter: (MediaFilter) -> Unit = { filter ->
        if (activeFilter != filter) {
            clearSelection()
            if (searchMode == FileSearchMode.OCR) ocrFilter = filter else actions.setFilter(filter)
        }
    }
    var detailVersion by rememberSaveable { mutableStateOf<Triple<String, String, String>?>(null) }
    val restrictedCloud = cloudFiles.catalogIssue?.blocksCachedCatalog == true
    var detailCloudId by remember(cloudFiles.accountUid, cloudFiles.mayReadCatalog, restrictedCloud) {
        mutableStateOf<String?>(null)
    }
    var sourceFilter by rememberSaveable { mutableStateOf(GallerySourceFilter.ALL) }
    var visiblePages by rememberSaveable { mutableIntStateOf(1) }
    var allowMeteredThumbnails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.filter, sourceFilter, cloudFiles.accountUid) { visiblePages = 1 }
    var chosenCloud by remember(cloudFiles.accountUid, cloudFiles.mayReadCatalog, restrictedCloud) {
        mutableStateOf<CloudOriginal?>(null)
    }
    val changeSource: (GallerySourceFilter) -> Unit = { option ->
        if (sourceFilter != option) {
            clearSelection()
            sourceFilter = option
            detailVersion = null
            detailCloudId = null
            chosenCloud = null
        }
    }
    val clearConditions = {
        updateFilter(MediaFilter())
        if (searchMode == FileSearchMode.OCR) ocrQuery = "" else changeSource(GallerySourceFilter.ALL)
        clearSelection()
    }
    val clearEmptyResultsAction: (@Composable () -> Unit)? =
        if (showSearch && (activeFilter != MediaFilter() || sourceFilter != GallerySourceFilter.ALL)) {
            { ActionButton("조건 지우기", clearConditions) }
        } else null
    val gridState = rememberLazyGridState()
    LaunchedEffect(sourceFilter) { gridState.scrollToItem(0) }
    var previousFilter by remember { mutableStateOf(activeFilter) }
    LaunchedEffect(activeFilter) {
        if (previousFilter != activeFilter) {
            previousFilter = activeFilter
            startSelection = false
            gridState.scrollToItem(0)
        }
    }
    val catalogVisible = !state.loading && state.catalogError == null && state.access != MediaAccess.NONE
    val deviceCatalogPending = state.access != MediaAccess.NONE && state.catalogError == null &&
        (state.loading || (!state.catalogReady && state.items.isEmpty()))
    val visibleItems = if (catalogVisible) state.items else emptyList()
    LaunchedEffect(resumeDetailVersion, catalogVisible, state.catalogReady) {
        if (resumeDetailVersion != null && catalogVisible && state.catalogReady) {
            detailVersion = resumeDetailVersion.takeIf { version -> visibleItems.any { it.matchesDetailVersion(version) } }
            onDetailResumed()
        }
    }
    val ocrRows = remember(visibleItems, state.ocrEntries) {
        PhotoOcrIndexPolicy.valid(visibleItems, state.ocrEntries)
    }
    val filtered = remember(catalogVisible, searchMode, visibleItems, activeFilter,
        state.filtered, state.ocrEntries, ocrQuery) {
        when {
            !catalogVisible -> emptyList()
            searchMode == FileSearchMode.OCR -> {
                val base = MediaQuery.apply(visibleItems, activeFilter.copy(kind = MediaKind.PHOTO, query = ""))
                PhotoOcrIndexPolicy.searchable(base, state.ocrEntries, ocrQuery)
            }
            else -> state.filtered
        }
    }
    val detail = detailVersion?.let { version -> visibleItems.find { it.matchesDetailVersion(version) } }
    val useUnified = cloudFiles.enabled && searchMode == FileSearchMode.FILES &&
        (sourceFilter == GallerySourceFilter.CLOUD ||
            (sourceFilter == GallerySourceFilter.ALL && cloudFiles.mayReadCatalog))
    val resultsPending = !useUnified && searchMode == FileSearchMode.FILES && state.filterPending
    val accessLabel = if (onOpenPermissionSettings == null) "접근 허용" else "앱 권한 설정"
    val openAccess = onOpenPermissionSettings ?: requestAccess
    val galleryItems = remember(
        visibleItems, cloudFiles.snapshot, cloudFiles.accountUid, cloudFiles.verifiedMatches,
    ) {
        combineGalleryCatalog(
            visibleItems, cloudFiles.snapshot, cloudFiles.accountUid, cloudFiles.verifiedMatches,
        )
    }
    val detailCloud = galleryItems.firstNotNullOfOrNull { it.cloud?.takeIf { cloud -> cloud.uploadId == detailCloudId } }
    val backedUpDeviceUris = remember(galleryItems) {
        galleryItems.mapNotNull { entry -> entry.device?.uri?.takeIf { entry.cloud != null } }.toSet()
    }
    val detailVerified = detail?.uri in backedUpDeviceUris
    val galleryFiltered = remember(galleryItems, state.filter, sourceFilter) {
        GalleryFileQuery.apply(galleryItems, state.filter, sourceFilter)
    }
    val visibleGallery = remember(galleryFiltered, visiblePages) {
        galleryFiltered.take(CloudGalleryPerformancePolicy.visibleCount(galleryFiltered.size, visiblePages))
    }
    val autoLoadThumbnails = CloudGalleryPerformancePolicy.canLoadThumbnails(
        cloudFiles.online, cloudFiles.unmetered, allowMeteredThumbnails,
    )
    val fileSections = remember(filtered, activeFilter) {
        FileSections.local(filtered, activeFilter)
    }
    val visibleDeviceItems = remember(resultsPending, useUnified, sourceFilter, visibleGallery, filtered,
        fileSections, state.filter) {
        when {
            resultsPending -> emptyList()
            !useUnified -> if (fileSections.isEmpty()) filtered else fileSections.flatMap { it.items }
            sourceFilter == GallerySourceFilter.CLOUD -> emptyList()
            else -> {
                val sections = FileSections.unified(visibleGallery, state.filter)
                val entries = if (sections.isEmpty()) visibleGallery else sections.flatMap { it.items }
                entries.mapNotNull { it.device }
            }
        }
    }
    val dragSelectionSession = Triple(
        Triple(activeFilter, searchMode, ocrQuery),
        Triple(sourceFilter, cloudFiles.accountUid, state.access),
        useUnified,
    )
    val selectable = remember(visibleDeviceItems) { SelectionPolicy.candidates(visibleDeviceItems) }
    val selected = remember(visibleDeviceItems, state.selected) {
        SelectionPolicy.candidates(visibleDeviceItems.filter { it.uri in state.selected })
    }
    val selectionMode = selected.isNotEmpty() || (startSelection && selectable.isNotEmpty())
    LaunchedEffect(selected.isNotEmpty()) {
        if (selected.isNotEmpty()) startSelection = false
    }
    BackHandler(enabled = selectionMode, onBack = clearSelection)
    LaunchedEffect(selectionMode) {
        if (selectionMode) {
            detailVersion = null
            detailCloudId = null
        }
    }
    val verifiedSelected = if (useUnified && cloudFiles.online && cloudFiles.catalogIssue == null) {
        selected.count { it.uri in backedUpDeviceUris }
    } else 0
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            ScrollAwayHeaderLayout(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                resetKey = (Triple(activeFilter, searchMode, ocrQuery) to showSearch) to
                    Triple(state.catalogError != null || state.access == MediaAccess.NONE,
                        sourceFilter, cloudFiles.accountUid),
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FileToolbar(activeFilter, state.presets, updateFilter,
                            advanced = { showFilter = true }, save = { showSave = true },
                            deletePreset = actions.deletePreset, gridView = gridView,
                            onGridViewChange = { actions.setFileListView(!it) }, searchMode = searchMode,
                            showSearch = showSearch,
                            ocrQuery = ocrQuery,
                            onSearchModeChange = { mode ->
                                if (searchMode != mode) {
                                    clearSelection()
                                    searchMode = mode
                                    detailVersion = null
                                    detailCloudId = null
                                    chosenCloud = null
                                }
                            },
                            onOcrQueryChange = { query ->
                                if (ocrQuery != query) {
                                    clearSelection()
                                    ocrQuery = query
                                }
                            },
                            sourceFilter = sourceFilter, showSourceFilter = cloudFiles.enabled,
                            onSourceFilterChange = changeSource,
                            onClearConditions = clearConditions,
                            onStartSelection = if (selectable.isNotEmpty() && !selectionMode) {
                                { startSelection = true }
                            } else null,
                        )
                        if (state.access == MediaAccess.PARTIAL &&
                            (searchMode == FileSearchMode.OCR || sourceFilter != GallerySourceFilter.CLOUD)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("허용한 기기 파일만 표시합니다.", Modifier.weight(1f), color = Muted,
                                    style = MaterialTheme.typography.bodySmall)
                                TextAction(if (onOpenPermissionSettings == null) "접근 변경" else "앱 권한 설정",
                                    onOpenPermissionSettings ?: requestAccess)
                            }
                        }
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
                                    if (cloudFiles.online && cloudFiles.catalogIssue == null &&
                                        !cloudFiles.matchChecking && cloudFiles.matchChecked < cloudFiles.matchTotal) {
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
                    val showOcrIndex = searchMode == FileSearchMode.OCR && catalogVisible && !deviceCatalogPending
                    val ocrSidebar = showOcrIndex && wide &&
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
                            if (showOcrIndex && !ocrSidebar) {
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
                            if (cloudFiles.loading && searchMode == FileSearchMode.FILES &&
                                sourceFilter == GallerySourceFilter.ALL) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                            }
                            when {
                                useUnified -> {
                                    when {
                                        sourceFilter == GallerySourceFilter.CLOUD && cloudFiles.accountUid == null ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 로그인이 필요합니다",
                                                    "기기 파일은 로그인 없이 계속 사용할 수 있습니다.",
                                                    action = {
                                                        ActionButton("클라우드에서 로그인", openCloud, Modifier.fillMaxWidth(), filled = true)
                                                        ActionButton("기기 파일 보기", { changeSource(GallerySourceFilter.DEVICE) }, Modifier.fillMaxWidth())
                                                    })
                                            }
                                        galleryFiltered.isEmpty() && sourceFilter == GallerySourceFilter.CLOUD && cloudFiles.loading ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 파일을 불러오는 중입니다",
                                                    "기기 파일은 별도로 확인할 수 있습니다.", action = {
                                                        ActionButton("기기 파일 보기", { changeSource(GallerySourceFilter.DEVICE) })
                                                    })
                                            }
                                        galleryFiltered.isEmpty() && sourceFilter == GallerySourceFilter.CLOUD &&
                                            cloudFiles.catalogIssue != null ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 파일을 표시할 수 없습니다",
                                                    "기기 파일은 계속 열 수 있습니다.", action = {
                                                        if (restrictedCloud) {
                                                            ActionButton("계정 확인", openCloud, Modifier.fillMaxWidth(), filled = true)
                                                        } else {
                                                            ActionButton("클라우드 다시 시도", cloudFiles.retry, Modifier.fillMaxWidth(), filled = true)
                                                        }
                                                        ActionButton("기기 파일 보기", { changeSource(GallerySourceFilter.DEVICE) }, Modifier.fillMaxWidth())
                                                    })
                                            }
                                        sourceFilter == GallerySourceFilter.CLOUD && !cloudFiles.mayReadCatalog ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("클라우드 보관함을 이용할 수 없습니다",
                                                    "클라우드 탭에서 이용 가능 여부를 확인할 수 있습니다.", action = {
                                                        ActionButton("클라우드 탭 열기", openCloud, Modifier.fillMaxWidth(), filled = true)
                                                        ActionButton("기기 파일 보기", { changeSource(GallerySourceFilter.DEVICE) }, Modifier.fillMaxWidth())
                                                    })
                                            }
                                        sourceFilter == GallerySourceFilter.CLOUD && cloudFiles.snapshot?.originals?.isEmpty() == true ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("아직 클라우드에 보관한 파일이 없습니다",
                                                    "보관한 사진과 동영상이 여기에 표시됩니다.", action = {
                                                        ActionButton("기기 파일 보기", { changeSource(GallerySourceFilter.DEVICE) })
                                                    })
                                            }
                                        galleryFiltered.isEmpty() && sourceFilter == GallerySourceFilter.ALL && deviceCatalogPending ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("미디어를 불러오고 있습니다", "현재 접근 권한으로 파일을 확인하고 있습니다.")
                                            }
                                        galleryFiltered.isEmpty() && sourceFilter == GallerySourceFilter.ALL && state.catalogError != null ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("기기 파일을 불러오지 못했습니다", state.catalogError,
                                                    action = { ActionButton("기기 파일 다시 시도", actions.refresh, filled = true) })
                                            }
                                        galleryFiltered.isEmpty() && sourceFilter == GallerySourceFilter.ALL && state.access == MediaAccess.NONE ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("미디어 접근이 필요합니다", "조건에 맞는 클라우드 파일은 없습니다.",
                                                    action = { ActionButton(accessLabel, openAccess, filled = true) })
                                            }
                                        galleryFiltered.isEmpty() ->
                                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                                EmptyState("조건에 맞는 파일이 없습니다",
                                                    "클라우드와 기기 저장 위치 또는 필터를 확인해 주세요.",
                                                    action = clearEmptyResultsAction,
                                                    scene = GalliScene.SEARCH)
                                            }
                                        else -> {
                                            val tabletGrid = wide && screenWidth >= 720.dp
                                            val availableGridWidth = if (sideDetail)
                                                screenWidth - detailWidth - 14.dp
                                            else if (ocrSidebar) screenWidth - sidebarWidth - 14.dp
                                            else screenWidth
                                            Column(Modifier.weight(1f).fillMaxWidth()) {
                                                if (cloudFiles.loading && sourceFilter == GallerySourceFilter.CLOUD) {
                                                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                                                }
                                                if (sourceFilter == GallerySourceFilter.CLOUD && !cloudFiles.online &&
                                                    cloudFiles.cachedCatalog) {
                                                    Text(
                                                        "오프라인 · 이전에 불러온 파일 정보만 표시합니다. 클라우드 원본 감상에는 인터넷 연결이 필요합니다.",
                                                        color = Muted, style = MaterialTheme.typography.bodySmall,
                                                    )
                                                } else if (cloudFiles.online && !cloudFiles.unmetered && !allowMeteredThumbnails &&
                                                    galleryFiltered.any { it.cloud != null }) {
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text("모바일 데이터 사용 중 · 자동 썸네일 요청을 제한했습니다.",
                                                            Modifier.weight(1f), color = Muted,
                                                            style = MaterialTheme.typography.bodySmall)
                                                        TextAction("이번 화면에서 허용", { allowMeteredThumbnails = true })
                                                    }
                                                }
                                                if (sourceFilter == GallerySourceFilter.ALL) {
                                                    when {
                                                        state.access == MediaAccess.NONE -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                            Text("클라우드 파일만 표시됩니다.", Modifier.weight(1f),
                                                                color = Muted, style = MaterialTheme.typography.bodySmall)
                                                            TextAction(accessLabel, openAccess)
                                                        }
                                                        state.catalogError != null -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                            Text("기기 파일 목록을 불러오지 못했습니다.", Modifier.weight(1f),
                                                                color = Muted, style = MaterialTheme.typography.bodySmall)
                                                            TextAction("기기 다시 시도", actions.refresh)
                                                        }
                                                        deviceCatalogPending -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                                    }
                                                }
                                                if (gridView && state.fileGridColumns != null) {
                                                    val applied = resolvedFileGridColumns(state.fileGridColumns,
                                                        availableGridWidth, screenFontScale, tabletGrid)
                                                    Text("한 줄 ${applied}개 표시 · 설정 ${state.fileGridColumns}개",
                                                        color = Muted, style = MaterialTheme.typography.bodySmall)
                                                }
                                                UnifiedFilesList(
                                                    entries = visibleGallery, filter = state.filter, source = sourceFilter,
                                                    gridView = gridView, wide = wide, availableWidth = availableGridWidth,
                                                    gridState = gridState, selected = state.selected,
                                                    selectionMode = selectionMode,
                                                    toggleDevice = actions.toggleSelection, openDevice = { uri ->
                                                        detailVersion = visibleItems.find { it.uri == uri }?.detailVersion()
                                                    },
                                                    openCloud = { detailCloudId = it.uploadId },
                                                    backup = cloudFiles.backup, accountUid = cloudFiles.accountUid,
                                                    autoLoadThumbnails = autoLoadThumbnails,
                                                    modifier = Modifier.weight(1f),
                                                    gridColumns = state.fileGridColumns,
                                                    dragSelectionSession = dragSelectionSession,
                                                    addSelected = actions.addSelected,
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
                                    EmptyState("미디어 접근이 필요합니다", "허용한 파일만 이곳에 표시됩니다.", action = {
                                        ActionButton(accessLabel, openAccess, filled = true)
                                    })
                                }
                                state.catalogError != null -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    EmptyState("파일을 불러오지 못했습니다", state.catalogError, Icons.Outlined.Info, action = { ActionButton("다시 시도", actions.refresh, filled = true) })
                                }
                                deviceCatalogPending && filtered.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                    EmptyState("미디어를 불러오고 있습니다", "현재 접근 권한으로 파일을 확인하고 있습니다.")
                                }
                                resultsPending ->
                                    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                        EmptyState("검색 결과를 확인하는 중입니다", "현재 적용한 조건으로 기기 파일을 살펴보고 있습니다.")
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
                                            if (state.access == MediaAccess.PARTIAL) "허용한 파일에서 현재 조건으로 검색했습니다."
                                            else "접근 범위와 필터 조건을 확인해 주세요.",
                                            action = clearEmptyResultsAction,
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
                                    DeviceFilesList(
                                        gridView = gridView,
                                        requestedColumns = state.fileGridColumns,
                                        availableGridWidth = availableGridWidth,
                                        fontScale = fontScale,
                                        tabletGrid = tabletGrid,
                                        gridState = gridState,
                                        visibleDeviceItems = visibleDeviceItems,
                                        filtered = filtered,
                                        fileSections = fileSections,
                                        activeFilter = activeFilter,
                                        actions = actions,
                                        selectedUris = state.selected,
                                        selectionMode = selectionMode,
                                        dragSelectionSession = dragSelectionSession,
                                        backedUpDeviceUris = backedUpDeviceUris,
                                        showOcrText = searchMode == FileSearchMode.OCR,
                                        ocrRows = ocrRows,
                                        ocrQuery = ocrQuery,
                                        onOpen = { detailVersion = it.detailVersion() },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        if (sideDetail && detail != null) {
                            Column(Modifier.width(detailWidth).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface).border(1.dp, Line, RoundedCornerShape(20.dp))
                                .semantics { contentDescription = "파일 상세 패널" }) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("파일 정보", style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f).semantics { heading() })
                                    IconAction("파일 상세 닫기", Icons.Outlined.Close) { detailVersion = null }
                                }
                                MediaDetail(detail, open, { actions.setProtected(detail, it) },
                                    Modifier.weight(1f), detail.hasFolderProtection(state.protectedFolders),
                                    ocrText = if (searchMode == FileSearchMode.OCR)
                                        ocrRows[detail.uri]?.recognizedText else null,
                                    ocrQuery = ocrQuery,
                                    cloudBackup = cloudFiles.backup,
                                    accountUid = cloudFiles.accountUid,
                                    cloudVerified = detailVerified,
                                    cloudDetailAccess = cloudFiles.detailAccess,
                                    cloudAccountEmail = cloudFiles.accountEmail,
                                    cloudNotice = cloudFiles.detailNotice,
                                    onRequestCloud = {
                                        onCloudSignInFromDetail(detail)
                                    })
                            }
                        }
                    }
                    if (!useSideDetail && detail != null) MediaDetailDialog(
                        detail, { detailVersion = null }, open,
                        { actions.setProtected(detail, it) }, detail.hasFolderProtection(state.protectedFolders),
                        ocrText = if (searchMode == FileSearchMode.OCR)
                            ocrRows[detail.uri]?.recognizedText else null,
                        ocrQuery = ocrQuery,
                        cloudBackup = cloudFiles.backup,
                        accountUid = cloudFiles.accountUid,
                        cloudVerified = detailVerified,
                        cloudDetailAccess = cloudFiles.detailAccess,
                        cloudAccountEmail = cloudFiles.accountEmail,
                        cloudNotice = cloudFiles.detailNotice,
                        onRequestCloud = {
                            onCloudSignInFromDetail(detail)
                        },
                    )
                }
            }

            if (!selectionMode && !resultsPending) {
                when {
                    useUnified && visibleGallery.isNotEmpty() -> FilesFooterSummary(
                        "표시 ${visibleGallery.size}개",
                        selectable,
                        actions.selectAll,
                    )
                    !useUnified && filtered.isNotEmpty() -> {
                        val count = String.format(java.util.Locale.KOREA, "%,d", filtered.size)
                        FilesFooterSummary(
                            "총 ${count}개 · ${bytesLabel(filtered.sumOf { it.sizeBytes })}",
                            selectable,
                            actions.selectAll,
                        )
                    }
                }
            }
            if (verifiedSelected > 0 && selected.isNotEmpty()) {
                Text("선택한 기기 파일 중 ${verifiedSelected}개는 서버의 검증된 백업 기록과 내용이 일치합니다. " +
                    "클라우드 필터에서 원본을 열어 확인한 뒤, Android 확인창에서 기기 사본을 정리해 주세요.",
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (selectionMode && selected.isEmpty()) {
                Row(Modifier.fillMaxWidth().galliCardSurface(Panel).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("선택할 파일을 눌러 주세요.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextAction("선택 닫기", clearSelection)
                }
            }
            SelectionBar(selected, clearSelection, { trash(selected) }, clearOnSearchChange = true)
        }
        if (showFilter) FilterDialog(activeFilter, visibleItems, {
            updateFilter(MediaFilterActions.sort(it, it.sortField, it.sortDirection)); showFilter = false
        }, { showFilter = false }, cloudItems = if (searchMode == FileSearchMode.FILES) cloudFiles.snapshot?.originals.orEmpty() else emptyList(),
            sourceFilter = sourceFilter.takeIf { cloudFiles.enabled && searchMode == FileSearchMode.FILES },
            onSourceFilterChange = changeSource, searchMode = searchMode)
        if (showSave) SavePresetDialog({ actions.savePreset(it); showSave = false }, { showSave = false })
        detailCloud?.let { original ->
            CloudFileDetailDialog(
                original = original, uid = cloudFiles.accountUid, backup = cloudFiles.backup,
                offline = !cloudFiles.online, onDismiss = { detailCloudId = null },
                onPreview = { detailCloudId = null; chosenCloud = original },
                onRestored = actions.refresh, autoLoadThumbnails = autoLoadThumbnails,
            )
        }
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
        cloudFiles.accountUid?.let { uid ->
            key(uid) {
                var catalogErrorAcknowledged by rememberSaveable { mutableStateOf(false) }
                val issue = cloudFiles.catalogIssue
                if (cloudFiles.enabled && issue != null && !cloudFiles.loading && !catalogErrorAcknowledged &&
                    searchMode == FileSearchMode.FILES && sourceFilter != GallerySourceFilter.DEVICE &&
                    !showFilter && !showSave && !selectionMode && detail == null &&
                    detailCloud == null && chosenCloud == null) {
                    FileCloudCatalogErrorDialog(
                        issue = issue,
                        dismiss = { catalogErrorAcknowledged = true },
                        confirm = {
                            catalogErrorAcknowledged = true
                            if (issue.blocksCachedCatalog) openCloud() else cloudFiles.retry()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileCloudCatalogErrorDialog(issue: CloudCatalogIssue, dismiss: () -> Unit, confirm: () -> Unit) {
    val reason = when (issue) {
        CloudCatalogIssue.CONNECTION -> "인터넷 연결을 확인해 주세요."
        CloudCatalogIssue.TIMEOUT -> "클라우드 서버 응답 시간이 초과되었습니다."
        CloudCatalogIssue.TEMPORARY_FAILURE -> "잠시 후 다시 시도해 주세요."
        CloudCatalogIssue.AUTH_REQUIRED -> "Google 계정을 다시 확인해 주세요."
        CloudCatalogIssue.ACCOUNT_RESTRICTED -> "Google 계정의 클라우드 보관함 접근 권한을 확인해 주세요."
    }
    AlertDialog(
        onDismissRequest = dismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = AppSurface,
        title = { Text("클라우드 파일을 불러오지 못했습니다", style = MaterialTheme.typography.titleSmall) },
        text = { Text(reason, color = Muted, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextAction(if (issue.blocksCachedCatalog) "계정 확인" else "다시 시도", confirm) },
        dismissButton = { TextAction("닫기", dismiss) },
    )
}

internal fun MediaItem.detailVersion(): Triple<String, String, String> = Triple(uri, fingerprint, volumeVersion)

private fun MediaItem.matchesDetailVersion(version: Triple<String, String, String>): Boolean =
    uri == version.first && fingerprint == version.second && volumeVersion == version.third

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
