package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.R
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*

@Composable
internal fun HomeScreen(
    state: AppUiState,
    requestAccess: () -> Unit,
    browse: (MediaFilter) -> Unit,
    duplicates: () -> Unit,
    cleanup: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = maxWidth >= 720.dp * fontScale
        val twoColumns = maxWidth >= 330.dp * fontScale
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HomeWelcome(cleanup)
            if (state.access == MediaAccess.NONE) {
                GalliCard(Modifier.fillMaxWidth()) {
                    Text("사진·동영상 접근이 필요합니다", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "기기 안의 파일을 살펴보려면 접근을 허용해 주세요. 파일 이동 전에는 Android 확인창에서 다시 승인합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    ActionButton("사진·동영상 선택", requestAccess, Modifier.fillMaxWidth(), filled = true)
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    HomeStorageSummary(state, Modifier.weight(1f))
                    HomeQuickActions(browse, duplicates, Modifier.weight(1f), twoColumns = true)
                }
            } else {
                HomeStorageSummary(state)
                HomeQuickActions(browse, duplicates, twoColumns = twoColumns)
            }
        }
    }
}

@Composable
private fun HomeWelcome(cleanup: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().galliCardSurface(Panel)) {
        val stacked = maxWidth < 350.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(GalliUiMetrics.cardPadding),
                verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GalliIllustration(GalliScene.ORGANIZE, Modifier.size(72.dp))
                    Text(
                        "사진과 영상을\n더 가볍고,\n더 깔끔하게!",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Text("필요한 파일만 직접 확인하세요", style = MaterialTheme.typography.bodySmall, color = Muted)
                ActionButton(
                    "정리 시작하기", cleanup, Modifier.fillMaxWidth(),
                    filled = true, icon = Icons.Rounded.ArrowForward,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(GalliUiMetrics.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GalliIllustration(GalliScene.ORGANIZE, Modifier.size(116.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
                ) {
                    Text(
                        "사진과 영상을\n더 가볍고,\n더 깔끔하게!",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text("필요한 파일만 직접 확인하세요", style = MaterialTheme.typography.bodySmall, color = Muted)
                    ActionButton(
                        "정리 시작하기", cleanup, Modifier.widthIn(max = 260.dp).fillMaxWidth(),
                        filled = true, icon = Icons.Rounded.ArrowForward,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeStorageSummary(state: AppUiState, modifier: Modifier = Modifier) {
    val total = state.totalStorageBytes.coerceAtLeast(0L)
    val used = (total - state.freeStorageBytes).coerceIn(0L, total)
    val usedFraction = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val usedPercent = (usedFraction * 100f).toInt()
    Column(
        modifier.galliCardSurface(if (IsGalliDark) AppSurface else Color.White)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(46.dp).background(Panel, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                GalliIcon(Icons.Rounded.Storage, null, Modifier.size(28.dp), tint = Primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("저장공간 사용량", style = MaterialTheme.typography.titleSmall)
                if (total > 0L) {
                    Text(
                        bytesLabel(used) + " / " + bytesLabel(total) + " (" + usedPercent + "%)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                } else {
                    Text("기기 저장공간 확인 중", style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Panel),
        ) {
            if (usedFraction > 0f) {
                Box(Modifier.fillMaxWidth(usedFraction).fillMaxHeight().background(Primary, CircleShape))
            }
        }
        Text(
            when (state.access) {
                MediaAccess.NONE -> "접근을 허용하면 관리할 파일을 확인할 수 있습니다."
                MediaAccess.PARTIAL -> "선택해 허용한 미디어 " + state.items.size + "개"
                else -> "접근 가능한 미디어 " + state.items.size + "개"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

private data class HomeShortcut(
    val title: String,
    val imageRes: Int? = null,
    val vectorIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
)

@Composable
private fun HomeQuickActions(
    browse: (MediaFilter) -> Unit,
    duplicates: () -> Unit,
    modifier: Modifier = Modifier,
    twoColumns: Boolean,
) {
    val oneYearAgo = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
    val shortcuts = listOf(
        HomeShortcut("사진·영상 정리", imageRes = R.drawable.gp_photo, onClick = { browse(MediaFilter()) }),
        HomeShortcut("중복 파일 찾기", imageRes = R.drawable.gp_duplicates, onClick = duplicates),
        HomeShortcut("용량 큰 파일", imageRes = R.drawable.gp_video, onClick = {
            browse(MediaFilter(minSizeBytes = 100L * 1024 * 1024, sortField = SortField.SIZE))
        }),
        HomeShortcut("오래된 파일", imageRes = R.drawable.gp_old_files, onClick = {
            browse(MediaFilter(
                fromDateMs = 1L, toDateMs = oneYearAgo,
                sortField = SortField.CAPTURED, sortDirection = SortDirection.ASCENDING,
            ))
        }),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (twoColumns) {
            shortcuts.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { shortcut ->
                        HomeShortcutCard(shortcut, Modifier.weight(1f))
                    }
                }
            }
        } else {
            shortcuts.forEach { shortcut ->
                HomeShortcutCard(shortcut, Modifier.fillMaxWidth(), compact = true)
            }
        }
    }
}

@Composable
private fun HomeShortcutCard(shortcut: HomeShortcut, modifier: Modifier = Modifier, compact: Boolean = false) {
    val surface = if (IsGalliDark) AppSurface else Color.White
    if (compact) {
        Row(
            modifier.heightIn(min = 76.dp).galliCardSurface(surface)
                .semantics { role = Role.Button }.plainClick(action = shortcut.onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeShortcutIcon(shortcut, Modifier.size(48.dp))
            Text(shortcut.title, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
            GalliIcon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = Muted)
        }
    } else {
        Column(
            modifier.heightIn(min = 124.dp).galliCardSurface(surface)
                .semantics { role = Role.Button }.plainClick(action = shortcut.onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeShortcutIcon(shortcut, Modifier.size(56.dp))
            Text(
                shortcut.title, style = MaterialTheme.typography.titleSmall, color = Ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomeShortcutIcon(shortcut: HomeShortcut, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (shortcut.imageRes != null) {
            Image(
                painter = painterResource(shortcut.imageRes), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
        } else if (shortcut.vectorIcon != null) {
            Box(
                Modifier.fillMaxSize().background(Panel, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                GalliIcon(shortcut.vectorIcon, null, Modifier.fillMaxSize(.62f), tint = Primary)
            }
        }
    }
}

internal data class CleanupSuggestion(val title: String, val description: String, val filter: MediaFilter, val items: List<MediaItem>)

private fun cleanupSuggestions(items: List<MediaItem>): List<CleanupSuggestion> {
    val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
    val screenshotFolder = items.filter { it.kind == MediaKind.PHOTO && (it.relativePath.contains("screenshot", true) || it.relativePath.contains("스크린샷")) }
        .groupBy { it.relativePath }.maxByOrNull { it.value.size }?.key
    val recordingFolder = items.filter { it.kind == MediaKind.VIDEO && (it.relativePath.contains("screen", true) || it.relativePath.contains("녹화")) }
        .groupBy { it.relativePath }.maxByOrNull { it.value.size }?.key
    val screenshotQuery = if (screenshotFolder != null) "" else "Screenshot"
    val recordingQuery = if (recordingFolder != null) "" else "Screen"
    val oldDownloads = MediaFilter(
        downloadsOnly = true, groupByDate = true,
        dateField = DateField.ADDED, fromDateMs = 1L, toDateMs = cutoff,
        sortField = SortField.ADDED, sortDirection = SortDirection.ASCENDING,
    )
    val largePhotos = MediaFilter(
        kind = MediaKind.PHOTO, minSizeBytes = 10L * 1024 * 1024,
        sortField = SortField.SIZE, sortDirection = SortDirection.DESCENDING,
    )
    return listOf(
        CleanupSuggestion("오래된 다운로드 파일", "다운로드 폴더 · 기기에 추가한 날짜로 1년 이전", oldDownloads, MediaQuery.apply(items, oldDownloads)),
        CleanupSuggestion("큰 동영상", "500 MB 이상인 동영상", MediaFilter(kind = MediaKind.VIDEO, minSizeBytes = 500L * 1024 * 1024, sortField = SortField.SIZE), items.filter { it.kind == MediaKind.VIDEO && it.sizeBytes >= 500L * 1024 * 1024 }),
        CleanupSuggestion("용량 큰 사진", "10 MB 이상 · 큰 사진부터", largePhotos, MediaQuery.apply(items, largePhotos)),
        CleanupSuggestion("오래된 스크린샷 후보", if (screenshotFolder != null) "1년 이전 · ${screenshotFolder.trimEnd('/')} 폴더 기준" else "1년 이전 · 파일명에 Screenshot 포함", MediaFilter(kind = MediaKind.PHOTO, folder = screenshotFolder.orEmpty(), query = screenshotQuery, fromDateMs = 1L, toDateMs = cutoff), items.filter { it.kind == MediaKind.PHOTO && it.capturedAt > 0 && it.capturedAt <= cutoff && if (screenshotFolder != null) it.relativePath == screenshotFolder else it.displayName.contains(screenshotQuery, true) }),
        CleanupSuggestion("화면 녹화 후보", if (recordingFolder != null) "${recordingFolder.trimEnd('/')} 폴더 기준" else "동영상 파일명에 Screen 포함", MediaFilter(kind = MediaKind.VIDEO, folder = recordingFolder.orEmpty(), query = recordingQuery, sortField = SortField.SIZE), items.filter { it.kind == MediaKind.VIDEO && if (recordingFolder != null) it.relativePath == recordingFolder else it.displayName.contains(recordingQuery, true) }),
    )
}

@Composable
internal fun CleanupScreen(state: AppUiState, onOpenDuplicates: (() -> Unit)? = null, browse: (MediaFilter) -> Unit) {
    val suggestions = remember(state.items) { cleanupSuggestions(state.items) }
    val populated = suggestions.filter { it.items.isNotEmpty() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("정리 후보", "확인 후 직접 선택한 파일만 휴지통으로 이동합니다.")
            if (onOpenDuplicates != null) {
                Row(
                    Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(14.dp))
                        .semantics { contentDescription = "중복 파일 검사 열기"; role = Role.Button }
                        .plainClick(action = onOpenDuplicates).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GalliIcon(Icons.Rounded.CopyAll, null, Modifier.size(32.dp), tint = Primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("완전히 같은 파일 찾기", style = MaterialTheme.typography.titleMedium)
                        Text("중복된 사진·영상을 검사하고 비교", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    GalliIcon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp))
                }
            }
            Text("종류별 정리", style = MaterialTheme.typography.titleMedium)
            suggestions.forEach { suggestion ->
                CleanupSuggestionRow(suggestion) { browse(suggestion.filter) }
            }
            if (populated.isEmpty()) {
                Text("현재 확인된 정리 후보가 없습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(
                "스크린샷·화면 녹화 후보는 폴더 및 파일명 기준입니다. 실제 내용을 확인해 주세요. 자동 삭제하지 않습니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CleanupSuggestionRow(suggestion: CleanupSuggestion, open: () -> Unit) {
    val deletable = suggestion.items.filter { it.canTrash() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .background(AppSurface, RoundedCornerShape(14.dp))
            .semantics { role = Role.Button }
            .plainClick(action = open).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CleanupCategoryIcon(suggestion, Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(suggestion.title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (deletable.isEmpty()) "현재 선택 가능한 후보 없음"
                else "${deletable.size}개 · ${bytesLabel(deletable.sumOf { it.sizeBytes })}",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            if (deletable.size < suggestion.items.size) {
                Text("즐겨찾기·보호 파일 제외", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        GalliIcon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp))
    }
}

@Composable
private fun CleanupResultCard(suggestion: CleanupSuggestion, open: () -> Unit, modifier: Modifier = Modifier) {
    val deletable = suggestion.items.filter { it.canTrash() }
    val excludedCount = suggestion.items.size - deletable.size
    Column(modifier.background(AppSurface, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CleanupCategoryIcon(suggestion, Modifier.size(42.dp))
            Text(suggestion.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text("${suggestion.items.size}개 · ${bytesLabel(suggestion.items.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.headlineSmall)
        Text("정리 가능 ${deletable.size}개 · ${bytesLabel(deletable.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Text(suggestion.description, style = MaterialTheme.typography.bodySmall, color = Muted)
        if (excludedCount > 0) {
            Text(
                if (deletable.isEmpty()) "모두 즐겨찾기·보호 파일입니다." else "즐겨찾기·보호 파일 ${excludedCount}개 제외 · 정리 가능 ${deletable.size}개 (${bytesLabel(deletable.sumOf { it.sizeBytes })})",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        ActionButton("파일 살펴보기", open, icon = Icons.Rounded.ArrowForward)
    }
}

@Composable
private fun CleanupEmptyRow(suggestion: CleanupSuggestion, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(AppSurface)
            .border(1.dp, Line, RoundedCornerShape(18.dp)).semantics { role = Role.Button }.plainClick(action = open).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CleanupCategoryIcon(suggestion, Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(suggestion.title, style = MaterialTheme.typography.titleMedium)
            Text("현재 후보 없음 · 조건 보기", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        GalliIcon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
    }
}

@Composable
private fun CleanupCategoryIcon(suggestion: CleanupSuggestion, modifier: Modifier) {
    val downloads = suggestion.filter.downloadsOnly
    val photo = suggestion.filter.kind == MediaKind.PHOTO
    Box(modifier.background(if (downloads) Panel else if (photo) Peach else Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        GalliIcon(if (downloads) Icons.Rounded.Folder else if (photo) Icons.Rounded.Image else Icons.Rounded.Videocam, null, Modifier.size(26.dp))
    }
}

@Composable
internal fun SettingsScreen(state: AppUiState, vm: AppViewModel, requestAccess: () -> Unit, openSettings: () -> Unit, openTrash: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var confirmClear by remember { mutableStateOf(false) }
    var showProtectedFolders by remember { mutableStateOf(false) }
    val folders = remember(state.items, state.protectedFolders) {
        val observed = state.items.groupBy { normalizeUiFolder(it.relativePath) }
        (observed.keys + state.protectedFolders).associateWith { observed[it].orEmpty() }.toSortedMap()
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val compact = maxWidth < 440.dp * fontScale
        Column(
            Modifier.fillMaxHeight().widthIn(max = 660.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 12.dp),
        ) {
            SectionTitle("설정", "화면·권한·파일 관리")
            SettingsSection("화면 테마") {
                Text("기기 설정을 따르거나 모드를 직접 선택하세요.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        val selected = state.themeMode == mode
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget).clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Panel else androidx.compose.ui.graphics.Color.Transparent)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { vm.setThemeMode(mode) },
                                    indication = null, interactionSource = remember { MutableInteractionSource() })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(when (mode) {
                                AppThemeMode.SYSTEM -> "기기 설정 따르기"
                                AppThemeMode.LIGHT -> "라이트 모드"
                                AppThemeMode.DARK -> "다크 모드"
                            }, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            SettingsSection("미디어 접근") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GalliIcon(Icons.Rounded.Shield, null, Modifier.size(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when (state.access) {
                            MediaAccess.NONE -> "접근 권한 없음"
                            MediaAccess.PARTIAL -> "일부 사진·동영상 접근 허용"
                            MediaAccess.FULL -> "전체 사진·동영상 접근 허용"
                        }, style = MaterialTheme.typography.bodyMedium)
                        Text("허용한 미디어만 분석하며 서버에 업로드하지 않습니다.",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("접근 범위 변경", requestAccess, Modifier.fillMaxWidth())
                            ActionButton("앱 권한 설정", openSettings, Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("접근 범위 변경", requestAccess, Modifier.weight(1f))
                            ActionButton("앱 권한 설정", openSettings, Modifier.weight(1f))
                        }
                    }
                }
            }
            SettingsSection("휴지통") {
                Text("보관 중 ${state.trashed.size}개 · 파일 용량 ${bytesLabel(state.trashed.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodyMedium)
                Text("복원은 Android 시스템 확인을 거쳐 진행합니다. 영구 삭제 기능은 제공하지 않습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton("휴지통 열기", openTrash, icon = Icons.Rounded.RestoreFromTrash)
            }
            SettingsSection("보호 폴더") {
                Text("직접 보호한 폴더 ${state.protectedFolders.size}개", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GalliIllustration(GalliScene.PROTECT, Modifier.size(44.dp))
                    Text("폴더를 보호하면 해당 파일의 정리 선택이 차단됩니다. 즐겨찾기도 기본으로 보호합니다.", color = Muted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                if (folders.isEmpty()) Text("미디어를 불러오면 폴더가 표시됩니다.", color = Muted)
                else ActionButton(
                    if (showProtectedFolders) "폴더 목록 접기" else "보호 폴더 관리 · ${folders.size}개",
                    { showProtectedFolders = !showProtectedFolders },
                    Modifier.fillMaxWidth(),
                )
                if (showProtectedFolders) folders.forEach { (path, items) ->
                    val protected = path in state.protectedFolders
                    val inherited = state.protectedFolders.firstOrNull { it.isNotEmpty() && it != path && path.startsWith(it) }
                    BoxWithConstraints(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(12.dp)) {
                        val label = if (protected) "보호 해제" else if (inherited != null) "상위 보호" else "보호"
                        val toggle = { vm.protectFolder(path, !protected) }
                        val description: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(path.trimEnd('/').ifEmpty { "기본 폴더" }, style = MaterialTheme.typography.bodyMedium)
                                Text("${items.size}개 · ${bytesLabel(items.sumOf { it.sizeBytes })}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (inherited != null) Text("상위 ${inherited.trimEnd('/')} 폴더에서 보호 중", color = Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (maxWidth < 360.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                description()
                                ActionButton(label, toggle, Modifier.fillMaxWidth(), enabled = protected || inherited == null)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) { description() }
                                ActionButton(label, toggle, enabled = protected || inherited == null)
                            }
                        }
                    }
                }
            }
            SettingsSection("분석 캐시") {
                Text("중복 분석 결과와 해시 캐시를 비웁니다. 사진·동영상 원본은 유지됩니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton("분석 캐시 비우기", { confirmClear = true })
            }
            SettingsSection("개인정보") {
                Text("미디어와 분석 결과는 외부로 전송하지 않습니다. 인터넷 연결 없이 작동하며 서버에 파일을 업로드하지 않습니다.", style = MaterialTheme.typography.bodyMedium)
                Text("사진 위치정보 접근은 원본 사진의 중복 검사에만 사용합니다. GPS 위치를 추적하거나 현재 위치를 수집하지 않습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
                ActionButton("개인정보처리방침", { uriHandler.openUri("https://hebi10.github.io/GalliPick/privacy.html") })
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GalliMascot(Modifier.size(38.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("GalliPick · 기기 내 미디어 정리", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
    if (confirmClear) ClearAnalysisCacheDialog(
        onConfirm = { vm.clearAnalysisCache(); confirmClear = false },
        onDismiss = { confirmClear = false },
    )
}

@Composable
internal fun ClearAnalysisCacheDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp),
        containerColor = AppSurface, titleContentColor = Ink, textContentColor = Muted,
        title = { Text("분석 캐시를 비울까요?") },
        text = { Text("중복 분석 결과와 해시 캐시를 초기화합니다. 다음 검사에서 다시 분석하며 사진·동영상 원본과 보호 설정은 유지합니다.") },
        confirmButton = { ActionButton("비우기", onConfirm, filled = true) },
        dismissButton = { TextAction("취소", onDismiss) },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val icon = when (title) {
        "화면 테마" -> Icons.Rounded.DarkMode
        "미디어 접근" -> Icons.Rounded.PhotoLibrary
        "휴지통" -> Icons.Rounded.RestoreFromTrash
        "보호 폴더" -> Icons.Rounded.Folder
        "분석 캐시" -> Icons.Rounded.CleaningServices
        else -> Icons.Rounded.Shield
    }
    Column(
        Modifier.fillMaxWidth().galliCardSurface(AppSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                GalliIcon(icon, null, Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        }
        content()
    }
}
