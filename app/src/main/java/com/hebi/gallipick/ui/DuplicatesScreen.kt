package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*

internal data class DuplicatesScreenActions(
    val startScan: () -> Unit,
    val pauseScan: () -> Unit,
    val resumeScan: () -> Unit,
    val selectRecommended: () -> Unit,
    val toggleSelection: (String) -> Unit,
    val clearSelection: () -> Unit,
    val chooseKeeper: (DuplicateGroup, String) -> Unit,
    val setProtected: (MediaItem, Boolean) -> Unit,
    val refresh: () -> Unit,
    val selectGroupCandidates: (DuplicateGroup) -> Unit = {},
)

@Composable
internal fun DuplicatesScreen(state: AppUiState, vm: AppViewModel, trash: (List<MediaItem>) -> Unit, open: (MediaItem) -> Unit, requestAccess: () -> Unit) {
    DuplicatesScreenContent(state, trash, open, requestAccess, DuplicatesScreenActions(
        vm::startScan, vm::pauseScan, vm::resumeScan, vm::selectRecommendedDuplicates,
        vm::toggleDuplicateCandidateSelection, vm::clearSelection, vm::chooseDuplicateKeeper,
        { item, protected -> vm.setProtected(item, protected) }, vm::refresh, vm::selectDuplicateGroupCandidates,
    ))
}

