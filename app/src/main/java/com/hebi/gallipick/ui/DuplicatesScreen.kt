package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
@OptIn(ExperimentalLayoutApi::class)
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
    // Keep the URI-keyed viewport while the catalog is temporarily revalidated.
    val listState = rememberLazyGridState()
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shortResults = maxHeight < 420.dp && maxWidth >= 720.dp && groups.isNotEmpty()
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (catalogVisible && !checkingComparison) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (shortResults) 2 else 1),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "중복 파일 목록" },
                    state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (shortResults) 4.dp else 8.dp),
                    contentPadding = PaddingValues(top = if (shortResults) 4.dp else 12.dp, bottom = if (shortResults) 8.dp else 16.dp),
                ) {
                    if (shortResults) {
                        item("intro", span = { GridItemSpan(maxLineSpan) }) {
                            CompactDuplicateSummary(state.scan, groups.size, candidates, liveItems.isNotEmpty(), actions)
                        }
                    } else {
                        item("intro", span = { GridItemSpan(maxLineSpan) }) { DuplicateIntro() }
                        item("scan", span = { GridItemSpan(maxLineSpan) }) { DuplicateScanSummary(state.scan, liveItems.isNotEmpty(), actions) }
                    }
                    if (groups.isEmpty()) {
                        item("empty", span = { GridItemSpan(maxLineSpan) }) { EmptyState(
                            if (state.scan.phase == ScanPhase.COMPLETE) "확인된 중복 파일이 없습니다" else "중복 사본을 찾아보세요",
                            if (state.scan.phase == ScanPhase.COMPLETE) "접근이 허용된 미디어에서 검사한 결과입니다." else "검사를 시작하면 같은 내용의 파일이 묶여 표시됩니다.", Icons.Rounded.CopyAll,
                            scene = if (state.scan.phase == ScanPhase.COMPLETE) GalliScene.COMPLETE else GalliScene.SEARCH,
                        ) }
                    } else {
                        if (!shortResults) item("selection", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Column(Modifier.weight(1f).widthIn(min = 130.dp).align(Alignment.CenterVertically)) {
                                        Text("${groups.size}개 묶음 · 후보 ${candidates.size}개", style = MaterialTheme.typography.titleSmall)
                                        Text(bytesLabel(duplicateBytes(candidates)), style = MaterialTheme.typography.bodySmall, color = Muted)
                                    }
                                    ActionButton(duplicateSelectionLabel(candidates.size), actions.selectRecommended,
                                        Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "중복 정리 후보 선택" }, enabled = candidates.isNotEmpty())
                                }
                                Text("남길 파일·보호 파일·즐겨찾기 제외", style = MaterialTheme.typography.bodySmall, color = Muted)
                                if (candidates.size > SelectionPolicy.MAX_SELECTION_SIZE) Text("전체 ${candidates.size}개 중 한 번에 최대 500개를 선택합니다.", style = MaterialTheme.typography.labelMedium, color = Muted)
                            }
                        }
                        groups.forEachIndexed { index, group ->
                            val roles = rolesByHash.getValue(group.hash)
                            val selectedInGroup = roles.candidates.count { it.uri in state.selected }
                            item("group:${group.hash}", span = { GridItemSpan(maxLineSpan) }) {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(top = if (shortResults) 0.dp else 6.dp)
                                        .background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Column(Modifier.weight(1f).widthIn(min = 140.dp).align(Alignment.CenterVertically)) {
                                        Text("묶음 ${index + 1} · ${if (group.items.all { it.kind == MediaKind.VIDEO }) "영상" else "파일"} ${group.items.size}개", style = MaterialTheme.typography.titleSmall)
                                        Text("남김 ${roles.kept.size}개 · 정리 후보 ${roles.candidates.size}개", style = MaterialTheme.typography.bodySmall, color = Muted)
                                    }
                                    ActionButton(duplicateGroupSelectionLabel(roles.candidates.size, selectedInGroup, selected.size),
                                        { actions.selectGroupCandidates(group) }, Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "중복 묶음 ${index + 1} 후보 선택" },
                                        enabled = selectedInGroup < roles.candidates.size && selected.size < SelectionPolicy.MAX_SELECTION_SIZE)
                                    TextAction("비교", { compare(group) }, Modifier.align(Alignment.CenterVertically).semantics { contentDescription = "중복 묶음 ${index + 1} 비교" })
                                }
                            }
                            items(roles.kept, key = { "${group.hash}:${it.uri}" }) { item ->
                                DuplicateFileRow(item, roles, state.selected, { compare(group, item.uri) }, { actions.chooseKeeper(group, item.uri) }, { actions.toggleSelection(item.uri) }, shortResults)
                            }
                            if (roles.candidates.isNotEmpty()) {
                                items(roles.candidates, key = { "${group.hash}:${it.uri}" }) { item ->
                                    DuplicateFileRow(item, roles, state.selected, { compare(group, item.uri) }, { actions.chooseKeeper(group, item.uri) }, { actions.toggleSelection(item.uri) }, shortResults)
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
            if (selected.isNotEmpty()) DuplicateSelectionBar(selected, actions.clearSelection, shortResults) { trash(selected) }
        }
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
private fun CompactDuplicateSummary(scan: ScanState, groupCount: Int, candidates: List<MediaItem>, canScan: Boolean, actions: DuplicatesScreenActions) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics {
                if (scan.phase == ScanPhase.COMPLETE) contentDescription = "중복 검사 완료 요약"
                liveRegion = LiveRegionMode.Polite
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("중복 정리", style = MaterialTheme.typography.titleSmall)
                    Text("${groupCount}개 묶음 · 후보 ${candidates.size}개", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val status = if (scan.phase == ScanPhase.COMPLETE) "검사 완료 · ${scan.completed}개 확인" + if (scan.skipped > 0) " · ${scan.skipped}개 건너뜀" else ""
                    else scanTitle(scan) + if (scan.total > 0) " · ${scan.completed} / ${scan.total}개" else ""
                Text("${bytesLabel(duplicateBytes(candidates))} · $status", style = MaterialTheme.typography.bodySmall, color = Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ActionButton(duplicateSelectionLabel(candidates.size), actions.selectRecommended,
                Modifier.semantics { contentDescription = "중복 정리 후보 선택" }, enabled = candidates.isNotEmpty())
            when (scan.phase) {
                ScanPhase.RUNNING, ScanPhase.QUEUED -> ActionButton("일시정지", actions.pauseScan, icon = Icons.Rounded.Pause)
                ScanPhase.PAUSED -> ActionButton("계속 검사", actions.resumeScan, filled = true, icon = Icons.Rounded.PlayArrow)
                ScanPhase.COMPLETE -> ActionButton("다시 검사", actions.startScan, enabled = canScan)
                else -> ActionButton("검사 시작", actions.startScan, filled = true, enabled = canScan, icon = Icons.Rounded.Search)
            }
        }
        if (scan.phase != ScanPhase.COMPLETE) {
            DuplicateScanProgress(scan)
            if (scan.message.isNotBlank()) Text(scan.message, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (candidates.size > SelectionPolicy.MAX_SELECTION_SIZE) Text("전체 ${candidates.size}개 중 한 번에 최대 500개를 선택합니다.",
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DuplicateScanSummary(scan: ScanState, canScan: Boolean, actions: DuplicatesScreenActions) {
    if (scan.phase == ScanPhase.COMPLETE) {
        FlowRow(Modifier.fillMaxWidth().semantics { contentDescription = "중복 검사 완료 요약" },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("검사 완료 · ${scan.completed}개 확인" + if (scan.skipped > 0) " · ${scan.skipped}개 건너뜀" else "",
                Modifier.weight(1f).widthIn(min = 130.dp).align(Alignment.CenterVertically).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = Muted)
            ActionButton("다시 검사", actions.startScan, Modifier.align(Alignment.CenterVertically), enabled = canScan)
        }
        return
    }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(scanTitle(scan), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (scan.total > 0) Text("${scan.completed} / ${scan.total}개 · 건너뜀 ${scan.skipped}개", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            when (scan.phase) {
                ScanPhase.RUNNING, ScanPhase.QUEUED -> ActionButton("일시정지", actions.pauseScan, icon = Icons.Rounded.Pause)
                ScanPhase.PAUSED -> ActionButton("계속 검사", actions.resumeScan, filled = true, icon = Icons.Rounded.PlayArrow)
                else -> ActionButton("검사 시작", actions.startScan, filled = true, enabled = canScan, icon = Icons.Rounded.Search)
            }
        }
        DuplicateScanProgress(scan)
        if (scan.message.isNotBlank()) Text(scan.message, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun DuplicateScanProgress(scan: ScanState) {
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
}

@Composable
private fun DuplicateFileRow(item: MediaItem, roles: DuplicateRoles, selected: Set<String>, compare: () -> Unit, chooseKeeper: () -> Unit, toggle: () -> Unit, compact: Boolean = false) {
    if (compact) {
        CompactDuplicateFileRow(item, roles, selected, compare, chooseKeeper, toggle)
        return
    }
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
        if (candidate) SelectBox(chosen, description = "${item.displayName}, ${item.relativePath} 정리 후보 선택", onClick = toggle, modifier = Modifier.size(48.dp))
        else Column(Modifier.widthIn(min = 36.dp).semantics { contentDescription = "${item.displayName} 남김" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            GalliIcon(if (item.isProtected || item.hasFolderProtection(roles.protectedFolders)) Icons.Rounded.Lock else Icons.Rounded.PushPin,
                null, Modifier.size(22.dp), tint = Ink)
            Text("남김", style = MaterialTheme.typography.labelSmall, color = Ink)
        }
    }
}

@Composable
private fun CompactDuplicateFileRow(item: MediaItem, roles: DuplicateRoles, selected: Set<String>, compare: () -> Unit, chooseKeeper: () -> Unit, toggle: () -> Unit) {
    val candidate = item.uri in roles.candidateUris
    val chosen = candidate && item.uri in selected
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, if (chosen) Ink else Line, shape).padding(6.dp)
        .semantics { contentDescription = "${item.displayName}, ${item.relativePath}, ${roles.label(item, selected)}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaThumbnail(item, Modifier.size(48.dp).plainClick(action = compare))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(roles.label(item, selected), Modifier.background(
                if (chosen) MaterialTheme.colorScheme.primary else if (!candidate) Mint else Panel, RoundedCornerShape(8.dp),
            ).padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium,
                color = if (chosen) MaterialTheme.colorScheme.onPrimary else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).plainClick(action = compare)) {
                Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(item.relativePath.trimEnd('/').ifEmpty { "기본 폴더" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(bytesLabel(item.sizeBytes) + if (item.kind == MediaKind.VIDEO) " · ${durationLabel(item.durationMs)}" else "", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (candidate) SelectBox(chosen, description = "${item.displayName}, ${item.relativePath} 정리 후보 선택", onClick = toggle, modifier = Modifier.size(48.dp))
            else Column(Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${item.displayName} 남김" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GalliIcon(if (item.isProtected || item.hasFolderProtection(roles.protectedFolders)) Icons.Rounded.Lock else Icons.Rounded.PushPin,
                    null, Modifier.size(20.dp), tint = Ink)
                Text("남김", style = MaterialTheme.typography.labelSmall, color = Ink)
            }
            if (item.uri != roles.keeper.uri) TextAction("남기기", chooseKeeper, Modifier.semantics { contentDescription = "${item.displayName} 남길 파일로 지정" })
        }
    }
}

@Composable
private fun DuplicateSelectionBar(selected: List<MediaItem>, clear: () -> Unit, compact: Boolean = false, trash: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = if (compact) 4.dp else 6.dp), shape = RoundedCornerShape(16.dp), color = Panel) {
        BoxWithConstraints(if (compact) Modifier.padding(horizontal = 8.dp) else Modifier.padding(10.dp)) {
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
