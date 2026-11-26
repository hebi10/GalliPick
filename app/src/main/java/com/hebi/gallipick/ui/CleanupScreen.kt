package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.MediaQuery
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField

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
internal fun CleanupScreen(
    state: AppUiState,
    onOpenDuplicates: (() -> Unit)? = null,
    onOpenFolderStorage: (() -> Unit)? = null,
    onOpenPhotoAnalysis: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
    onOpenReviewInbox: (() -> Unit)? = null,
    onOpenTrash: (() -> Unit)? = null,
    onRequestAccess: (() -> Unit)? = null,
    onOpenPermissionSettings: (() -> Unit)? = null,
    onRetryCatalog: (() -> Unit)? = null,
    browse: (MediaFilter) -> Unit,
) {
    val catalogIssue = when {
        state.access == MediaAccess.NONE -> "사진·동영상 접근이 필요합니다."
        state.catalogError != null -> state.catalogError
        state.loading || (!state.catalogReady && state.items.isEmpty()) -> "정리할 수 있는 기기 파일을 확인하고 있습니다."
        else -> null
    }
    val accessibleItems = if (catalogIssue == null) state.items else emptyList()
    val suggestions = remember(accessibleItems) { cleanupSuggestions(accessibleItems) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = maxWidth >= 700.dp * fontScale
        val showIllustration = maxWidth >= 350.dp * fontScale && maxHeight >= 480.dp * fontScale
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SectionTitle("정리", "필요한 파일을 찾아 직접 확인하고 정리하세요.")
                }
                if (showIllustration) {
                    GalliIllustration(GalliScene.ORGANIZE, Modifier.size(88.dp))
                }
            }
            if (catalogIssue != null) {
                GalliCard(Modifier.fillMaxWidth()) {
                    Text(catalogIssue, style = MaterialTheme.typography.bodyMedium)
                    val action = when {
                        state.access == MediaAccess.NONE && onOpenPermissionSettings != null -> "앱 권한 설정" to onOpenPermissionSettings
                        state.access == MediaAccess.NONE && onRequestAccess != null -> "접근 허용" to onRequestAccess
                        state.catalogError != null && onRetryCatalog != null -> "다시 시도" to onRetryCatalog
                        else -> null
                    }
                    action?.let { (label, open) -> ActionButton(label, open, Modifier.fillMaxWidth(), filled = true) }
                }
            } else if (state.access == MediaAccess.PARTIAL) {
                GalliCard(Modifier.fillMaxWidth()) {
                    Text("허용한 사진과 동영상에서 정리 후보를 찾습니다.",
                        style = MaterialTheme.typography.bodyMedium, color = Muted)
                    (onOpenPermissionSettings ?: onRequestAccess)?.let { changeAccess ->
                        TextAction(if (onOpenPermissionSettings == null) "접근 변경" else "앱 권한 설정", changeAccess)
                    }
                }
            }
            if (onOpenDuplicates != null) {
                Text(
                    "중복 파일", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                CleanupNavigationRow(
                    title = "같은 파일 찾기",
                    subtitle = "내용이 같은 사진과 동영상을 묶습니다.",
                    icon = Icons.Outlined.CopyAll,
                    description = "사진·동영상 중복 검사 열기",
                    open = onOpenDuplicates,
                )
            }
            if (onOpenPhotoAnalysis != null || onOpenReviewInbox != null) {
                Text(
                    "사진 분석", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (onOpenPhotoAnalysis != null) {
                CleanupNavigationRow(
                    title = "비슷한 사진 찾기",
                    subtitle = "장면이 비슷한 사진을 찾습니다.",
                    icon = Icons.Outlined.Collections,
                    description = "유사 사진 분석 열기",
                    open = onOpenPhotoAnalysis,
                )
            }
            if (onOpenReviewInbox != null) {
                CleanupNavigationRow(
                    title = "사진 검토함",
                    subtitle = "검토하던 사진을 이어서 확인해요.",
                    icon = Icons.Outlined.FactCheck,
                    description = "스마트 사진 검토함 열기",
                    open = onOpenReviewInbox,
                )
            }
            if (onOpenHistory != null || onOpenTrash != null) {
                Text("기록·복원", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
            }
            if (onOpenHistory != null) {
                CleanupNavigationRow(
                    title = "정리 기록 보기",
                    subtitle = "확인된 휴지통 이동·복원 내역 · ${state.cleanupHistory.size}건",
                    icon = Icons.Outlined.History,
                    description = "정리 기록 열기",
                    open = onOpenHistory,
                )
            }
            if (onOpenTrash != null) {
                CleanupNavigationRow(
                    title = "휴지통·복원",
                    subtitle = "Android 휴지통에 보관된 원본을 확인해요.",
                    icon = Icons.Outlined.RestoreFromTrash,
                    description = "휴지통 확인 및 복원 열기",
                    open = onOpenTrash,
                )
            }
            Text("종류별 정리", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            suggestions.forEach { suggestion ->
                CleanupSuggestionRow(suggestion, catalogIssue == null) { browse(suggestion.filter) }
            }
            if (onOpenFolderStorage != null) {
                CleanupNavigationRow("폴더별 용량 분석", "접근 가능한 사진·동영상의 폴더별 용량 확인",
                    Icons.Outlined.Folder, "폴더별 용량 분석 열기", onOpenFolderStorage)
            }
            if (catalogIssue == null && suggestions.none { it.items.any { item -> item.canTrash() } }) {
                Text(if (state.access == MediaAccess.PARTIAL) "허용한 파일 중 정리 후보가 없습니다."
                    else "현재 확인된 정리 후보가 없습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(
                "스크린샷·화면 녹화 후보는 폴더 및 파일명 기준입니다. 실제 내용을 확인해 주세요. 자동 삭제하지 않습니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun CleanupNavigationRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    open: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .galliCardSurface(AppSurface)
            .semantics { role = Role.Button; contentDescription = description }
            .plainClick(action = open).padding(GalliUiMetrics.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(Panel, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            GalliIcon(icon, null, Modifier.size(24.dp), tint = Primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        GalliIcon(Icons.Outlined.ChevronRight, null, Modifier.size(22.dp))
    }
}

@Composable
private fun CleanupSuggestionRow(suggestion: CleanupSuggestion, catalogReady: Boolean, open: () -> Unit) {
    val deletable = suggestion.items.filter { it.canTrash() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .galliCardSurface(AppSurface)
            .semantics { role = Role.Button }
            .plainClick(action = open).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CleanupCategoryIcon(suggestion, Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(suggestion.title, style = MaterialTheme.typography.titleSmall)
            if (catalogReady) Text(
                if (deletable.isEmpty()) "현재 선택 가능한 후보 없음"
                else "${deletable.size}개 · ${bytesLabel(deletable.sumOf { it.sizeBytes })}",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            if (deletable.size < suggestion.items.size) {
                Text("즐겨찾기·보호 파일 제외", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        GalliIcon(Icons.Outlined.ChevronRight, null, Modifier.size(22.dp))
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
        ActionButton("파일 살펴보기", open, icon = Icons.Outlined.ArrowForward)
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
        GalliIcon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
    }
}

@Composable
private fun CleanupCategoryIcon(suggestion: CleanupSuggestion, modifier: Modifier) {
    val downloads = suggestion.filter.downloadsOnly
    val photo = suggestion.filter.kind == MediaKind.PHOTO
    Box(modifier.background(if (downloads) Panel else IconContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        GalliIcon(if (downloads) Icons.Outlined.Folder else if (photo) Icons.Outlined.Image else Icons.Outlined.Videocam, null, Modifier.size(26.dp))
    }
}