@Composable
internal fun DuplicatesScreenContent(
    state: AppUiState,
    trash: (List<MediaItem>) -> Unit,
    open: (MediaItem) -> Unit,
    requestAccess: () -> Unit,
    actions: DuplicatesScreenActions,
) {
    var compareHash by rememberSaveable { mutableStateOf<String?>(null) }
    var initialCompareUri by rememberSaveable { mutableStateOf<String?>(null) }
    var retainedComparison by remember { mutableStateOf<DuplicateGroup?>(null) }
    var closedPendingHash by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val catalogVisible = !state.loading && state.access != MediaAccess.NONE && state.catalogError == null
    val liveItems = remember(state.items, catalogVisible) {
        if (!catalogVisible) emptyMap() else state.items.filterNot { it.isTrashed }.associateBy { it.uri }
    }
    val groups = remember(state.duplicates, liveItems) {
        DuplicateReviewPolicy.liveGroups(state.duplicates, liveItems.values.toList())
    }
    val rolesByHash = remember(groups, state.duplicateKeepers, state.protectedFolders) {
        groups.associate { it.hash to duplicateRoles(it, state.duplicateKeepers[it.hash], state.protectedFolders) }
    }
    val candidates = DuplicateReviewPolicy.candidates(groups, liveItems.values.toList(), state.duplicateKeepers, state.protectedFolders)
    val selected = candidates.filter { it.uri in state.selected }
    val reviewHash = compareHash ?: closedPendingHash
    val authoritativeComparison = groups.firstOrNull { it.hash == reviewHash }
    val retainedLive = retainedComparison?.takeIf { it.hash == reviewHash }?.let { group ->
        DuplicateReviewPolicy.liveGroups(listOf(group), liveItems.values.toList()).singleOrNull()
    }
    val comparison = if (catalogVisible && compareHash != null) authoritativeComparison ?: retainedLive else null
    val checkingComparison = catalogVisible && reviewHash != null && authoritativeComparison == null && retainedLive != null
    LaunchedEffect(reviewHash, authoritativeComparison) {
        if (authoritativeComparison != null) {
            if (compareHash != null) retainedComparison = authoritativeComparison
            else { closedPendingHash = null; retainedComparison = null }
        }
    }
    LaunchedEffect(state.access, state.catalogError, state.loading, reviewHash, authoritativeComparison, retainedLive) {
        if (state.loading && compareHash == null) {
            closedPendingHash = null
            retainedComparison = null
        }
        if (state.access == MediaAccess.NONE || state.catalogError != null || (!state.loading && reviewHash != null && authoritativeComparison == null && retainedLive == null)) {
            compareHash = null
            closedPendingHash = null
            retainedComparison = null
        }
    }
    fun compare(group: DuplicateGroup, uri: String? = null) {
        closedPendingHash = null
        compareHash = group.hash
        initialCompareUri = uri
        retainedComparison = group
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (catalogVisible && !checkingComparison) {
            LazyColumn(
                modifier = Modifier.weight(1f).semantics { contentDescription = "중복 파일 목록" },
                state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
            ) {
                item("intro") { DuplicateIntro() }
                item("scan") { DuplicateScanSummary(state.scan, liveItems.isNotEmpty(), actions) }
                if (groups.isEmpty()) {
                    item("empty") { EmptyState(
                        if (state.scan.phase == ScanPhase.COMPLETE) "확인된 중복 파일이 없습니다" else "중복 사본을 찾아보세요",
                        if (state.scan.phase == ScanPhase.COMPLETE) "접근이 허용된 미디어에서 검사한 결과입니다." else "검사를 시작하면 같은 내용의 파일이 묶여 표시됩니다.", Icons.Rounded.CopyAll,
                    ) }
                } else {
                    item("selection") {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${groups.size}개 묶음 · 정리 후보 ${candidates.size}개", style = MaterialTheme.typography.titleMedium)
                            Text("후보 파일 용량 ${bytesLabel(duplicateBytes(candidates))}", style = MaterialTheme.typography.bodySmall, color = Muted)
                            ActionButton(duplicateSelectionLabel(candidates.size), actions.selectRecommended,
                                Modifier.fillMaxWidth().semantics { contentDescription = "중복 정리 후보 선택" }, enabled = candidates.isNotEmpty())
                            Text("현재 결과의 모든 묶음에서 선택합니다. 남길 파일·보호 파일·즐겨찾기는 제외합니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
                            Text("선택만으로는 이동하지 않습니다. 아래 휴지통 이동 버튼에서 확인합니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
                            if (candidates.size > SelectionPolicy.MAX_SELECTION_SIZE) Text("전체 ${candidates.size}개 중 한 번에 최대 500개를 선택합니다.", style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                    groups.forEachIndexed { index, group ->
                        val roles = rolesByHash.getValue(group.hash)
                        val selectedInGroup = roles.candidates.count { it.uri in state.selected }
                        item("group:${group.hash}") {
                            Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("묶음 ${index + 1} · 같은 ${if (group.items.all { it.kind == MediaKind.VIDEO }) "영상" else "파일"} ${group.items.size}개", style = MaterialTheme.typography.titleMedium)
                                        Text("남김 ${roles.kept.size}개 · 정리 후보 ${roles.candidates.size}개", style = MaterialTheme.typography.bodySmall, color = Muted)
                                    }
                                    TextAction("비교", { compare(group) }, Modifier.semantics { contentDescription = "중복 묶음 ${index + 1} 비교" })
                                }
                                ActionButton(duplicateGroupSelectionLabel(roles.candidates.size, selectedInGroup, selected.size),
                                    { actions.selectGroupCandidates(group) }, Modifier.fillMaxWidth(),
                                    enabled = selectedInGroup < roles.candidates.size && selected.size < SelectionPolicy.MAX_SELECTION_SIZE)
                            }
                        }
                        item("kept:${group.hash}") { Text("남길 파일 ${roles.kept.size}개", style = MaterialTheme.typography.labelLarge, color = Ink) }
                        items(roles.kept, key = { "${group.hash}:${it.uri}" }) { item ->
                            DuplicateFileRow(item, roles, state.selected, { compare(group, item.uri) }, { actions.chooseKeeper(group, item.uri) }, { actions.toggleSelection(item.uri) })
                        }
                        if (roles.candidates.isNotEmpty()) {
                            item("candidates:${group.hash}") { Text("정리 후보 ${roles.candidates.size}개 · ${bytesLabel(duplicateBytes(roles.candidates))}", style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.padding(top = 6.dp)) }
                            items(roles.candidates, key = { "${group.hash}:${it.uri}" }) { item ->
                                DuplicateFileRow(item, roles, state.selected, { compare(group, item.uri) }, { actions.chooseKeeper(group, item.uri) }, { actions.toggleSelection(item.uri) })
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
                item { DuplicateIntro() }
                item { DuplicateScanSummary(state.scan, false, actions) }
                item {
                    when {
                        checkingComparison -> EmptyState("중복 결과를 확인하고 있습니다", "남길 파일과 정리 후보를 다시 확인한 뒤 목록을 표시합니다.", Icons.Rounded.CopyAll)
                        state.access == MediaAccess.NONE -> EmptyState("미디어 접근이 필요합니다", "접근을 허용한 사진과 동영상에서 중복 사본을 찾습니다.", Icons.Rounded.CopyAll,
                            action = { ActionButton("접근 허용", requestAccess, filled = true) })
                        state.catalogError != null -> EmptyState("파일을 불러오지 못했습니다", state.catalogError, Icons.Rounded.Info,
                            action = { ActionButton("다시 시도", actions.refresh, filled = true) })
                        else -> EmptyState("미디어를 불러오고 있습니다", "현재 접근 권한으로 파일을 확인하고 있습니다.", Icons.Rounded.CopyAll)
                    }
                }
            }
        }
        if (selected.isNotEmpty()) DuplicateSelectionBar(selected, actions.clearSelection) { trash(selected) }
    }
    if (comparison != null) DuplicateCompareDialog(
        group = comparison, preferredKeeperUri = state.duplicateKeepers[comparison.hash], selectedUris = state.selected,
        protectedFolders = state.protectedFolders, onDismiss = {
            if (checkingComparison) closedPendingHash = compareHash else retainedComparison = null
            compareHash = null
        },
        onOpen = open, onChooseKeeper = actions.chooseKeeper, onToggleSelection = actions.toggleSelection,
        onProtect = actions.setProtected, initialItemUri = initialCompareUri, actionsEnabled = authoritativeComparison != null,
    )
}

@Composable
private fun DuplicateIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("중복 정리", style = MaterialTheme.typography.titleLarge)
        Text("내용이 같은 사본입니다. 남길 파일을 확인하고 나머지를 선택하세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun DuplicateScanSummary(scan: ScanState, canScan: Boolean, actions: DuplicatesScreenActions) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(scanTitle(scan), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (scan.total > 0) Text("${scan.completed} / ${scan.total}개 · 건너뜀 ${scan.skipped}개", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            when (scan.phase) {
                ScanPhase.RUNNING, ScanPhase.QUEUED -> ActionButton("일시정지", actions.pauseScan, icon = Icons.Rounded.Pause)
                ScanPhase.PAUSED -> ActionButton("계속 검사", actions.resumeScan, filled = true, icon = Icons.Rounded.PlayArrow)
                else -> ActionButton(if (scan.phase == ScanPhase.COMPLETE) "다시 검사" else "검사 시작", actions.startScan, filled = true, enabled = canScan, icon = Icons.Rounded.Search)
            }
        }
        if (scan.total > 0 || scan.phase == ScanPhase.RUNNING || scan.phase == ScanPhase.QUEUED) {
            val processed = (scan.completed.toLong() + scan.skipped).coerceIn(0L, scan.total.coerceAtLeast(0).toLong())
            val progress = if (scan.total > 0) processed.toFloat() / scan.total else 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(12.dp)).background(Line).semantics {
                contentDescription = "중복 검사 진행률"
                progressBarRangeInfo = if (scan.total > 0) ProgressBarRangeInfo(progress, 0f..1f) else ProgressBarRangeInfo.Indeterminate
                stateDescription = if (scan.total > 0) "전체 ${scan.total}개 중 ${processed}개 처리" else "검사할 파일을 확인하고 있습니다"
            }) {
                if (progress > 0) Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Ink, RoundedCornerShape(12.dp)))
            }
        }
        if (scan.message.isNotBlank()) Text(scan.message, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun DuplicateFileRow(item: MediaItem, roles: DuplicateRoles, selected: Set<String>, compare: () -> Unit, chooseKeeper: () -> Unit, toggle: () -> Unit) {
    val candidate = item.uri in roles.candidateUris
    val chosen = candidate && item.uri in selected
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, if (chosen) Ink else Line, shape).padding(10.dp)
        .semantics { contentDescription = "${item.displayName}, ${item.relativePath}, ${roles.label(item, selected)}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MediaThumbnail(item, Modifier.size(60.dp).plainClick(action = compare))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(roles.label(item, selected), Modifier.background(
                if (chosen) MaterialTheme.colorScheme.primary else if (!candidate) Mint else Panel, RoundedCornerShape(8.dp),
            ).padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                color = if (chosen) MaterialTheme.colorScheme.onPrimary else Ink)
            Column(Modifier.fillMaxWidth().plainClick(action = compare), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(item.relativePath.trimEnd('/').ifEmpty { "기본 폴더" }, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(bytesLabel(item.sizeBytes) + if (item.kind == MediaKind.VIDEO) " · ${durationLabel(item.durationMs)}" else "", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (!candidate) Text(roles.reason(item), style = MaterialTheme.typography.bodySmall, color = Muted)
            if (item.uri != roles.keeper.uri) TextAction("이 파일 남기기", chooseKeeper, Modifier.semantics { contentDescription = "${item.displayName} 남길 파일로 지정" })
        }
        if (candidate) SelectBox(chosen, description = "${item.displayName}, ${item.relativePath} 정리 후보 선택", onClick = toggle)
        else GalliIcon(if (item.isProtected || item.hasFolderProtection(roles.protectedFolders)) Icons.Rounded.Lock else Icons.Rounded.CheckCircle,
            "남길 파일", Modifier.size(24.dp), tint = Ink)
    }
}

