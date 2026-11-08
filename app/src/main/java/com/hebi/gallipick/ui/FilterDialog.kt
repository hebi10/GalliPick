package com.hebi.gallipick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.Orientation
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch

private const val BYTES_PER_MB = 1_048_576.0

@Composable
fun FilterDialog(
    current: MediaFilter,
    items: List<MediaItem>,
    onApply: (MediaFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            FilterDialogContent(current, items, onApply, onDismiss, Modifier.fillMaxSize())
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
) {
    var kind by remember(current) { mutableStateOf(current.kind) }
    var query by remember(current) { mutableStateOf(current.query) }
    var folder by remember(current) { mutableStateOf(current.folder) }
    var downloadsOnly by remember(current) { mutableStateOf(current.downloadsOnly) }
    var groupByDate by remember(current) { mutableStateOf(current.groupByDate) }
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
    var error by remember { mutableStateOf<String?>(null) }
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
                current.dateField != DateField.CAPTURED || current.orientation != null || current.minPixels != null ||
                current.favoritesOnly || current.protectedOnly || current.sortField != SortField.CAPTURED ||
                current.sortDirection != SortDirection.DESCENDING,
        )
    }
    val folders = remember(items, current.folder) {
        (items.map { it.relativePath }.filter { it.isNotBlank() } + current.folder)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val mimeTypes = remember(items, current.mimeType) {
        (items.map { it.mimeType }.filter { it.isNotBlank() } + current.mimeType)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val basicContent: @Composable ColumnScope.() -> Unit = {
        FilterGroup("미디어 유형") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChoice("전체", kind == null) { kind = null }
                FilterChoice("사진", kind == MediaKind.PHOTO) { kind = MediaKind.PHOTO }
                FilterChoice("동영상", kind == MediaKind.VIDEO) { kind = MediaKind.VIDEO }
            }
        }
        FilterGroup("날짜 범위") {
            Text("${dateFieldLabel(dateField)} 기준", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(dateBasisHelp(dateField), color = Muted, style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DateField.entries.forEach { field ->
                    FilterChoice(dateFieldLabel(field), dateField == field) { dateField = field }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChoice("오늘", quickDateSelection == "today") {
                    val today = dateText(Calendar.getInstance().timeInMillis)
                    fromDate = today
                    toDate = today
                    quickDateSelection = "today"
                }
                FilterChoice("최근 7일", quickDateSelection == "7") {
                    val end = Calendar.getInstance()
                    toDate = dateText(end.timeInMillis)
                    end.add(Calendar.DAY_OF_MONTH, -6)
                    fromDate = dateText(end.timeInMillis)
                    quickDateSelection = "7"
                }
                FilterChoice("최근 30일", quickDateSelection == "30") {
                    val end = Calendar.getInstance()
                    toDate = dateText(end.timeInMillis)
                    end.add(Calendar.DAY_OF_MONTH, -29)
                    fromDate = dateText(end.timeInMillis)
                    quickDateSelection = "30"
                }
                FilterChoice("전체 기간", fromDate.isBlank() && toDate.isBlank()) {
                    fromDate = ""
                    toDate = ""
                    quickDateSelection = "all"
                }
                FilterChoice("이번 달", quickDateSelection == "month") {
                    val today = Calendar.getInstance()
                    toDate = dateText(today.timeInMillis)
                    today.set(Calendar.DAY_OF_MONTH, 1)
                    fromDate = dateText(today.timeInMillis)
                    quickDateSelection = "month"
                }
                FilterChoice("지난 달", quickDateSelection == "previous") {
                    val previous = Calendar.getInstance().apply {
                        set(Calendar.DAY_OF_MONTH, 1)
                        add(Calendar.MONTH, -1)
                    }
                    fromDate = dateText(previous.timeInMillis)
                    previous.set(Calendar.DAY_OF_MONTH, previous.getActualMaximum(Calendar.DAY_OF_MONTH))
                    toDate = dateText(previous.timeInMillis)
                    quickDateSelection = "previous"
                }
                FilterChoice("직접 선택", quickDateSelection == "custom" && (fromDate.isNotBlank() || toDate.isNotBlank())) {
                    quickDateSelection = "custom"
                    calendarField = "start"
                }
            }
            FilterFieldPair(
                first = { fieldModifier ->
                    FilterDateInput("시작일", fromDate, { fromDate = it; quickDateSelection = "custom" }, fieldModifier) { calendarField = "start" }
                },
                second = { fieldModifier ->
                    FilterDateInput("종료일", toDate, { toDate = it; quickDateSelection = "custom" }, fieldModifier) { calendarField = "end" }
                },
            )
            Text(
                "달력에서 날짜를 선택하거나 직접 입력하세요. 종료일은 해당 날짜 전체를 포함합니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        FilterGroup("검색과 폴더") {
            OutlinedTextField(
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
                if (!downloadsOnly) groupByDate = false
            }
        }
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
        if (kind == MediaKind.VIDEO) {
            FilterGroup("동영상 길이 · 분") {
                FilterBounds(minDuration, { minDuration = it }, maxDuration, { maxDuration = it }, "최소 분", "최대 분")
            }
        } else {
            Text("길이 조건은 동영상 유형에서 설정할 수 있습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    val advancedContent: @Composable ColumnScope.() -> Unit = {
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
        FilterGroup("정렬") {
            FilterDropdown("정렬 기준", sortFieldLabel(sortField), SortField.entries.map { it to sortFieldLabel(it) }) {
                sortField = it
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChoice("내림차순", sortDirection == SortDirection.DESCENDING) {
                    sortDirection = SortDirection.DESCENDING
                }
                FilterChoice("오름차순", sortDirection == SortDirection.ASCENDING) {
                    sortDirection = SortDirection.ASCENDING
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
                        IconAction("필터 닫기", Icons.Rounded.ArrowBack, onClick = onDismiss)
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
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Column(Modifier.weight(1f).semantics { contentDescription = "기본 필터 조건" },
                                    verticalArrangement = Arrangement.spacedBy(14.dp), content = basicContent)
                                Column(Modifier.weight(1f).semantics { contentDescription = "고급 필터 조건" },
                                    verticalArrangement = Arrangement.spacedBy(14.dp), content = advancedContent)
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().semantics { contentDescription = "기본 필터 조건" },
                                verticalArrangement = Arrangement.spacedBy(14.dp), content = basicContent)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton(
                                    if (advancedExpanded) "고급 조건 접기" else "고급 조건 열기",
                                    onClick = { advancedExpanded = !advancedExpanded },
                                    modifier = Modifier.fillMaxWidth().semantics {
                                        stateDescription = if (advancedExpanded) "펼쳐짐" else "접힘"
                                    },
                                )
                                Text("파일 형식 · 날짜 · 화면 방향 · 해상도 · 정렬", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                            TextAction("초기화", onClick = { onApply(MediaFilter()); onDismiss() }, modifier = if (roomyFooter) Modifier else Modifier.weight(1f))
                            TextAction("취소", onClick = onDismiss, modifier = if (roomyFooter) Modifier else Modifier.weight(1f))
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
                                            minSizeBytes = minimumSize, maxSizeBytes = maximumSize,
                                            minDurationMs = minimumDuration, maxDurationMs = maximumDuration,
                                            dateField = dateField, fromDateMs = from, toDateMs = to,
                                            minPixels = minPixels, orientation = orientation,
                                            favoritesOnly = favoritesOnly, protectedOnly = protectedOnly,
                                            sortField = sortField, sortDirection = sortDirection,
                                        ))
                                        onDismiss()
                                    } catch (invalid: IllegalArgumentException) {
                                        error = invalid.message ?: "입력한 조건을 확인해 주세요."
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        if (compactChrome) scope.launch { scrollState.scrollTo(0) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FilterDateInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCalendar: () -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text("yyyy-MM-dd") },
            singleLine = true,
            shape = RoundedCornerShape(GalliUiMetrics.buttonCorner),
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        IconAction("$label 달력에서 선택", Icons.Rounded.CalendarMonth, onClick = onOpenCalendar)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterCalendarPanel(
    label: String,
    value: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 500.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val initialDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
        val initialMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
            initialDisplayMode = if (compactHeight) DisplayMode.Input else DisplayMode.Picker,
        )
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                DatePicker(
                    state = picker,
                    showModeToggle = !compactHeight,
                    title = {
                        Text(
                            "$label 선택", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    },
                )
            }
            HorizontalDivider(color = Line)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("취소") }
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { millis ->
                            onSelect(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        }
                    },
                    enabled = picker.selectedDateMillis != null,
                ) {
                    Text("날짜 선택")
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterSection(title)
        content()
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun FilterSection(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(GalliUiMetrics.buttonCorner)
    Text(
        text = label,
        color = if (selected) colors.onPrimary else colors.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.sizeIn(minHeight = GalliUiMetrics.touchTarget)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .clip(shape)
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else Line, shape)
            .plainClick(action = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
    )
}

@Composable
private fun FilterBounds(
    minimum: String, onMinimum: (String) -> Unit,
    maximum: String, onMaximum: (String) -> Unit,
    minimumLabel: String, maximumLabel: String,
) {
    FilterFieldPair(
        first = { fieldModifier ->
            OutlinedTextField(
                value = minimum, onValueChange = onMinimum, label = { Text(minimumLabel) },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = fieldModifier,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        second = { fieldModifier ->
            OutlinedTextField(
                value = maximum, onValueChange = onMaximum, label = { Text(maximumLabel) },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = fieldModifier,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
    )
}

@Composable
private fun FilterFieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 280.dp * fontScale.coerceAtLeast(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun <T> FilterDropdown(label: String, valueLabel: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = when {
                    anchorBounds.bottom + popupContentSize.height <= windowSize.height -> anchorBounds.bottom
                    anchorBounds.top >= popupContentSize.height -> anchorBounds.top - popupContentSize.height
                    else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
        Box {
            ActionButton("$valueLabel  ▾", onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width })
            if (expanded) {
                Popup(popupPositionProvider = position, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp), shadowElevation = 0.dp, tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, Line),
                        modifier = Modifier.width(with(density) { anchorWidth.toDp() }).heightIn(max = 340.dp),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            options.forEach { (value, title) ->
                                Text(title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().semantics { selected = valueLabel == title; role = Role.RadioButton }
                                        .background(if (valueLabel == title) Panel else MaterialTheme.colorScheme.surface)
                                        .plainClick { onSelect(value); expanded = false }.padding(horizontal = 16.dp, vertical = 14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun numberText(value: Long?, divisor: Double): String {
    if (value == null) return ""
    return java.math.BigDecimal.valueOf(value).divide(java.math.BigDecimal.valueOf(divisor), 6, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}

private fun parseBound(text: String, multiplier: Double, label: String, original: Long? = null): Long? {
    if (text.isBlank()) return null
    // The displayed decimal is shortened for editing; an untouched persisted value stays exact.
    if (original != null && original >= 0 && text == numberText(original, multiplier)) return original
    val number = text.trim().toDoubleOrNull()
    require(number != null && number.isFinite() && number >= 0) { "$label: 0 이상의 숫자를 입력해 주세요." }
    val scaled = number * multiplier
    require(scaled.isFinite() && scaled < Long.MAX_VALUE.toDouble()) { "$label 값이 너무 큽니다." }
    return scaled.toLong()
}

private fun checkOrder(minimum: Long?, maximum: Long?, label: String) {
    require(minimum == null || maximum == null || minimum <= maximum) { "$label: 최소값이 최대값보다 클 수 없습니다." }
}

private fun dateBasisHelp(field: DateField): String = when (field) {
    DateField.CAPTURED -> "촬영 정보가 있으면 그 날짜를 사용하고, 없으면 기기에 추가된 날짜를 사용합니다."
    DateField.ADDED -> "기기 미디어 목록에 추가된 날짜입니다. 원본 촬영일과 다를 수 있습니다."
    DateField.MODIFIED -> "파일이 마지막으로 수정된 날짜입니다. 촬영일과 다를 수 있습니다."
}

private fun matchingDatePreset(from: Long?, to: Long?): String {
    if (from == null && to == null) return "all"
    if (from == null || to == null) return "custom"
    val start = dateText(from)
    val end = dateText(to)
    val today = Calendar.getInstance()
    val now = dateText(today.timeInMillis)
    val last7 = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -6) }
    val last30 = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -29) }
    val firstOfMonth = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    val previous = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, -1)
    }
    val previousStart = dateText(previous.timeInMillis)
    previous.set(Calendar.DAY_OF_MONTH, previous.getActualMaximum(Calendar.DAY_OF_MONTH))
    val previousEnd = dateText(previous.timeInMillis)
    return when {
        start == now && end == now -> "today"
        start == dateText(last7.timeInMillis) && end == now -> "7"
        start == dateText(last30.timeInMillis) && end == now -> "30"
        start == dateText(firstOfMonth.timeInMillis) && end == now -> "month"
        start == previousStart && end == previousEnd -> "previous"
        else -> "custom"
    }
}

private fun dateText(value: Long?): String = value?.let {
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it))
}.orEmpty()

private fun parseDateBound(text: String, endOfDay: Boolean): Long? {
    if (text.isBlank()) return null
    val value = text.trim()
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "날짜는 yyyy-MM-dd 형식으로 입력해 주세요." }
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
    val position = ParsePosition(0)
    val parsed = parser.parse(value, position)
    require(parsed != null && position.index == value.length && parser.format(parsed) == value) { "존재하는 날짜를 입력해 주세요." }
    val calendar = Calendar.getInstance().apply { time = parsed }
    if (endOfDay) {
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        calendar.add(Calendar.MILLISECOND, -1)
    }
    return calendar.timeInMillis
}

private fun dateFieldLabel(value: DateField): String = when (value) {
    DateField.CAPTURED -> "촬영일"
    DateField.ADDED -> "추가일"
    DateField.MODIFIED -> "수정일"
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
