package com.hebi.gallipick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Ink = Color(0xFF25483F)
internal val Muted = Color(0xFF597267)
internal val Line = Color(0xFFDCE6DF)
internal val Panel = Color(0xFFEEF4EE)
internal val AppBackground = Color(0xFFFAF9F5)
internal val Mint = Color(0xFFCDE6D8)
internal val Peach = Color(0xFFF5D6BD)

internal enum class Destination(val title: String, val icon: ImageVector) {
    HOME("홈", Icons.Rounded.Home), FILES("파일", Icons.Rounded.GridView),
    DUPLICATES("중복", Icons.Rounded.CopyAll), CLEANUP("정리", Icons.Rounded.CleaningServices),
    SETTINGS("설정", Icons.Rounded.Settings),
}

@Composable
fun GalliPickApp(
    vm: AppViewModel,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onTrash: (List<MediaItem>) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onRestore: (List<MediaItem>) -> Unit = {},
) {
    val state by vm.ui.collectAsState()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showTrash || destination != Destination.HOME) {
        if (showTrash) showTrash = false else destination = Destination.HOME
    }
    val navigateFiles: (com.hebi.gallipick.domain.MediaFilter) -> Unit = {
        vm.setFilter(it)
        destination = Destination.FILES
    }
    GalliPickTheme {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val wide = maxWidth >= 650.dp
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GalliMascot(Modifier.size(40.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("GalliPick", style = MaterialTheme.typography.titleLarge)
                            Text("말랑한 추억 정리함", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        if (state.loading) Text("불러오는 중", style = MaterialTheme.typography.labelSmall, color = Muted)
                        IconAction("새로고침", Icons.Rounded.Refresh, onClick = vm::refresh)
                    }
                    if (state.message != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                .background(Color.White, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
                                .padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(state.message.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconAction("알림 닫기", Icons.Rounded.Close, onClick = vm::dismissMessage)
                        }
                    }
                    if (state.access == MediaAccess.PARTIAL) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                .background(Panel, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("허용된 사진·동영상만 표시 중", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextAction("접근 변경", onClick = onRequestPermission)
                        }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (wide) {
                            Column(Modifier.width(100.dp).padding(start = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Destination.entries.forEach { NavItem(it, destination == it, { showTrash = false; destination = it }, Modifier.fillMaxWidth(), vertical = true) }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (showTrash) TrashScreen(state.trashed, onRestore, { showTrash = false })
                            else when (destination) {
                                Destination.HOME -> HomeScreen(state, onRequestPermission, navigateFiles, { destination = Destination.DUPLICATES }, { destination = Destination.CLEANUP })
                                Destination.FILES -> FilesScreen(state, vm, wide, onTrash, onOpenMedia, onRequestPermission)
                                Destination.DUPLICATES -> DuplicatesScreen(state, vm, onTrash, onOpenMedia)
                                Destination.CLEANUP -> CleanupScreen(state, navigateFiles)
                                Destination.SETTINGS -> SettingsScreen(state, vm, onRequestPermission, onOpenSettings, { showTrash = true })
                            }
                        }
                    }
                    if (!wide) {
                        Row(
                            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Destination.entries.forEach { NavItem(it, destination == it, { showTrash = false; destination = it }, Modifier.weight(1f), vertical = true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun GalliPickTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink, onPrimary = Color.White, secondary = Muted, onSecondary = Color.White,
            tertiary = Ink, onTertiary = Color.White, background = AppBackground, onBackground = Ink,
            primaryContainer = Mint, onPrimaryContainer = Ink, inversePrimary = Mint,
            secondaryContainer = Panel, onSecondaryContainer = Ink,
            tertiaryContainer = Peach, onTertiaryContainer = Ink,
            surface = AppBackground, onSurface = Ink, surfaceVariant = Panel, onSurfaceVariant = Muted,
            inverseSurface = Ink, inverseOnSurface = Color.White, surfaceTint = Color.Transparent,
            surfaceBright = Color.White, surfaceDim = Line,
            surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Line,
            surfaceContainerLow = AppBackground, surfaceContainerLowest = Color.White,
            outline = Muted, outlineVariant = Line, scrim = Ink, error = Color(0xFF944837), onError = Color.White,
            errorContainer = Color(0xFFF9E7DC), onErrorContainer = Color(0xFF753B2D),
        ),
        shapes = Shapes(RoundedCornerShape(10.dp), RoundedCornerShape(14.dp), RoundedCornerShape(18.dp), RoundedCornerShape(24.dp), RoundedCornerShape(28.dp)),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        ),
    ) { CompositionLocalProvider(LocalRippleConfiguration provides null, content = content) }
}

@Composable
private fun NavItem(destination: Destination, selected: Boolean, onClick: () -> Unit, modifier: Modifier, vertical: Boolean) {
    Column(
        modifier.semantics { this.selected = selected; role = Role.Tab }.clip(RoundedCornerShape(20.dp))
            .plainClick(action = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(width = 54.dp, height = 34.dp).background(if (selected) Mint else Color.Transparent, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            GalliIcon(destination.icon, contentDescription = null, tint = if (selected) Ink else Muted, modifier = Modifier.size(27.dp))
        }
        Text(destination.title, color = if (selected) Ink else Muted, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
internal fun Modifier.plainClick(enabled: Boolean = true, action: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = action,
)

@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val background = if (filled && enabled) Ink else if (!enabled) Panel else Color.White
    val foreground = if (filled && enabled) Color.White else if (!enabled) Muted else Ink
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape).background(background)
            .border(BorderStroke(1.dp, if (filled && enabled) Ink else Line), shape)
            .semantics { role = Role.Button }.plainClick(enabled, onClick).padding(horizontal = 15.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { GalliIcon(icon, null, tint = foreground, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp)) }
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).semantics { role = Role.Button }.plainClick(action = onClick).padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Ink, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun IconAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(CircleShape).semantics { role = Role.Button }.plainClick(action = onClick), contentAlignment = Alignment.Center) {
        GalliIcon(icon, label, modifier = Modifier.size(23.dp))
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun EmptyState(title: String, body: String, icon: ImageVector = Icons.Rounded.PhotoLibrary, action: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            GalliMascot(Modifier.size(92.dp))
            Box(Modifier.align(Alignment.BottomEnd).size(36.dp).background(Mint, CircleShape).border(2.dp, AppBackground, CircleShape), contentAlignment = Alignment.Center) {
                GalliIcon(icon, null, Modifier.size(23.dp), tint = Ink)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

internal fun bytesLabel(bytes: Long): String {
    val amount = bytes.coerceAtLeast(0).toDouble()
    return when {
        amount >= 1_099_511_627_776 -> String.format(Locale.KOREA, "%.1f TB", amount / 1_099_511_627_776)
        amount >= 1_073_741_824 -> String.format(Locale.KOREA, "%.1f GB", amount / 1_073_741_824)
        amount >= 1_048_576 -> String.format(Locale.KOREA, "%.1f MB", amount / 1_048_576)
        amount >= 1024 -> String.format(Locale.KOREA, "%.0f KB", amount / 1024)
        else -> "${amount.toLong()} B"
    }
}

internal fun durationLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun dateLabel(ms: Long): String = if (ms <= 0) "정보 없음" else SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(ms))
internal fun MediaItem.canTrash(): Boolean = !isFavorite && !isProtected
internal fun normalizeUiFolder(path: String): String = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
internal fun MediaItem.hasFolderProtection(folders: Set<String>): Boolean {
    val path = normalizeUiFolder(relativePath)
    return folders.any { path == it || (it.isNotEmpty() && path.startsWith(it)) }
}
