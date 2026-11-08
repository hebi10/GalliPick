package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.FolderStorageEntry
import com.hebi.gallipick.domain.FolderStoragePolicy
import com.hebi.gallipick.domain.FolderStorageSummary
import java.util.Locale
import kotlin.math.roundToInt

private enum class FolderStorageSort(val title: String) {
    LARGE_FIRST("용량 큰 순"),
    SMALL_FIRST("용량 작은 순"),
    MANY_FIRST("파일 많은 순"),
}

@Composable
internal fun FolderStorageScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    requestAccess: () -> Unit,
    refresh: () -> Unit,
) {
    val summary = remember(state.items) { FolderStoragePolicy.summarize(state.items) }
    var sort by rememberSaveable { mutableStateOf(FolderStorageSort.LARGE_FIRST) }
    var sortMenu by remember { mutableStateOf(false) }
    val folders = remember(summary.folders, sort) {
        when (sort) {
            FolderStorageSort.LARGE_FIRST -> summary.folders.sortedWith(
                compareByDescending<FolderStorageEntry> { it.sizeBytes }.thenBy { it.path }
            )
            FolderStorageSort.SMALL_FIRST -> summary.folders.sortedWith(
                compareBy<FolderStorageEntry> { it.sizeBytes }.thenBy { it.path }
            )
            FolderStorageSort.MANY_FIRST -> summary.folders.sortedWith(
                compareByDescending<FolderStorageEntry> { it.count }.thenByDescending { it.sizeBytes }.thenBy { it.path }
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = maxWidth >= 700.dp * fontScale
        val compactHeight = maxHeight < 480.dp * fontScale.coerceAtMost(1.3f)
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight()
                .padding(horizontal = if (wide) 28.dp else 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconAction("폴더별 용량 분석 닫기", Icons.Rounded.ArrowBack, onClick = onBack)
                Text(
                    "폴더별 용량 분석",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            when {
                state.access == MediaAccess.NONE -> Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        "미디어 접근이 필요합니다",
                        "접근을 허용한 사진·동영상 폴더의 용량만 분석합니다.",
                        action = { ActionButton("접근 허용", requestAccess, filled = true) },
                    )
                }
                state.catalogError != null -> Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        "폴더 용량을 불러오지 못했습니다",
                        "파일 목록을 다시 조회한 뒤 확인해 주세요.",
                        action = { ActionButton("다시 시도", refresh, filled = true) },
                    )
                }
                state.loading -> Box(Modifier.fillMaxSize()) {
                    EmptyState("미디어를 불러오고 있습니다", "접근 가능한 파일을 확인하고 있습니다.")
                }
                summary.folders.isEmpty() -> Box(Modifier.fillMaxSize()) {
                    EmptyState("확인할 폴더가 없습니다", "접근 가능한 사진·동영상이 없습니다.")
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().semantics { contentDescription = "폴더별 용량 목록" },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
                ) {
                    if (!compactHeight) item("intro") {
                        Text(
                            "어떤 폴더의 사진·동영상이 용량을 많이 차지하는지 확인하세요.",
                            style = MaterialTheme.typography.bodyMedium, color = Muted,
                        )
                    }
                    item("summary") { FolderStorageSummaryCard(summary, compactHeight) }
                    item("sort") {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("폴더 목록", style = MaterialTheme.typography.titleMedium)
                            Box {
                                TextButton(
                                    onClick = { sortMenu = true },
                                    modifier = Modifier.semantics { contentDescription = "폴더 정렬 선택" },
                                ) {
                                    Text(sort.title, color = Ink)
                                    Spacer(Modifier.width(4.dp))
                                    GalliIcon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp), tint = Ink)
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    FolderStorageSort.entries.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text(item.title) },
                                            onClick = { sort = item; sortMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(folders, key = { it.path }) { entry ->
                        FolderStorageRow(entry, summary.totalBytes, compactHeight) { onOpenFolder(entry.path) }
                    }
                    item("note") {
                        Text(
                            "기기 전체 저장공간이 아닌, 현재 접근 가능한 사진·동영상의 용량입니다. 폴더별 직접 포함 파일만 계산하며 하위 폴더는 별도로 표시합니다. 파일을 자동으로 삭제하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderStorageSummaryCard(summary: FolderStorageSummary, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
            .padding(if (compact) 10.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(if (compact) 40.dp else 52.dp).background(Mint, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            GalliIcon(Icons.Rounded.Storage, null, Modifier.size(if (compact) 22.dp else 28.dp), tint = Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (compact) {
                Text("사진·영상 ${bytesLabel(summary.totalBytes)}", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    "접근 가능 · ${String.format(Locale.KOREA, "%,d", summary.totalCount)}개 파일 · ${summary.folders.size}개 폴더",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            } else {
                Text("접근 가능한 사진·동영상 합계", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(bytesLabel(summary.totalBytes), style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text(
                    "${String.format(Locale.KOREA, "%,d", summary.totalCount)}개 파일 · ${summary.folders.size}개 폴더",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        }
    }
}

@Composable
private fun FolderStorageRow(entry: FolderStorageEntry, totalBytes: Long, compact: Boolean, open: () -> Unit) {
    val fraction = FolderStoragePolicy.fraction(entry, totalBytes)
    val clickable = entry.canBrowse
    val basis = Modifier.fillMaxWidth().heightIn(min = if (compact) 70.dp else 94.dp)
        .clip(RoundedCornerShape(GalliUiMetrics.cardCorner))
        .background(AppSurface)
        .then(
            if (clickable) Modifier.semantics {
                role = Role.Button
                contentDescription = "${entry.displayName} 폴더 파일 보기"
            }.plainClick(action = open)
            else Modifier
        )
        .padding(if (compact) 10.dp else GalliUiMetrics.cardPadding)
    Column(basis, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalliIcon(Icons.Rounded.Folder, null, Modifier.size(if (compact) 20.dp else 24.dp), tint = Primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.titleSmall,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                Text("${String.format(Locale.KOREA, "%,d", entry.count)}개", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(bytesLabel(entry.sizeBytes), style = MaterialTheme.typography.titleSmall, color = Ink)
                Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            if (clickable) GalliIcon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(if (compact) 5.dp else 7.dp).background(Panel, RoundedCornerShape(7.dp)),
        ) {
            if (fraction > 0f) Box(
                Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .background(Primary, RoundedCornerShape(7.dp)),
            )
        }
        if (!clickable) {
            Text("폴더 경로가 없어 바로 열 수 없습니다.", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
