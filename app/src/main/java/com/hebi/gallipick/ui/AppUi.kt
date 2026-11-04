package com.hebi.gallipick.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    fun navigate(destinationValue: Destination) {
        if (destinationValue == Destination.DUPLICATES) vm.prepareDuplicateReview()
        showTrash = false
        destination = destinationValue
    }
    BackHandler(enabled = showTrash || destination != Destination.HOME) {
        if (showTrash) showTrash = false else destination = Destination.HOME
    }
    val navigateFiles: (com.hebi.gallipick.domain.MediaFilter) -> Unit = {
        vm.setFilter(it)
        destination = Destination.FILES
    }
    Surface(Modifier.fillMaxSize(), color = AppBackground) {
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val wide = maxWidth >= 650.dp
            Column(Modifier.fillMaxSize()) {
                ScrollAwayHeaderLayout(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    resetKey = Triple(destination, showTrash, state.filter),
                    header = {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GalliMascot(Modifier.size(32.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("GalliPick", style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
                            if (state.loading) Text("불러오는 중", style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
                            IconAction("새로고침", Icons.Rounded.Refresh, onClick = vm::refresh)
                        }
                    },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (state.message != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                    .background(AppSurface, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
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
                                AppDestinationRail(destination, ::navigate)
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (showTrash) TrashScreen(state.trashed, onRestore, { showTrash = false })
                                else when (destination) {
                                    Destination.HOME -> HomeScreen(state, onRequestPermission, navigateFiles, { navigate(Destination.DUPLICATES) }, { navigate(Destination.CLEANUP) })
                                    Destination.FILES -> FilesScreen(state, vm, wide, onTrash, onOpenMedia, onRequestPermission)
                                    Destination.DUPLICATES -> DuplicatesScreen(state, vm, onTrash, onOpenMedia, onRequestPermission)
                                    Destination.CLEANUP -> CleanupScreen(state, navigateFiles)
                                    Destination.SETTINGS -> SettingsScreen(state, vm, onRequestPermission, onOpenSettings, { showTrash = true })
                                }
                            }
                        }
                    }
                }
                if (!wide) {
                    Row(
                        Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Destination.entries.forEach { NavItem(it, destination == it, { navigate(it) }, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppDestinationRail(current: Destination, onNavigate: (Destination) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.width(100.dp).fillMaxHeight()) {
        // Base the rail on its remaining height after app banners/header, including text scaling.
        // The compact layout fits five 48 dp tabs in 272 dp; scrolling covers smaller windows.
        val compact = maxHeight < 420.dp * LocalDensity.current.fontScale
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).selectableGroup()
                .padding(start = 12.dp, top = if (compact) 8.dp else 12.dp, bottom = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Destination.entries.forEach { destination ->
                NavItem(destination, current == destination, { onNavigate(destination) }, Modifier.fillMaxWidth(), compact)
            }
        }
    }
}

@Composable
private fun NavItem(destination: Destination, selected: Boolean, onClick: () -> Unit, modifier: Modifier, compact: Boolean = false) {
    Column(
        modifier.heightIn(min = 48.dp).semantics { this.selected = selected; role = Role.Tab }.clip(RoundedCornerShape(20.dp))
            .plainClick(action = onClick).padding(vertical = if (compact) 2.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(width = 54.dp, height = if (compact) 24.dp else 34.dp)
                .background(if (selected) Mint else Color.Transparent, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            GalliNavigationIcon(destination.icon, modifier = Modifier.size(if (compact) 22.dp else 29.dp))
        }
        Text(destination.title, color = if (selected) Ink else Muted, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
internal fun Modifier.plainClick(enabled: Boolean = true, action: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() }, indication = ripple(), enabled = enabled, onClick = action,
)

@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val background = if (filled && enabled) Primary else if (!enabled) Panel else AppSurface
    val foreground = if (filled && enabled) OnPrimary else if (!enabled) Muted else Ink
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape).background(background)
            .border(BorderStroke(1.dp, if (filled && enabled) Primary else Line), shape)
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
            GalliIllustration(GalliScene.EMPTY, Modifier.size(92.dp))
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
