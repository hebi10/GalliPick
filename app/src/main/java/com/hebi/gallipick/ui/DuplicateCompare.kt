package com.hebi.gallipick.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    var leftUri by rememberSaveable(group.hash, initialItemUri) { mutableStateOf(roles.keeper.uri) }
    var rightUri by rememberSaveable(group.hash, initialItemUri) {
        mutableStateOf(group.items.firstOrNull { it.uri == initialItemUri && it.uri != roles.keeper.uri }?.uri
            ?: group.items.first { it.uri != roles.keeper.uri }.uri)
    }
    var activeSlot by rememberSaveable(group.hash, initialItemUri) { mutableIntStateOf(if (initialItemUri != null && initialItemUri != roles.keeper.uri) 1 else 0) }
    val left = group.items.firstOrNull { it.uri == leftUri } ?: roles.keeper
    val right = group.items.firstOrNull { it.uri == rightUri && it.uri != left.uri } ?: group.items.first { it.uri != left.uri }
    val coordinator = remember(group.hash) { PreviewPlaybackCoordinator() }
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 720.dp * scale.coerceAtMost(1.4f) && maxHeight >= 480.dp * scale
        val short = maxHeight < 420.dp * scale
        val sideMetadataFits = maxWidth - 24.dp >= 560.dp * scale.coerceAtMost(1.4f)
        val scrollBody = !sideBySide && maxHeight < (if (sideMetadataFits) 320.dp else 600.dp) * scale
        val finitePanelHeight = if (sideMetadataFits) (220.dp * scale).coerceAtLeast(300.dp) else 520.dp * scale.coerceAtMost(1.4f)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (short) "사본 비교" else "남길 파일과 정리 후보 비교", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconAction("비교 닫기", Icons.Rounded.Close, onClick = onDismiss)
            }
            Text(if (short) "파일 전체 내용이 같은 사본" else "파일 이름이 아니라 파일 전체의 SHA-256 값이 같은 사본입니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (!actionsEnabled) Text("중복 결과를 다시 확인하고 있습니다. 확인 후 변경할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.semantics { contentDescription = "중복 결과 확인 중" })
            if (!short) Text("선택만으로 이동하지 않습니다. 소리는 한 영상씩 재생합니다.", style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.padding(top = 3.dp, bottom = 6.dp))
            if (!sideBySide) PrimaryTabRow(selectedTabIndex = activeSlot) {
                Tab(selected = activeSlot == 0, onClick = { activeSlot = 0 }, modifier = Modifier.testTag("A 비교 탭"),
                    text = { Text("A · ${roles.label(left, selectedUris).substringBefore(" · ")}") })
                Tab(selected = activeSlot == 1, onClick = { activeSlot = 1 }, modifier = Modifier.testTag("B 비교 탭"),
                    text = { Text("B · ${roles.label(right, selectedUris).substringBefore(" · ")}") })
            }
            val panel: @Composable (String, MediaItem, Modifier) -> Unit = { slot, item, panelModifier ->
                key(slot, item.uri, item.fingerprint, item.volumeVersion) {
                    DuplicateComparePanel(slot, item, group, roles, selectedUris, coordinator, actionsEnabled,
                        onChooseItem = { uri ->
                            if (slot == "A") {
                                if (uri == right.uri) rightUri = left.uri
                                leftUri = uri
                            } else {
                                if (uri == left.uri) leftUri = right.uri
                                rightUri = uri
                            }
                        },
                        onOpen = { coordinator.pauseActive(); onOpen(item) },
                        onChooseKeeper = { onChooseKeeper(group, item.uri) }, onToggleSelection = { onToggleSelection(item.uri) },
                        onProtect = { onProtect(item, !item.isProtected) }, modifier = panelModifier,
                    )
                }
            }
            if (sideBySide) Row(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                panel("A", left, Modifier.weight(1f).fillMaxHeight())
                panel("B", right, Modifier.weight(1f).fillMaxHeight())
            } else panel(if (activeSlot == 0) "A" else "B", if (activeSlot == 0) left else right,
                (if (scrollBody) Modifier.height(finitePanelHeight) else Modifier.weight(1f)).fillMaxWidth().padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun DuplicateComparePanel(
    slot: String,
    item: MediaItem,
    group: DuplicateGroup,
    roles: DuplicateRoles,
    selectedUris: Set<String>,
    coordinator: PreviewPlaybackCoordinator,
    actionsEnabled: Boolean,
    onChooseItem: (String) -> Unit,
    onOpen: () -> Unit,
    onChooseKeeper: () -> Unit,
    onToggleSelection: () -> Unit,
    onProtect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidate = item.uri in roles.candidateUris
    val selected = candidate && item.uri in selectedUris
    val folderProtected = item.hasFolderProtection(roles.protectedFolders)
    Surface(modifier.border(1.dp, Line, RoundedCornerShape(16.dp)).semantics { contentDescription = "$slot 파일 비교 패널" },
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val metadataBeside = maxWidth >= 560.dp * fontScale.coerceAtMost(1.4f) && maxHeight < 400.dp * fontScale
            // A tall portrait window should bring the decision closer, not enlarge the preview indefinitely.
            val stackedPreviewHeight = (maxHeight * .45f).coerceIn(220.dp, 280.dp)
            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(roles.label(item, selectedUris), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    var picker by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { picker = true }, modifier = Modifier.semantics { contentDescription = "$slot 비교 파일 변경" }) { Text("비교 대상 변경") }
                        DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                            group.items.forEach { option -> DropdownMenuItem(text = {
                                Column {
                                    Text(option.displayName)
                                    Text(option.relativePath.ifEmpty { "기본 폴더" }, style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }, onClick = { picker = false; onChooseItem(option.uri) }) }
                        }
                    }
                }
            }
            val details: @Composable (Modifier) -> Unit = { detailModifier ->
                Column(detailModifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(item.relativePath.ifEmpty { "기본 폴더" }, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("${bytesLabel(item.sizeBytes)} · ${String.format(Locale.KOREA, "%,d", item.sizeBytes)} B", style = MaterialTheme.typography.bodySmall)
                    Text("${item.width} × ${item.height}" + if (item.kind == MediaKind.VIDEO) " · ${durationLabel(item.durationMs)}" else "", style = MaterialTheme.typography.bodySmall)
                    Text("${if (item.dateTakenMs > 0) "촬영일" else "저장일"} ${dateLabel(item.capturedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (!candidate) Text(roles.reason(item), style = MaterialTheme.typography.bodySmall, color = Muted)
                    else Text(if (selected) "휴지통 이동 대상으로 선택했습니다." else "필요 없는 사본이면 이동 대상으로 선택하세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    if (item.uri == roles.keeper.uri) Text("이 묶음에서 남길 기준 파일", style = MaterialTheme.typography.labelMedium, color = Ink)
                    else ActionButton("이 파일 남기기", onChooseKeeper, Modifier.fillMaxWidth().semantics { contentDescription = "$slot 남길 파일로 지정" }, filled = true, enabled = actionsEnabled)
                    TextButton(onClick = onProtect,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$slot ${if (item.isProtected) "보호 해제" else "보호"}" },
                        enabled = !folderProtected && actionsEnabled) {
                        Text(if (folderProtected) "폴더 보호 중" else if (item.isProtected) "파일 보호 해제" else "이 파일 보호")
                    }
                    if (candidate) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectBox(selected, actionsEnabled, "$slot 정리 후보 선택", onToggleSelection)
                        Text(if (selected) "이동 대상 · 선택됨" else "휴지통 이동 대상으로 선택", style = MaterialTheme.typography.labelMedium)
                    } else Text("남길 파일 · 이동 대상에서 제외", style = MaterialTheme.typography.labelMedium, color = Muted)
                    TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$slot 외부 앱에서 파일 열기" }) {
                        GalliIcon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("외부 앱에서 파일 열기")
                    }
                }
            }
            val preview: @Composable (Modifier) -> Unit = { previewModifier ->
                if (item.kind == MediaKind.VIDEO) VideoPreview(item, previewModifier, coordinator = coordinator, controlLabel = slot)
                else PhotoPreview(item, previewModifier, compact = true)
            }
            Column(Modifier.fillMaxSize()) {
                if (metadataBeside) Row(Modifier.weight(1f).fillMaxWidth()) {
                    preview(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        header()
                        details(Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    header()
                    preview(Modifier.height(stackedPreviewHeight).fillMaxWidth())
                    details(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
