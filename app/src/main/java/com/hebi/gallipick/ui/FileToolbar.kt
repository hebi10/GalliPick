package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hebi.gallipick.domain.*
import java.util.Calendar

@Composable
internal fun FileToolbar(
    filter: MediaFilter,
    presets: List<FilterPreset>,
    change: (MediaFilter) -> Unit,
    advanced: () -> Unit,
    save: () -> Unit,
    deletePreset: (String) -> Unit,
    gridView: Boolean = true,
    onGridViewChange: (Boolean) -> Unit = {},
) {
    var menu by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val oneYearAgo = remember { Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis }
    val quickFilters = remember(oneYearAgo) {
        listOf(
            "10분 이상 영상" to MediaFilter(kind = MediaKind.VIDEO, minDurationMs = 600_000, sortField = SortField.DURATION),
            "1 GB 이상" to MediaFilter(minSizeBytes = 1_073_741_824, sortField = SortField.SIZE),
            "1년 이전 영상" to MediaFilter(kind = MediaKind.VIDEO, toDateMs = oneYearAgo),
        )
    }
    val conditionCount = listOf(
        filter.folder.isNotBlank(), filter.mimeType.isNotBlank(), filter.minSizeBytes != null || filter.maxSizeBytes != null,
        filter.minDurationMs != null || filter.maxDurationMs != null, filter.fromDateMs != null || filter.toDateMs != null,
        filter.minPixels != null, filter.orientation != null, filter.favoritesOnly, filter.protectedOnly,
    ).count { it }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { change(filter.copy(query = it)) },
                modifier = Modifier.weight(1f).focusRequester(focus)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .semantics { contentDescription = "파일 이름 또는 폴더 검색" },
                placeholder = { Text("파일 이름 또는 폴더 검색") },
                singleLine = true,
                shape = RoundedCornerShape(GalliUiMetrics.buttonCorner),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = {
                    IconAction("파일 검색", Icons.Rounded.Search) {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconAction("검색어 지우기", Icons.Rounded.Close) {
                            change(filter.copy(query = ""))
                            focus.requestFocus()
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
            )
            if (searchFocused || filter.query.isNotBlank()) {
                TextAction("검색 완료", {
                    focusManager.clearFocus()
                    keyboard?.hide()
                })
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .semantics { contentDescription = "파일 도구 모음" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Box {
            ToolbarChoice(if (conditionCount == 0) "필터" else "필터 $conditionCount", "파일 조건 선택", filterSummary(filter).ifBlank { "조건 없음" }, conditionCount > 0) { advanced() }
            DropdownMenu(expanded = menu == "conditions", onDismissRequest = { menu = null }, modifier = Modifier.widthIn(min = 260.dp, max = 360.dp).heightIn(max = 420.dp)) {
                DropdownMenuItem(text = { Text("상세 필터") }, onClick = { menu = null; advanced() }, leadingIcon = { GalliIcon(Icons.Rounded.Tune, null) })
                if (conditionCount > 0) Text("적용 중: ${filterSummary(filter)}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                HorizontalDivider()
                Text("빠른 조건", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
                quickFilters.forEach { (label, preset) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { change(preset); menu = null })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("즐겨찾기만") }, onClick = { change(filter.copy(favoritesOnly = !filter.favoritesOnly)); menu = null }, trailingIcon = { if (filter.favoritesOnly) GalliIcon(Icons.Rounded.Check, null) })
                DropdownMenuItem(text = { Text("보호 파일만") }, onClick = { change(filter.copy(protectedOnly = !filter.protectedOnly)); menu = null }, trailingIcon = { if (filter.protectedOnly) GalliIcon(Icons.Rounded.Check, null) })
                HorizontalDivider()
                Text("저장한 조건", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
                if (presets.isEmpty()) Text("저장한 조건이 없습니다.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                presets.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                        change(MediaFilterActions.sort(preset.filter, preset.filter.sortField, preset.filter.sortDirection)); menu = null
                    }, trailingIcon = {
                        IconAction("${preset.name} 필터 삭제", Icons.Rounded.Close, Modifier.size(44.dp)) { deletePreset(preset.id) }
                    })
                }
            }
        }
        Box {
            ToolbarChoice(shortSortLabel(filter), "정렬 기준 선택", sortChoiceLabel(filter), false) { menu = "sort" }
            if (menu == "sort") {
                Dialog(onDismissRequest = { menu = null }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = AppSurface) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            SortChoices.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                    change(MediaFilterActions.sort(filter, choice.field, choice.direction))
                                    menu = null
                                }, trailingIcon = {
                                    if (filter.sortField == choice.field && filter.sortDirection == choice.direction) {
                                        GalliIcon(Icons.Rounded.Check, null)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
        Box {
            IconAction("파일 추가 작업", Icons.Rounded.MoreVert) { menu = "more" }
            DropdownMenu(expanded = menu == "more", onDismissRequest = { menu = null }) {
                DropdownMenuItem(text = { Text("현재 조건 저장") }, onClick = { menu = null; save() })
                DropdownMenuItem(text = { Text("조건 초기화") }, onClick = { change(MediaFilter()); menu = null })
                DropdownMenuItem(text = { Text("빠른 조건·저장한 조건") }, onClick = { menu = "conditions" })
            }
        }
        listOf(null, MediaKind.PHOTO, MediaKind.VIDEO).forEach { kind ->
            FileKindChip(kindLabel(kind), filter.kind == kind) {
                change(MediaFilterActions.kind(filter, kind))
            }
        }
        IconAction(if (gridView) "목록 보기" else "그리드 보기",
            if (gridView) Icons.Rounded.ViewList else Icons.Rounded.GridView
        ) { onGridViewChange(!gridView) }
        }
    }
}

@Composable
private fun FileKindChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.heightIn(min = GalliUiMetrics.touchTarget)
            .semantics {
                selected = isSelected
                role = Role.RadioButton
                contentDescription = "파일 유형: $label"
            }
            .clip(shape)
            .background(if (isSelected) Primary else AppSurface)
            .border(1.dp, if (isSelected) Primary else Line, shape)
            .plainClick(action = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (isSelected) OnPrimary else Ink, style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolbarChoice(label: String, description: String, state: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick,
        modifier = Modifier.height(48.dp).background(if (active) Mint else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .semantics { contentDescription = description; stateDescription = state },
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(label, color = Ink, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        GalliIcon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp), tint = Ink)
    }
}

private fun kindLabel(kind: MediaKind?): String = when (kind) { null -> "전체"; MediaKind.PHOTO -> "사진"; MediaKind.VIDEO -> "동영상" }

internal fun filterSummary(filter: MediaFilter): String = buildList {
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
