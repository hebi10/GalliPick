package com.hebi.gallipick.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

private const val MIB = 1_048_576.0

/** A filter is applied only after every visible bound has passed validation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterDialog(
    current: MediaFilter,
    items: List<MediaItem>,
    onApply: (MediaFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember(current) { mutableStateOf(current.kind) }
    var query by remember(current) { mutableStateOf(current.query) }
    var folder by remember(current) { mutableStateOf(current.folder) }
    var mimeType by remember(current) { mutableStateOf(current.mimeType) }
    var minSize by remember(current) { mutableStateOf(numberText(current.minSizeBytes, MIB)) }
    var maxSize by remember(current) { mutableStateOf(numberText(current.maxSizeBytes, MIB)) }
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
    val folders = remember(items, current.folder) {
        (items.map { it.relativePath }.filter { it.isNotBlank() } + current.folder)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val mimeTypes = remember(items, current.mimeType) {
        (items.map { it.mimeType }.filter { it.isNotBlank() } + current.mimeType)
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(.93f),
            shape = RectangleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("파일 필터", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FilterSection("미디어 유형")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChoice("전체", kind == null) { kind = null }
                        FilterChoice("사진", kind == MediaKind.PHOTO) { kind = MediaKind.PHOTO }
                        FilterChoice("동영상", kind == MediaKind.VIDEO) { kind = MediaKind.VIDEO }
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, label = { Text("파일 이름 검색") },
                        singleLine = true, shape = RectangleShape, modifier = Modifier.fillMaxWidth(),
                    )
                    FilterDropdown("폴더", folder.ifBlank { "모든 폴더" }, listOf("" to "모든 폴더") + folders.map { it to it }) { folder = it }
                    FilterDropdown("파일 형식", mimeType.ifBlank { "모든 형식" }, listOf("" to "모든 형식") + mimeTypes.map { it to it }) { mimeType = it }
                    FilterSection("파일 크기 · MiB")
                    FilterBounds(minSize, { minSize = it }, maxSize, { maxSize = it }, "최소 MiB", "최대 MiB")
                    if (kind == MediaKind.VIDEO) {
                        FilterSection("동영상 길이 · 분")
                        FilterBounds(minDuration, { minDuration = it }, maxDuration, { maxDuration = it }, "최소 분", "최대 분")
                    } else {
                        Text("길이 조건은 동영상 유형에서 설정할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    FilterSection("날짜")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField.entries.forEach { field -> FilterChoice(dateFieldLabel(field), dateField == field) { dateField = field } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = fromDate, onValueChange = { fromDate = it }, label = { Text("시작일") },
                            placeholder = { Text("yyyy-MM-dd") }, singleLine = true, shape = RectangleShape,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        OutlinedTextField(
                            value = toDate, onValueChange = { toDate = it }, label = { Text("종료일") },
                            placeholder = { Text("yyyy-MM-dd") }, singleLine = true, shape = RectangleShape,
                            modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                    }
                    Text("날짜는 yyyy-MM-dd 형식이며, 종료일 전체를 포함합니다.", style = MaterialTheme.typography.bodySmall)
                    FilterSection("화면 방향")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChoice("전체", orientation == null) { orientation = null }
                        Orientation.entries.forEach { value -> FilterChoice(orientationLabel(value), orientation == value) { orientation = value } }
                    }
                    FilterSection("최소 해상도")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "전체", 921_600L to "HD", 2_073_600L to "FHD", 3_686_400L to "QHD", 8_294_400L to "4K", 33_177_600L to "8K", 1_000_000L to "1 MP", 4_000_000L to "4 MP", 8_000_000L to "8 MP", 12_000_000L to "12 MP").forEach { (pixels, label) ->
                            FilterChoice(label, minPixels == pixels) { minPixels = pixels }
                        }
                        if (minPixels != null && minPixels !in listOf(921_600L, 2_073_600L, 3_686_400L, 8_294_400L, 33_177_600L, 1_000_000L, 4_000_000L, 8_000_000L, 12_000_000L)) {
                            FilterChoice("${numberText(minPixels, 1_000_000.0)} MP", true) { }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.plainClick { favoritesOnly = !favoritesOnly }) {
                            SelectBox(checked = favoritesOnly, description = "즐겨찾기만", onClick = { favoritesOnly = !favoritesOnly })
                            Text("즐겨찾기만", Modifier.padding(top = 13.dp))
                        }
                        Row(Modifier.plainClick { protectedOnly = !protectedOnly }) {
                            SelectBox(checked = protectedOnly, description = "보호된 파일만", onClick = { protectedOnly = !protectedOnly })
                            Text("보호된 파일만", Modifier.padding(top = 13.dp))
                        }
                    }
                    FilterSection("정렬")
                    FilterDropdown("정렬 기준", sortFieldLabel(sortField), SortField.entries.map { it to sortFieldLabel(it) }) { sortField = it }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChoice("내림차순", sortDirection == SortDirection.DESCENDING) { sortDirection = SortDirection.DESCENDING }
                        FilterChoice("오름차순", sortDirection == SortDirection.ASCENDING) { sortDirection = SortDirection.ASCENDING }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextAction("초기화", onClick = { onApply(MediaFilter()); onDismiss() })
                    TextAction("취소", onClick = onDismiss)
                    ActionButton(
                        label = "필터 적용", filled = true,
                        onClick = {
                            try {
                                val minimumSize = parseBound(minSize, MIB, "최소 크기")
                                val maximumSize = parseBound(maxSize, MIB, "최대 크기")
                                checkOrder(minimumSize, maximumSize, "파일 크기")
                                val minimumDuration = if (kind == MediaKind.VIDEO) parseBound(minDuration, 60_000.0, "최소 길이") else null
                                val maximumDuration = if (kind == MediaKind.VIDEO) parseBound(maxDuration, 60_000.0, "최대 길이") else null
                                checkOrder(minimumDuration, maximumDuration, "동영상 길이")
                                val from = parseDateBound(fromDate, false)
                                val to = parseDateBound(toDate, true)
                                checkOrder(from, to, "날짜")
                                onApply(current.copy(
                                    kind = kind, query = query.trim(), folder = folder, mimeType = mimeType,
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
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSection(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        color = if (selected) colors.onPrimary else colors.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.sizeIn(minHeight = 44.dp)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outline)
            .plainClick(action = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
    )
}

@Composable
private fun FilterBounds(
    minimum: String, onMinimum: (String) -> Unit,
    maximum: String, onMaximum: (String) -> Unit,
    minimumLabel: String, maximumLabel: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = minimum, onValueChange = onMinimum, label = { Text(minimumLabel) },
            singleLine = true, shape = RectangleShape, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            value = maximum, onValueChange = onMaximum, label = { Text(maximumLabel) },
            singleLine = true, shape = RectangleShape, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
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
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
        Box {
            ActionButton("$valueLabel  ▾", onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width })
            if (expanded) {
                Popup(popupPositionProvider = position, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                    Surface(
                        shape = RectangleShape, shadowElevation = 0.dp, tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.width(with(density) { anchorWidth.toDp() }).heightIn(max = 340.dp),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            options.forEach { (value, title) ->
                                Text(title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().semantics { selected = valueLabel == title; role = Role.RadioButton }
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

private fun parseBound(text: String, multiplier: Double, label: String): Long? {
    if (text.isBlank()) return null
    val number = text.trim().toDoubleOrNull()
    require(number != null && number.isFinite() && number >= 0) { "$label: 0 이상의 숫자를 입력해 주세요." }
    val scaled = number * multiplier
    require(scaled.isFinite() && scaled < Long.MAX_VALUE.toDouble()) { "$label 값이 너무 큽니다." }
    return scaled.toLong()
}

private fun checkOrder(minimum: Long?, maximum: Long?, label: String) {
    require(minimum == null || maximum == null || minimum <= maximum) { "$label: 최소값이 최대값보다 클 수 없습니다." }
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
