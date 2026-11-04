package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).semantics { contentDescription = "파일 도구 모음" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Row(
                Modifier.heightIn(min = 48.dp).background(
                    if (filter.query.isNotBlank()) Mint else MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(14.dp),
                ).semantics {
                    contentDescription = "파일 검색"
                    stateDescription = filter.query.ifBlank { "검색어 없음" }
                    role = Role.Button
                }.plainClick { menu = "search" }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GalliIcon(Icons.Rounded.Search, null, Modifier.size(20.dp))
                Text("검색", style = MaterialTheme.typography.labelLarge, color = Ink)
            }
            DropdownMenu(expanded = menu == "search", onDismissRequest = { menu = null; keyboard?.hide() },
                modifier = Modifier.widthIn(min = 260.dp, max = 380.dp), properties = PopupProperties(focusable = true)) {
                OutlinedTextField(
                    value = filter.query, onValueChange = { change(filter.copy(query = it)) },
                    label = { Text("파일 이름 또는 폴더 검색") },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(280.dp).focusRequester(focus)
                        .semantics { contentDescription = "파일 이름 또는 폴더 검색" },
                    singleLine = true,
                    trailingIcon = { if (filter.query.isNotEmpty()) IconAction("검색어 지우기", Icons.Rounded.Close) { change(filter.copy(query = "")) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { menu = null; keyboard?.hide() }),
                )
                TextAction("검색 완료", { menu = null; keyboard?.hide() }, Modifier.align(Alignment.End))
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        Box {
            ToolbarChoice(kindLabel(filter.kind), "파일 유형 선택", kindLabel(filter.kind), filter.kind != null) { menu = "kind" }
            DropdownMenu(expanded = menu == "kind", onDismissRequest = { menu = null }) {
                listOf(null, MediaKind.PHOTO, MediaKind.VIDEO).forEach { kind ->
                    DropdownMenuItem(text = { Text(kindLabel(kind)) }, onClick = {
                        change(MediaFilterActions.kind(filter, kind)); menu = null
                    }, trailingIcon = { if (filter.kind == kind) GalliIcon(Icons.Rounded.Check, null) })
                }
            }
        }
        Box {
            ToolbarChoice(if (conditionCount == 0) "필터" else "필터 $conditionCount", "파일 조건 선택", filterSummary(filter).ifBlank { "조건 없음" }, conditionCount > 0) { menu = "conditions" }
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
            DropdownMenu(expanded = menu == "sort", onDismissRequest = { menu = null }, modifier = Modifier.heightIn(max = 420.dp)) {
                SortChoices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                        change(MediaFilterActions.sort(filter, choice.field, choice.direction)); menu = null
                    }, trailingIcon = { if (filter.sortField == choice.field && filter.sortDirection == choice.direction) GalliIcon(Icons.Rounded.Check, null) })
                }
            }
        }
        Box {
            IconAction("파일 추가 작업", Icons.Rounded.MoreVert) { menu = "more" }
            DropdownMenu(expanded = menu == "more", onDismissRequest = { menu = null }) {
                DropdownMenuItem(text = { Text("현재 조건 저장") }, onClick = { menu = null; save() })
                DropdownMenuItem(text = { Text("조건 초기화") }, onClick = { change(MediaFilter()); menu = null })
            }
        }
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
