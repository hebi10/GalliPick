package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.SelectionPolicy

internal enum class MediaCatalogAvailability { PERMISSION_REQUIRED, LOADING, ERROR, READY }

internal val AppUiState.catalogAvailability: MediaCatalogAvailability
    get() = when {
        access == MediaAccess.NONE -> MediaCatalogAvailability.PERMISSION_REQUIRED
        loading -> MediaCatalogAvailability.LOADING
        catalogError != null -> MediaCatalogAvailability.ERROR
        !catalogReady -> MediaCatalogAvailability.LOADING
        else -> MediaCatalogAvailability.READY
    }

@Composable
internal fun TrashScreen(
    state: AppUiState,
    onRestore: (List<MediaItem>) -> Unit,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val availability = state.catalogAvailability
    val items = state.trashed
    var selected by rememberSaveable { mutableStateOf(emptyMap<String, Pair<String, String>>()) }
    var limitMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(availability, items) {
        when (availability) {
            MediaCatalogAvailability.READY -> {
                val current = items.associateBy { it.uri }
                selected = selected.filter { (uri, version) -> current[uri]?.let { it.fingerprint to it.volumeVersion } == version }
            }
            MediaCatalogAvailability.PERMISSION_REQUIRED -> selected = emptyMap()
            MediaCatalogAvailability.LOADING, MediaCatalogAvailability.ERROR -> return@LaunchedEffect
        }
        if (selected.isEmpty()) limitMessage = null
    }
    val selectedItems = items.filter { selected[it.uri] == (it.fingerprint to it.volumeVersion) }
    val toggle: (MediaItem) -> Unit = { item ->
        when {
            selected[item.uri] == (item.fingerprint to item.volumeVersion) -> { selected = selected - item.uri; limitMessage = null }
            selected.size < SelectionPolicy.MAX_SELECTION_SIZE -> {
                selected = selected + (item.uri to (item.fingerprint to item.volumeVersion))
                limitMessage = null
            }
            else -> limitMessage = "한 번에 최대 ${SelectionPolicy.MAX_SELECTION_SIZE}개까지 복원할 수 있습니다. 선택을 해제한 뒤 다시 선택해 주세요."
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 1040.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconAction("뒤로", Icons.Outlined.ArrowBack, onClick = onBack)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("휴지통", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                    if (availability == MediaCatalogAvailability.READY) {
                        Text("보관 파일 ${items.size}개 · 파일 용량 ${bytesLabel(items.sumOf { it.sizeBytes })}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (availability != MediaCatalogAvailability.READY) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (availability) {
                        MediaCatalogAvailability.PERMISSION_REQUIRED -> EmptyState(
                            "미디어 접근이 필요합니다", "휴지통에 있는 사진과 동영상을 확인하려면 접근을 허용해 주세요.",
                            Icons.Outlined.PhotoLibrary,
                            action = { ActionButton("접근 허용", onRequestPermission, filled = true) },
                        )
                        MediaCatalogAvailability.LOADING -> EmptyState(
                            "휴지통을 불러오고 있습니다", "복원할 수 있는 사진과 동영상을 확인하고 있습니다.",
                            Icons.Outlined.DeleteOutline,
                            action = { CircularProgressIndicator(Modifier.size(28.dp)) },
                            scene = GalliScene.SEARCH,
                        )
                        MediaCatalogAvailability.ERROR -> EmptyState(
                            "휴지통을 불러오지 못했습니다", state.catalogError.orEmpty(), Icons.Outlined.ErrorOutline,
                            action = { ActionButton("다시 시도", onRetry, filled = true) },
                        )
                        MediaCatalogAvailability.READY -> Unit
                    }
                }
                return@Column
            }
            Spacer(Modifier.height(16.dp))
            if (state.access == MediaAccess.PARTIAL) {
                GalliInlineNotice(
                    "접근을 허용한 사진·동영상만 표시 중",
                    "접근 범위 변경", onRequestPermission, Icons.Outlined.PhotoLibrary,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "휴지통 보관 기간은 Android가 관리합니다. 보관 기간이 지나면 자동 삭제될 수 있습니다. 파일 복원은 Android 시스템에서 최종 승인합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp),
            )
            if (items.isEmpty()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val short = maxHeight < 300.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    if (short) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            GalliIllustration(GalliScene.EMPTY, Modifier.size(44.dp))
                            Text("휴지통이 비어 있습니다", style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center)
                            Text("휴지통으로 이동한 사진과 동영상이 여기에 표시됩니다.",
                                style = MaterialTheme.typography.bodySmall, color = Muted,
                                textAlign = TextAlign.Center)
                        }
                    } else {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            EmptyState("휴지통이 비어 있습니다",
                                "휴지통으로 이동한 사진과 동영상이 여기에 표시됩니다.",
                                Icons.Outlined.DeleteOutline)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val allSelected = selectedItems.size == minOf(items.size, SelectionPolicy.MAX_SELECTION_SIZE)
                    SelectBox(
                        checked = allSelected,
                        description = "휴지통 전체 선택",
                        onClick = {
                            selected = if (allSelected) emptyMap() else items.take(SelectionPolicy.MAX_SELECTION_SIZE).associate { it.uri to (it.fingerprint to it.volumeVersion) }
                            limitMessage = if (!allSelected && items.size > SelectionPolicy.MAX_SELECTION_SIZE) "앞의 ${SelectionPolicy.MAX_SELECTION_SIZE}개를 선택했습니다. 한 번에 최대 ${SelectionPolicy.MAX_SELECTION_SIZE}개까지 복원할 수 있습니다." else null
                        },
                    )
                    Text(if (items.size > SelectionPolicy.MAX_SELECTION_SIZE) "앞 ${SelectionPolicy.MAX_SELECTION_SIZE}개 선택" else "전체 선택", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("${selectedItems.size}개 선택 · ${bytesLabel(selectedItems.sumOf { it.sizeBytes })}", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    limitMessage ?: "한 번에 최대 ${SelectionPolicy.MAX_SELECTION_SIZE}개까지 복원할 수 있습니다.",
                    color = Muted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                LazyColumn(
                    Modifier.weight(1f).semantics { contentDescription = "휴지통 파일 목록" },
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.uri }) { item ->
                        TrashRow(item, selected[item.uri] == (item.fingerprint to item.volumeVersion)) { toggle(item) }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        if (selectedItems.isEmpty()) "복원할 파일을 선택하세요." else "복원 예정 ${selectedItems.size}개 · 파일 용량 ${bytesLabel(selectedItems.sumOf { it.sizeBytes })}",
                        color = Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    ActionButton(
                        "선택한 ${selectedItems.size}개 복원", onClick = { onRestore(selectedItems) },
                        modifier = Modifier.fillMaxWidth(), filled = true,
                        enabled = selectedItems.isNotEmpty(), icon = Icons.Outlined.RestoreFromTrash,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashRow(item: MediaItem, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Panel else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (selected) Ink else Line, RoundedCornerShape(12.dp))
            .plainClick(action = onToggle).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectBox(checked = selected, description = "${item.displayName} 복원 선택", onClick = onToggle)
        MediaThumbnail(item, Modifier.size(56.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (item.kind == MediaKind.VIDEO) "동영상" else "사진"} · ${bytesLabel(item.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            if (item.relativePath.isNotBlank()) {
                Text(item.relativePath, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.isFavorite || item.isProtected) {
                Text(
                    listOfNotNull("즐겨찾기".takeIf { item.isFavorite }, "보호됨".takeIf { item.isProtected }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
        }
    }
}
