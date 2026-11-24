package com.hebi.gallipick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.GallerySourceFilter
import com.hebi.gallipick.domain.FileGroupMode
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.Orientation
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import kotlinx.coroutines.launch

private const val BYTES_PER_MB = 1_048_576.0

@Composable
internal fun FilterDialog(
    current: MediaFilter,
    items: List<MediaItem>,
    onApply: (MediaFilter) -> Unit,
    onDismiss: () -> Unit,
    cloudItems: List<CloudOriginal> = emptyList(),
    sourceFilter: GallerySourceFilter? = null,
    onSourceFilterChange: (GallerySourceFilter) -> Unit = {},
    searchMode: FileSearchMode = FileSearchMode.FILES,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            FilterDialogContent(current, items, onApply, onDismiss, Modifier.fillMaxSize(),
                cloudItems, sourceFilter, onSourceFilterChange, searchMode)
        }
    }
}

/** Every condition is validated on apply, including a collapsed advanced group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterDialogContent(
    current: MediaFilter,
    items: List<MediaItem>,
    onApply: (MediaFilter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    cloudItems: List<CloudOriginal> = emptyList(),
    sourceFilter: GallerySourceFilter? = null,
    onSourceFilterChange: (GallerySourceFilter) -> Unit = {},
    searchMode: FileSearchMode = FileSearchMode.FILES,
) {
    val photoTextSearch = searchMode == FileSearchMode.OCR
    var kind by remember(current) { mutableStateOf(current.kind) }
    var query by remember(current) { mutableStateOf(current.query) }
    var folder by remember(current) { mutableStateOf(current.folder) }
    var downloadsOnly by remember(current) { mutableStateOf(current.downloadsOnly) }
    var groupByDate by remember(current) { mutableStateOf(current.groupByDate) }
    var groupMode by remember(current) { mutableStateOf(current.groupMode) }
    var mimeType by remember(current) { mutableStateOf(current.mimeType) }
    var minSize by remember(current) { mutableStateOf(numberText(current.minSizeBytes, BYTES_PER_MB)) }
    var maxSize by remember(current) { mutableStateOf(numberText(current.maxSizeBytes, BYTES_PER_MB)) }
    var minDuration by remember(current) { mutableStateOf(numberText(current.minDurationMs, 60_000.0)) }
    var maxDuration by remember(current) { mutableStateOf(numberText(current.maxDurationMs, 60_000.0)) }
    var dateField by remember(current) { mutableStateOf(current.dateField) }
    var fromDate by remember(current) { mutableStateOf(dateText(current.fromDateMs)) }
    var toDate by remember(current) { mutableStateOf(dateText(current.toDateMs)) }
    var minPixels by remember(current) { mutableStateOf(current.minPixels) }
    var orientation by remember(current) { mutableStateOf(current.orientation) }
    var favoritesOnly by remember(current) { mutableStateOf(current.favoritesOnly) }
    var protectedOnly by remember(current) { mutableStateOf(current.protectedOnly) }
    var sortField by remember(current) { mutableStateOf(current.sortField) }
    var sortDirection by remember(current) { mutableStateOf(current.sortDirection) }
    var selectedSource by remember(sourceFilter, photoTextSearch) {
        mutableStateOf(sourceFilter.takeUnless { photoTextSearch })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var invalidTarget by remember { mutableStateOf<String?>(null) }
    var validationAttempt by remember { mutableIntStateOf(0) }
    val dateRequester = remember { BringIntoViewRequester() }
    val sizeRequester = remember { BringIntoViewRequester() }
    val durationRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) {
            withFrameNanos { }
            val requester = when (invalidTarget) {
                "date" -> dateRequester
                "duration" -> durationRequester
                else -> sizeRequester
            }
            runCatching { requester.bringIntoView() }
        }
    }
    var quickDateSelection by remember(current) { mutableStateOf(matchingDatePreset(current.fromDateMs, current.toDateMs)) }
    var calendarField by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var sizeHelpExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember(current) {
        mutableStateOf(
            current.mimeType.isNotBlank() ||
                current.orientation != null || current.minPixels != null ||
                current.favoritesOnly || current.protectedOnly ||
                current.query.isNotBlank() || current.folder.isNotBlank() || current.downloadsOnly ||
                current.minSizeBytes != null || current.maxSizeBytes != null ||
                current.minDurationMs != null || current.maxDurationMs != null,
        )
    }
    val deviceItems = remember(items, photoTextSearch) {
        if (photoTextSearch) items.filter { it.kind == MediaKind.PHOTO } else items
    }
    val availableCloudItems = if (photoTextSearch) emptyList() else cloudItems
    val folders = remember(deviceItems, availableCloudItems, current.folder) {
        (deviceItems.map { it.relativePath } + availableCloudItems.flatMap { it.sourceFolders } + current.folder)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val mimeTypes = remember(deviceItems, availableCloudItems, current.mimeType) {
        (deviceItems.map { it.mimeType } + availableCloudItems.map { it.mimeType } + current.mimeType)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val activeAdvancedCount = listOf(
        query.isNotBlank(), folder.isNotBlank(), downloadsOnly,
        minSize.isNotBlank() || maxSize.isNotBlank(),
        minDuration.isNotBlank() || maxDuration.isNotBlank(),
        mimeType.isNotBlank(), orientation != null, minPixels != null,
        favoritesOnly, protectedOnly,
    ).count { it }
    val basicContent: @Composable ColumnScope.() -> Unit = {
        if (!photoTextSearch) FilterGroup("미디어 유형") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChoice("전체", kind == null) { kind = null }
                FilterChoice("사진", kind == MediaKind.PHOTO) { kind = MediaKind.PHOTO }
                FilterChoice("동영상", kind == MediaKind.VIDEO) { kind = MediaKind.VIDEO }
            }
        }
        Column(Modifier.bringIntoViewRequester(dateRequester)) {
            FilterDateRange(
                dateField = dateField,
                fromDate = fromDate,
                toDate = toDate,
                quickDateSelection = quickDateSelection,
                onDateFieldChange = { dateField = it },
                onPresetSelected = { from, to, preset ->
                    fromDate = from
                    toDate = to
                    quickDateSelection = preset
                },
                onFromDateChange = {
                    fromDate = it
                    quickDateSelection = "custom"
                },
                onToDateChange = {
                    toDate = it
                    quickDateSelection = "custom"
                },
                onOpenCustomDate = {
                    quickDateSelection = "custom"
                    calendarField = "start"
                },
                onOpenStartDate = { calendarField = "start" },
                onOpenEndDate = { calendarField = "end" },
            )
        }
        FilterGroup("그룹 보기") {
            Text("선택한 정렬과 필터에 맞춰 파일을 묶습니다.", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileGroupMode.entries.forEach { mode ->
                    FilterChoice(fileGroupModeLabel(mode), groupMode == mode) {
                        groupMode = mode
                        if (mode != FileGroupMode.AUTO) groupByDate = false
                    }
                }
            }
        }
        FilterGroup("정렬") {
            val availableSort = SortField.entries.filterNot { photoTextSearch && it == SortField.DURATION }
            FilterDropdown("정렬 기준", sortFieldLabel(sortField), availableSort.map { it to sortFieldLabel(it) }) {
                sortField = it
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChoice("내림차순", sortDirection == SortDirection.DESCENDING) {
                    sortDirection = SortDirection.DESCENDING
                }
                FilterChoice("오름차순", sortDirection == SortDirection.ASCENDING) {
                    sortDirection = SortDirection.ASCENDING
                }
            }
        }
        if (selectedSource != null) {
            FilterGroup("저장 위치") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GallerySourceFilter.entries.forEach { option ->
                        FilterChoice(sourceLabel(option), selectedSource == option) {
                            selectedSource = option
                        }
                    }
                }
            }
        }
    }
    val advancedContent: @Composable ColumnScope.() -> Unit = {
        FilterGroup(if (photoTextSearch) "기기 사진 폴더" else "검색과 폴더") {
            if (!photoTextSearch) OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("파일 이름 검색") },
                singleLine = true, shape = RoundedCornerShape(GalliUiMetrics.buttonCorner),
                modifier = Modifier.fillMaxWidth(),
            )
            FilterDropdown(
                "폴더", folder.ifBlank { "모든 폴더" },
                listOf("" to "모든 폴더") + folders.map { it to it },
            ) { folder = it }
            FilterChoice("다운로드 폴더만 (하위 폴더 포함)", downloadsOnly) {
                downloadsOnly = !downloadsOnly
            }
        }
        Column(Modifier.bringIntoViewRequester(sizeRequester)) {
        FilterGroup("파일 크기 · MB") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("최소·최대 파일 크기", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
                TextAction("용량 단위 안내", onClick = { sizeHelpExpanded = !sizeHelpExpanded },
                    modifier = Modifier.semantics { stateDescription = if (sizeHelpExpanded) "펼쳐짐" else "접힘" })
            }
            FilterBounds(minSize, { minSize = it }, maxSize, { maxSize = it }, "최소 MB", "최대 MB")
            if (sizeHelpExpanded) {
                Text(
                    "앱의 용량 표기와 같은 기준입니다. 1 MB = 1,048,576바이트.",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        }
        if (kind == MediaKind.VIDEO) {
            Column(Modifier.bringIntoViewRequester(durationRequester)) {
                FilterGroup("동영상 길이 · 분") {
                    FilterBounds(minDuration, { minDuration = it }, maxDuration, { maxDuration = it }, "최소 분", "최대 분")
                }
            }
        } else if (!photoTextSearch) {
            Text("길이 조건은 동영상 유형에서 설정할 수 있습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        FilterGroup("파일 형식") {
            FilterDropdown(
                "파일 형식", mimeType.ifBlank { "모든 형식" },
                listOf("" to "모든 형식") + mimeTypes.map { it to it },
            ) { mimeType = it }
        }
        FilterGroup("화면 방향") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChoice("전체", orientation == null) { orientation = null }
                Orientation.entries.forEach { value ->
                    FilterChoice(orientationLabel(value), orientation == value) { orientation = value }
                }
            }
        }
        FilterGroup("최소 해상도") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    null to "전체", 921_600L to "HD", 2_073_600L to "FHD", 3_686_400L to "QHD",
                    8_294_400L to "4K", 33_177_600L to "8K",
                    1_000_000L to "1 MP", 4_000_000L to "4 MP", 8_000_000L to "8 MP", 12_000_000L to "12 MP",
                ).forEach { (pixels, label) ->
                    FilterChoice(label, minPixels == pixels) { minPixels = pixels }
                }
                if (minPixels != null && minPixels !in listOf(
                    921_600L, 2_073_600L, 3_686_400L, 8_294_400L, 33_177_600L,
                    1_000_000L, 4_000_000L, 8_000_000L, 12_000_000L,
                )) {
                    FilterChoice("${numberText(minPixels, 1_000_000.0)} MP", true) {}
                }
            }
        }
        FilterGroup("추가 조건") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.plainClick { favoritesOnly = !favoritesOnly }, verticalAlignment = Alignment.CenterVertically) {
                    SelectBox(checked = favoritesOnly, description = "즐겨찾기만", onClick = { favoritesOnly = !favoritesOnly })
                    Text("즐겨찾기만", Modifier.weight(1f, fill = false))
                }
                Row(Modifier.plainClick { protectedOnly = !protectedOnly }, verticalAlignment = Alignment.CenterVertically) {
                    SelectBox(checked = protectedOnly, description = "보호된 파일만", onClick = { protectedOnly = !protectedOnly })
                    Text("보호된 파일만", Modifier.weight(1f, fill = false))
                }
            }
        }
    }
    BackHandler(enabled = calendarField != null) { calendarField = null }
    Surface(
        modifier = modifier.windowInsetsPadding(WindowInsets.ime),
        shape = RoundedCornerShape(0.dp),
        color = AppBackground,
        border = null,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        if (calendarField != null) {
            val field = calendarField!!
            FilterCalendarPanel(
                label = if (field == "start") "시작일" else "종료일",
                value = if (field == "start") fromDate else toDate,
                onDismiss = { calendarField = null },
                onSelect = { selected ->
                    if (field == "start") fromDate = selected else toDate = selected
                    quickDateSelection = "custom"
                    calendarField = null
                },
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 680.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val roomyFooter = maxWidth >= 500.dp
            val compact = maxHeight < 360.dp
            val compactChrome = maxHeight < 200.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                if (!compactChrome) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = if (compact) 2.dp else 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconAction("필터 닫기", Icons.Outlined.ArrowBack, onClick = onDismiss)
                        Text("필터", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextAction("기간 선택", onClick = { calendarField = "start" })
                    }
                }
                // Clip the entire viewport: scrolling and focused fields cannot paint under the footer.
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().semantics { contentDescription = "필터 조건" }) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scrollState)
                            .padding(horizontal = 20.dp, vertical = if (compactChrome) 0.dp else 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            (if (photoTextSearch) "사진 속 글자 · 기기 사진"
                                else "선택: ${when (kind) { MediaKind.PHOTO -> "사진"; MediaKind.VIDEO -> "동영상"; null -> "전체" }}") +
                                " · ${if (fromDate.isBlank() && toDate.isBlank()) "전체 기간" else "기간 지정"}" +
                                " · ${fileGroupModeLabel(groupMode)} 그룹" +
                                " · ${sortFieldLabel(sortField)}" +
                                (selectedSource?.let { " · 위치: ${sourceLabel(it)}" } ?: "") +
                                (if (activeAdvancedCount > 0) " · 세부 ${activeAdvancedCount}개" else ""),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Column(Modifier.weight(1f).semantics { contentDescription = "기본 필터 조건" },
                                    verticalArrangement = Arrangement.spacedBy(10.dp), content = basicContent)
                                Column(Modifier.weight(1f).semantics { contentDescription = "고급 필터 조건" },
                                    verticalArrangement = Arrangement.spacedBy(14.dp), content = advancedContent)
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().semantics { contentDescription = "기본 필터 조건" },
                                verticalArrangement = Arrangement.spacedBy(10.dp), content = basicContent)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton(
                                    if (advancedExpanded) "고급 조건 접기" else "고급 조건 열기",
                                    onClick = { advancedExpanded = !advancedExpanded },
                                    modifier = Modifier.fillMaxWidth().semantics {
                                        stateDescription = if (advancedExpanded) "펼쳐짐" else "접힘"
                                    },
                                )
                                Text(if (activeAdvancedCount > 0) "세부 조건 ${activeAdvancedCount}개 적용 중"
                                    else if (photoTextSearch) "폴더 · 크기 · 형식 · 해상도 · 보호"
                                    else "검색 · 폴더 · 크기 · 길이 · 형식 · 해상도 · 보호",
                                    color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                            if (advancedExpanded) {
                                Column(Modifier.fillMaxWidth().semantics { contentDescription = "고급 필터 조건" },
                                    verticalArrangement = Arrangement.spacedBy(14.dp), content = advancedContent)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().semantics { contentDescription = "필터 작업" }) {
                    HorizontalDivider(color = Line)
                    Column(Modifier.padding(horizontal = 12.dp, vertical = when {
                        compactChrome -> 0.dp
                        compact -> 8.dp
                        else -> 12.dp
                    }),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            ActionButton(
                                label = "필터 적용", filled = true, modifier = if (roomyFooter) Modifier else Modifier.weight(1.5f),
                                onClick = {
                                    try {
                                        val minimumSize = parseBound(minSize, BYTES_PER_MB, "최소 크기", current.minSizeBytes)
                                        val maximumSize = parseBound(maxSize, BYTES_PER_MB, "최대 크기", current.maxSizeBytes)
                                        checkOrder(minimumSize, maximumSize, "파일 크기")
                                        val minimumDuration = if (kind == MediaKind.VIDEO) parseBound(minDuration, 60_000.0, "최소 길이", current.minDurationMs) else null
                                        val maximumDuration = if (kind == MediaKind.VIDEO) parseBound(maxDuration, 60_000.0, "최대 길이", current.maxDurationMs) else null
                                        checkOrder(minimumDuration, maximumDuration, "동영상 길이")
                                        val from = parseDateBound(fromDate, false)
                                        val to = parseDateBound(toDate, true)
                                        checkOrder(from, to, "날짜")
                                        onApply(current.copy(
                                            kind = kind, query = query.trim(), folder = folder, mimeType = mimeType,
                                            downloadsOnly = downloadsOnly, groupByDate = groupByDate,
                                            groupMode = groupMode,
                                            minSizeBytes = minimumSize, maxSizeBytes = maximumSize,
                                            minDurationMs = minimumDuration, maxDurationMs = maximumDuration,
                                            dateField = dateField, fromDateMs = from, toDateMs = to,
                                            minPixels = minPixels, orientation = orientation,
                                            favoritesOnly = favoritesOnly, protectedOnly = protectedOnly,
                                            sortField = sortField, sortDirection = sortDirection,
                                        ))
                                        selectedSource?.let(onSourceFilterChange)
                                        onDismiss()
                                    } catch (invalid: IllegalArgumentException) {
                                        error = invalid.message ?: "입력한 조건을 확인해 주세요."
                                        invalidTarget = when {
                                            error.orEmpty().contains("날짜") -> "date"
                                            error.orEmpty().contains("길이") -> "duration"
                                            else -> "size"
                                        }
                                        if (invalidTarget != "date") advancedExpanded = true
                                        validationAttempt++
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        if (compactChrome) scope.launch { scrollState.scrollTo(0) }
                                    }
                                },
                            )
                            TextAction("취소", onClick = onDismiss, modifier = if (roomyFooter) Modifier else Modifier.weight(1f))
                            TextAction("초기화", onClick = {
                                onApply(MediaFilter())
                                selectedSource?.let { onSourceFilterChange(GallerySourceFilter.ALL) }
                                onDismiss()
                            }, modifier = if (roomyFooter) Modifier else Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        }
    }
}

private fun orientationLabel(value: Orientation): String = when (value) {
    Orientation.LANDSCAPE -> "가로"
    Orientation.PORTRAIT -> "세로"
    Orientation.SQUARE -> "정사각형"
}

private fun sortFieldLabel(value: SortField): String = when (value) {
    SortField.CAPTURED -> "촬영일"
    SortField.ADDED -> "추가일"
    SortField.MODIFIED -> "수정일"
    SortField.SIZE -> "파일 크기"
    SortField.DURATION -> "동영상 길이"
    SortField.NAME -> "파일 이름"
    SortField.PIXELS -> "해상도"
}
