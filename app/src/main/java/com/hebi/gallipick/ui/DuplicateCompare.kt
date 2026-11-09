package com.hebi.gallipick.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import java.util.Locale

@Composable
internal fun DuplicateCompareDialog(
    group: DuplicateGroup,
    preferredKeeperUri: String?,
    selectedUris: Set<String>,
    protectedFolders: Set<String>,
    onDismiss: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onChooseKeeper: (DuplicateGroup, String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onProtect: (MediaItem, Boolean) -> Unit,
    initialItemUri: String? = null,
    actionsEnabled: Boolean = true,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            DuplicateCompareContent(group, preferredKeeperUri, selectedUris, protectedFolders, onDismiss, onOpen,
                onChooseKeeper, onToggleSelection, onProtect, Modifier.safeDrawingPadding(), initialItemUri, actionsEnabled)
        }
    }
}

@Composable
internal fun DuplicateCompareContent(
    group: DuplicateGroup,
    preferredKeeperUri: String?,
    selectedUris: Set<String>,
    protectedFolders: Set<String>,
    onDismiss: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onChooseKeeper: (DuplicateGroup, String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onProtect: (MediaItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialItemUri: String? = null,
    actionsEnabled: Boolean = true,
) {
    if (group.items.size < 2) return
    val roles = duplicateRoles(group, preferredKeeperUri, protectedFolders)
    val ordered = remember(group.items, roles) {
        (listOf(roles.keeper) + roles.kept.filterNot { it.uri == roles.keeper.uri } + roles.candidates)
            .distinctBy { it.uri }
    }
    var focusedUri by rememberSaveable(group.hash, initialItemUri) {
        mutableStateOf(initialItemUri?.takeIf { uri -> ordered.any { it.uri == uri } } ?: roles.keeper.uri)
    }
    val focused = ordered.firstOrNull { it.uri == focusedUri } ?: roles.keeper
    val photos = ordered.filter { it.kind == MediaKind.PHOTO }
    var compareAB by rememberSaveable(group.hash) { mutableStateOf(false) }
    var secondUri by rememberSaveable(group.hash) { mutableStateOf(photos.getOrNull(1)?.uri.orEmpty()) }
    var editingSlotA by rememberSaveable(group.hash) { mutableStateOf(false) }
    val firstPhoto = photos.firstOrNull { it.uri == focusedUri } ?: photos.firstOrNull()
    val secondPhoto = photos.firstOrNull { it.uri == secondUri && it.uri != firstPhoto?.uri }
        ?: photos.firstOrNull { it.uri != firstPhoto?.uri }
    val rowState = rememberLazyListState()
    LaunchedEffect(roles.keeper.uri) { rowState.scrollToItem(0) }
    val coordinator = remember(group.hash) { PreviewPlaybackCoordinator() }
    DisposableEffect(coordinator) { onDispose { coordinator.pauseActive() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 420.dp || maxWidth < 350.dp
        val cardWidth = if (compact) 108.dp else if (maxWidth >= 900.dp) 164.dp else 136.dp
        val thumbnailHeight = if (compact) 72.dp else if (maxWidth >= 900.dp) 124.dp else 106.dp
        val previewHeight = when {
            maxHeight < 420.dp -> 180.dp
            maxWidth >= 900.dp -> 380.dp
            maxWidth >= 720.dp -> 320.dp
            else -> 260.dp
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("중복 파일 비교", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (photos.size >= 2) TextButton(onClick = { compareAB = !compareAB },
                    modifier = Modifier.semantics { contentDescription = "중복 A/B 사진 비교 전환" }) {
                    Text(if (compareAB) "한 장 보기" else "A/B 비교")
                }
                IconAction("비교 닫기", Icons.Outlined.Close, onClick = { coordinator.pauseActive(); onDismiss() })
            }
            if (!actionsEnabled) Text(
                "중복 결과를 다시 확인하고 있습니다. 확인 후 변경할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.semantics { contentDescription = "중복 결과 확인 중" },
            )
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("SHA-256 전체 내용이 같은 사본 ${ordered.size}개 · 정리 후보 ${roles.candidates.size}개",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("파일을 눌러 비교하고, '이 파일 남기기'로 보존 대상을 바꿉니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                    modifier = Modifier.padding(bottom = 8.dp))
                LazyRow(
                    Modifier.fillMaxWidth().semantics { contentDescription = "중복 파일 가로 비교 목록" },
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                ) {
                    items(ordered, key = { it.uri }) { item ->
                        val active = focused.uri == item.uri
                        Column(
                            Modifier.width(cardWidth)
                                .border(if (active) 2.dp else 1.dp, if (active) Primary else Line, RoundedCornerShape(10.dp))
                                .plainClick(action = {
                                    coordinator.pauseActive()
                                    if (compareAB && item.kind == MediaKind.PHOTO && !editingSlotA) {
                                        if (item.uri != firstPhoto?.uri) secondUri = item.uri
                                    } else {
                                        if (compareAB && item.uri == secondPhoto?.uri) {
                                            secondUri = firstPhoto?.uri.orEmpty()
                                        }
                                        if (item.kind == MediaKind.VIDEO) compareAB = false
                                        focusedUri = item.uri
                                    }
                                })
                                .padding(4.dp)
                                .semantics {
                                    contentDescription = "${item.displayName} 비교 대상"
                                    stateDescription = if (active) "현재 보고 있는 파일" else "파일 선택 가능"
                                },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MediaThumbnail(item, Modifier.fillMaxWidth().height(thumbnailHeight))
                            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                            Text(roles.label(item, selectedUris), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) Primary else Muted)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (compareAB && firstPhoto != null && secondPhoto != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("교체할 사진", style = MaterialTheme.typography.labelMedium, color = Muted)
                        TextButton(onClick = { editingSlotA = true },
                            modifier = Modifier.semantics { contentDescription = "중복 A 슬롯 선택" }) {
                            Text(if (editingSlotA) "A 선택 중" else "사진 A",
                                color = if (editingSlotA) Primary else Muted)
                        }
                        TextButton(onClick = { editingSlotA = false },
                            modifier = Modifier.semantics { contentDescription = "중복 B 슬롯 선택" }) {
                            Text(if (!editingSlotA) "B 선택 중" else "사진 B",
                                color = if (!editingSlotA) Primary else Muted)
                        }
                    }
                    ABPhotoComparison(
                        a = firstPhoto, b = secondPhoto,
                        selectedUris = selectedUris.intersect(roles.candidateUris),
                        canSelect = { item -> item.uri in roles.candidateUris },
                        onToggleCandidate = { item -> onToggleSelection(item.uri) },
                        onOpenMedia = { item -> coordinator.pauseActive(); onOpen(item) },
                        modifier = Modifier.fillMaxWidth(),
                        allowEditing = actionsEnabled,
                        keeperUri = roles.keeper.uri,
                        onChooseKeeper = { item -> onChooseKeeper(group, item.uri) },
                        onProtect = { item -> onProtect(item, !item.isProtected) },
                        canProtect = { item -> !item.hasFolderProtection(roles.protectedFolders) },
                        statusLabel = { item -> roles.label(item, selectedUris) },
                    )
                } else key(focused.uri, focused.fingerprint, focused.volumeVersion) {
                    DuplicateComparePanel(
                        slot = "선택 파일",
                        item = focused,
                        roles = roles,
                        selectedUris = selectedUris,
                        coordinator = coordinator,
                        actionsEnabled = actionsEnabled,
                        onOpen = { coordinator.pauseActive(); onOpen(focused) },
                        onChooseKeeper = { onChooseKeeper(group, focused.uri) },
                        onToggleSelection = { onToggleSelection(focused.uri) },
                        onProtect = { onProtect(focused, !focused.isProtected) },
                        previewHeight = previewHeight,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun DuplicateComparePanel(
    slot: String,
    item: MediaItem,
    roles: DuplicateRoles,
    selectedUris: Set<String>,
    coordinator: PreviewPlaybackCoordinator,
    actionsEnabled: Boolean,
    onOpen: () -> Unit,
    onChooseKeeper: () -> Unit,
    onToggleSelection: () -> Unit,
    onProtect: () -> Unit,
    previewHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val candidate = item.uri in roles.candidateUris
    val selected = candidate && item.uri in selectedUris
    val folderProtected = item.hasFolderProtection(roles.protectedFolders)
    Surface(modifier.border(1.dp, Line, RoundedCornerShape(16.dp)).semantics { contentDescription = "$slot 파일 비교 패널" },
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val metadataBeside = maxWidth >= 560.dp * fontScale.coerceAtMost(1.4f)
            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(roles.label(item, selectedUris), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
            }
            val details: @Composable (Modifier) -> Unit = { detailModifier ->
                Column(detailModifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("파일 정보", style = MaterialTheme.typography.titleSmall, color = Muted)
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(item.relativePath.ifEmpty { "기본 폴더" }, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("${bytesLabel(item.sizeBytes)} · ${String.format(Locale.KOREA, "%,d", item.sizeBytes)} B", style = MaterialTheme.typography.bodySmall)
                    Text("${item.width} × ${item.height}" + if (item.kind == MediaKind.VIDEO) " · ${durationLabel(item.durationMs)}" else "", style = MaterialTheme.typography.bodySmall)
                    Text("${if (item.dateTakenMs > 0) "촬영일" else "저장일"} ${dateLabel(item.capturedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (!candidate) Text(roles.reason(item), style = MaterialTheme.typography.bodySmall, color = Muted)
                    else Text(if (selected) "정리 대상으로 선택했습니다. 아직 휴지통으로 이동하지 않았습니다." else "남길 파일을 확인한 뒤 필요한 사본만 정리 대상으로 선택하세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    HorizontalDivider(color = Line)
                    if (item.uri == roles.keeper.uri) Text("이 묶음에서 남길 기준 파일", style = MaterialTheme.typography.labelMedium, color = Ink)
                    else ActionButton("이 파일 남기기", onChooseKeeper, Modifier.fillMaxWidth().semantics { contentDescription = "$slot 남길 파일로 지정" }, filled = true, enabled = actionsEnabled)
                    TextButton(onClick = onProtect,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$slot ${if (item.isProtected) "보호 해제" else "보호"}" },
                        enabled = !folderProtected && actionsEnabled) {
                        Text(if (folderProtected) "폴더 보호 중" else if (item.isProtected) "파일 보호 해제" else "이 파일 보호")
                    }
                    if (candidate) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectBox(selected, actionsEnabled, "$slot 정리 후보 선택", onToggleSelection)
                        Text(if (selected) "정리 후보 · 선택됨" else "정리 후보로 선택", style = MaterialTheme.typography.labelMedium)
                    } else Text("남길 파일 · 이동 대상에서 제외", style = MaterialTheme.typography.labelMedium, color = Muted)
                    TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$slot 외부 앱에서 파일 열기" }) {
                        GalliIcon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("외부 앱에서 파일 열기")
                    }
                }
            }
            val preview: @Composable (Modifier) -> Unit = { previewModifier ->
                if (item.kind == MediaKind.VIDEO) VideoPreview(item, previewModifier, coordinator = coordinator, controlLabel = slot)
                else PhotoPreview(item, previewModifier, compact = true)
            }
            Column(Modifier.fillMaxWidth()) {
                if (metadataBeside) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        preview(Modifier.weight(1f).height(previewHeight))
                        Column(Modifier.weight(1f)) {
                            header()
                            details(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    header()
                    preview(Modifier.fillMaxWidth().height(previewHeight))
                    details(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
