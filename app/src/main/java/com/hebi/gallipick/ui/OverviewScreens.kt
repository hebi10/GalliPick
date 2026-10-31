package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*

@Composable
internal fun HomeScreen(state: AppUiState, requestAccess: () -> Unit, browse: (MediaFilter) -> Unit, duplicates: () -> Unit, cleanup: () -> Unit) {
    val photos = state.items.filter { it.kind == MediaKind.PHOTO }
    val videos = state.items.filter { it.kind == MediaKind.VIDEO }
    val mediaSize = state.items.sumOf { it.sizeBytes }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("픽이와 함께, 차근차근", style = MaterialTheme.typography.labelMedium, color = Muted)
                Text("추억은 소중하게,\n공간은 가볍게.", style = MaterialTheme.typography.titleLarge.copy(lineHeight = 29.sp))
                Text("사진과 동영상을 한눈에 살펴보세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            GalliMascot(Modifier.size(80.dp))
        }
        if (state.access == MediaAccess.NONE) {
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp).background(Peach, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    GalliIcon(Icons.Rounded.PhotoLibrary, null, Modifier.size(32.dp))
                }
                Text("먼저 사진·동영상 접근을 허용해주세요", style = MaterialTheme.typography.titleMedium)
                Text("기기의 미디어를 읽어 용량과 중복 파일을 보여드립니다. 삭제할 때는 Android 확인창이 열립니다.", style = MaterialTheme.typography.bodyMedium, color = Muted)
                Text("원본 사진의 정확한 중복 검사를 위해 사진에 포함된 위치 메타데이터 접근을 요청할 수 있습니다. GPS 위치 추적은 사용하지 않습니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton("사진·동영상 선택", requestAccess, Modifier.fillMaxWidth(), filled = true)
            }
        }
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalliIcon(Icons.Rounded.Folder, null, Modifier.size(24.dp))
                Text("기기 저장공간", style = MaterialTheme.typography.titleMedium)
            }
            Text(if (state.totalStorageBytes > 0) "${bytesLabel(state.freeStorageBytes)} 사용 가능" else "저장공간 확인 중", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (state.totalStorageBytes > 0) {
                val used = ((state.totalStorageBytes - state.freeStorageBytes).toFloat() / state.totalStorageBytes).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Panel)) {
                    if (used > 0) Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(Ink, CircleShape))
                }
                Text("전체 ${bytesLabel(state.totalStorageBytes)} · 사용 중 ${bytesLabel(state.totalStorageBytes - state.freeStorageBytes)}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("${if (state.access == MediaAccess.PARTIAL) "허용한" else "접근 가능한"} 미디어 ${state.items.size}개 · ${bytesLabel(mediaSize)}", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaStat("사진", photos.size, photos.sumOf { it.sizeBytes }, Icons.Rounded.Image, Modifier.weight(1f), Mint) { browse(MediaFilter(kind = MediaKind.PHOTO)) }
            MediaStat("동영상", videos.size, videos.sumOf { it.sizeBytes }, Icons.Rounded.Videocam, Modifier.weight(1f), Peach) { browse(MediaFilter(kind = MediaKind.VIDEO)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("정리 시작하기", style = MaterialTheme.typography.titleLarge)
            OverviewAction("완전히 같은 파일 찾기", "파일 내용을 비교해 중복 사본을 확인합니다", Icons.Rounded.CopyAll, duplicates)
            OverviewAction("용량을 줄일 후보 살펴보기", "큰 동영상과 오래된 스크린샷부터 확인하세요", Icons.Rounded.CleaningServices, cleanup)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalliIcon(Icons.Rounded.Shield, null, Modifier.size(22.dp))
            Text("모든 분석은 이 기기에서 이루어집니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun MediaStat(title: String, count: Int, size: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, accent: Color, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(24.dp)).plainClick(action = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).background(accent, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            GalliIcon(icon, null, Modifier.size(30.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("${count}개", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(bytesLabel(size), color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OverviewAction(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(20.dp)).plainClick(action = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp).background(Panel, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            GalliIcon(icon, null, Modifier.size(32.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        GalliIcon(Icons.Rounded.ChevronRight, "열기", Modifier.size(20.dp))
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
    return listOf(
        CleanupSuggestion("큰 동영상", "500 MB 이상인 동영상 · 1 MB = 1,048,576 B", MediaFilter(kind = MediaKind.VIDEO, minSizeBytes = 500L * 1024 * 1024, sortField = SortField.SIZE), items.filter { it.kind == MediaKind.VIDEO && it.sizeBytes >= 500L * 1024 * 1024 }),
        CleanupSuggestion("오래된 스크린샷 후보", if (screenshotFolder != null) "1년 이전 · ${screenshotFolder.trimEnd('/')} 폴더 기준" else "1년 이전 · 파일명에 Screenshot 포함", MediaFilter(kind = MediaKind.PHOTO, folder = screenshotFolder.orEmpty(), query = screenshotQuery, fromDateMs = 1L, toDateMs = cutoff), items.filter { it.kind == MediaKind.PHOTO && it.capturedAt > 0 && it.capturedAt <= cutoff && if (screenshotFolder != null) it.relativePath == screenshotFolder else it.displayName.contains(screenshotQuery, true) }),
        CleanupSuggestion("화면 녹화 후보", if (recordingFolder != null) "${recordingFolder.trimEnd('/')} 폴더 기준" else "동영상 파일명에 Screen 포함", MediaFilter(kind = MediaKind.VIDEO, folder = recordingFolder.orEmpty(), query = recordingQuery, sortField = SortField.SIZE), items.filter { it.kind == MediaKind.VIDEO && if (recordingFolder != null) it.relativePath == recordingFolder else it.displayName.contains(recordingQuery, true) }),
    )
}

@Composable
internal fun CleanupScreen(state: AppUiState, browse: (MediaFilter) -> Unit) {
    val suggestions = remember(state.items) { cleanupSuggestions(state.items) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionTitle("정리 후보", "필요한 파일인지 확인한 뒤 직접 선택해주세요.")
        suggestions.forEachIndexed { index, suggestion ->
            val deletable = suggestion.items.filter { it.canTrash() }
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(46.dp).background(if (index == 1) Peach else Mint, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        GalliIcon(if (suggestion.filter.kind == MediaKind.PHOTO) Icons.Rounded.Image else Icons.Rounded.Videocam, null, Modifier.size(31.dp))
                    }
                    Text(suggestion.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Text(suggestion.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("${suggestion.items.size}개 · ${bytesLabel(suggestion.items.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.headlineSmall)
                Text("즐겨찾기·보호 파일 제외 시 ${deletable.size}개 · ${bytesLabel(deletable.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.bodySmall, color = Muted)
                ActionButton("파일 살펴보기", { browse(suggestion.filter) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.ArrowForward)
            }
        }
        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GalliIcon(Icons.Rounded.Shield, null, Modifier.size(26.dp))
            Text("스크린샷과 화면 녹화는 폴더·파일명으로 찾은 후보입니다. 실제 내용과 다를 수 있습니다. 자동으로 삭제하지 않습니다.", modifier = Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SettingsScreen(state: AppUiState, vm: AppViewModel, requestAccess: () -> Unit, openSettings: () -> Unit, openTrash: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val folders = remember(state.items, state.protectedFolders) {
        val observed = state.items.groupBy { normalizeUiFolder(it.relativePath) }
        (observed.keys + state.protectedFolders).associateWith { observed[it].orEmpty() }.toSortedMap()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionTitle("설정", "파일은 안전하게, 분석은 기기 안에서.")
        SettingsSection("미디어 접근") {
            Text(when (state.access) { MediaAccess.NONE -> "접근 권한 없음"; MediaAccess.PARTIAL -> "일부 사진·동영상 접근 허용"; MediaAccess.FULL -> "전체 사진·동영상 접근 허용" }, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("접근 범위 변경", requestAccess, Modifier.weight(1f))
                ActionButton("앱 권한 설정", openSettings, Modifier.weight(1f))
            }
        }
        SettingsSection("휴지통") {
            Text("${state.trashed.size}개 · ${bytesLabel(state.trashed.sumOf { it.sizeBytes })}", color = Muted, style = MaterialTheme.typography.bodyMedium)
            ActionButton("휴지통 열기", openTrash, icon = Icons.Rounded.RestoreFromTrash)
        }
        SettingsSection("보호 폴더") {
            Text("폴더를 보호하면 해당 파일의 정리 선택이 차단됩니다. 즐겨찾기도 기본으로 보호합니다.", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (folders.isEmpty()) Text("미디어를 불러오면 폴더가 표시됩니다.", color = Muted)
            folders.forEach { (path, items) ->
                val protected = path in state.protectedFolders
                val inherited = state.protectedFolders.firstOrNull { it.isNotEmpty() && it != path && path.startsWith(it) }
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(path.trimEnd('/').ifEmpty { "기본 폴더" }, style = MaterialTheme.typography.bodyMedium)
                        Text("${items.size}개 · ${bytesLabel(items.sumOf { it.sizeBytes })}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (inherited != null) Text("상위 ${inherited.trimEnd('/')} 폴더에서 보호 중", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    ActionButton(if (protected) "보호 해제" else if (inherited != null) "상위 보호" else "보호", { vm.protectFolder(path, !protected) }, enabled = protected || inherited == null)
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
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GalliMascot(Modifier.size(38.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("GalliPick · 기기 내 미디어 정리", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("소중한 추억을 위한 작은 정리함", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("분석 캐시를 비울까요?") },
        text = { Text("다음 중복 검사에서 파일을 다시 분석합니다. 원본 파일은 삭제하지 않습니다.") },
        confirmButton = { TextAction("비우기", { vm.clearAnalysisCache(); confirmClear = false }) },
        dismissButton = { TextAction("취소", { confirmClear = false }) },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val icon = when (title) {
        "미디어 접근" -> Icons.Rounded.PhotoLibrary
        "휴지통" -> Icons.Rounded.RestoreFromTrash
        "보호 폴더" -> Icons.Rounded.Folder
        "분석 캐시" -> Icons.Rounded.CleaningServices
        else -> Icons.Rounded.Shield
    }
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, Line, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).background(Panel, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                GalliIcon(icon, null, Modifier.size(28.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}