@Composable
private fun DuplicateSelectionBar(selected: List<MediaItem>, clear: () -> Unit, trash: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(16.dp), color = Panel) {
        BoxWithConstraints(Modifier.padding(10.dp)) {
            if (maxWidth >= 620.dp) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${selected.size}개 선택 · 파일 용량 ${bytesLabel(duplicateBytes(selected))}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                TextAction("선택 해제", clear)
                ActionButton("선택한 ${selected.size}개 휴지통으로 이동", trash, filled = true, icon = Icons.Rounded.DeleteOutline)
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size}개 선택 · ${bytesLabel(duplicateBytes(selected))}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    TextAction("선택 해제", clear)
                }
                ActionButton("선택한 ${selected.size}개 휴지통으로 이동", trash, Modifier.fillMaxWidth(), filled = true, icon = Icons.Rounded.DeleteOutline)
            }
        }
    }
}

private fun scanTitle(scan: ScanState): String = when (scan.phase) {
    ScanPhase.IDLE -> "아직 검사하지 않았습니다"
    ScanPhase.QUEUED -> "검사를 준비하고 있습니다"
    ScanPhase.RUNNING -> "중복 파일을 검사하고 있습니다"
    ScanPhase.PAUSED -> "검사를 일시정지했습니다"
    ScanPhase.COMPLETE -> "검사를 마쳤습니다"
    ScanPhase.FAILED -> "검사를 완료하지 못했습니다"
}
