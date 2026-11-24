package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.DateField
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterDateRange(
    dateField: DateField,
    fromDate: String,
    toDate: String,
    quickDateSelection: String,
    onDateFieldChange: (DateField) -> Unit,
    onPresetSelected: (from: String, to: String, preset: String) -> Unit,
    onFromDateChange: (String) -> Unit,
    onToDateChange: (String) -> Unit,
    onOpenCustomDate: () -> Unit,
    onOpenStartDate: () -> Unit,
    onOpenEndDate: () -> Unit,
) {
    FilterGroup("날짜 범위") {
        Text("${dateFieldLabel(dateField)} 기준", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(dateBasisHelp(dateField), color = Muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateField.entries.forEach { field ->
                FilterChoice(dateFieldLabel(field), dateField == field) { onDateFieldChange(field) }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChoice("전체 기간", fromDate.isBlank() && toDate.isBlank()) {
                onPresetSelected("", "", "all")
            }
            FilterChoice("오늘", quickDateSelection == "today") {
                val today = dateText(Calendar.getInstance().timeInMillis)
                onPresetSelected(today, today, "today")
            }
            FilterChoice("최근 7일", quickDateSelection == "7") {
                val end = Calendar.getInstance()
                val endDate = dateText(end.timeInMillis)
                end.add(Calendar.DAY_OF_MONTH, -6)
                onPresetSelected(dateText(end.timeInMillis), endDate, "7")
            }
            FilterChoice("최근 30일", quickDateSelection == "30") {
                val end = Calendar.getInstance()
                val endDate = dateText(end.timeInMillis)
                end.add(Calendar.DAY_OF_MONTH, -29)
                onPresetSelected(dateText(end.timeInMillis), endDate, "30")
            }
            FilterChoice("이번 달", quickDateSelection == "month") {
                val today = Calendar.getInstance()
                val endDate = dateText(today.timeInMillis)
                today.set(Calendar.DAY_OF_MONTH, 1)
                onPresetSelected(dateText(today.timeInMillis), endDate, "month")
            }
            FilterChoice("지난 달", quickDateSelection == "previous") {
                val previous = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    add(Calendar.MONTH, -1)
                }
                val startDate = dateText(previous.timeInMillis)
                previous.set(Calendar.DAY_OF_MONTH, previous.getActualMaximum(Calendar.DAY_OF_MONTH))
                onPresetSelected(startDate, dateText(previous.timeInMillis), "previous")
            }
            FilterChoice("직접 선택", quickDateSelection == "custom" && (fromDate.isNotBlank() || toDate.isNotBlank())) {
                onOpenCustomDate()
            }
        }
        FilterFieldPair(
            first = { fieldModifier ->
                FilterDateInput("시작일", fromDate, onFromDateChange, fieldModifier, onOpenStartDate)
            },
            second = { fieldModifier ->
                FilterDateInput("종료일", toDate, onToDateChange, fieldModifier, onOpenEndDate)
            },
        )
        Text(
            "달력에서 날짜를 선택하거나 직접 입력하세요. 종료일은 해당 날짜 전체를 포함합니다.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
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
        IconAction("$label 달력에서 선택", Icons.Outlined.CalendarMonth, onClick = onOpenCalendar)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterCalendarPanel(
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

private fun dateBasisHelp(field: DateField): String = when (field) {
    DateField.CAPTURED -> "촬영 정보가 있으면 그 날짜를 사용하고, 없으면 기기에 추가된 날짜를 사용합니다."
    DateField.ADDED -> "기기 미디어 목록에 추가된 날짜입니다. 원본 촬영일과 다를 수 있습니다."
    DateField.MODIFIED -> "파일이 마지막으로 수정된 날짜입니다. 촬영일과 다를 수 있습니다."
}

internal fun matchingDatePreset(from: Long?, to: Long?): String {
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

internal fun dateText(value: Long?): String = value?.let {
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(it))
}.orEmpty()

internal fun parseDateBound(text: String, endOfDay: Boolean): Long? {
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
