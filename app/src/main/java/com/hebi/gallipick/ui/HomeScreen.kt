package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.R
import com.hebi.gallipick.domain.CleanupHistoryAction
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.PhotoReviewInboxPolicy
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField

@Composable
internal fun HomeScreen(
    state: AppUiState,
    requestAccess: () -> Unit,
    browse: (MediaFilter) -> Unit,
    duplicates: () -> Unit,
    cleanup: () -> Unit,
    resumeReview: (() -> Unit)? = null,
    openAdRemoval: (() -> Unit)? = null,
    openHistory: (() -> Unit)? = null,
    openTrash: (() -> Unit)? = null,
    openPermissionSettings: (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val tabletLayout = maxWidth >= 940.dp * fontScale
        val wide = maxWidth >= 720.dp * fontScale
        val twoColumns = maxWidth >= 330.dp * fontScale
        val recent = remember(state.cleanupHistory) { state.cleanupHistory.maxByOrNull { it.occurredAtMs } }
        val prioritizeStorage = !wide && recent != null && state.access != MediaAccess.NONE
        val statusCards: @Composable () -> Unit = {
            if (state.access == MediaAccess.NONE) HomePermissionNotice(requestAccess, openPermissionSettings)
            if (prioritizeStorage) HomeStorageSummary(state)
            if (recent != null && openHistory != null) {
                val action = if (recent.action == CleanupHistoryAction.RESTORE) "복원" else "휴지통 이동"
                CleanupNavigationRow("최근 정리 내역", "$action ${recent.confirmedCount}개 · ${dateLabel(recent.occurredAtMs)}",
                    Icons.Outlined.History, "최근 정리 기록 보기", openHistory)
            }
            if (state.trashed.isNotEmpty() && openTrash != null) {
                CleanupNavigationRow("휴지통·복원", "접근 가능한 파일 ${state.trashed.size}개",
                    Icons.Outlined.RestoreFromTrash, "홈에서 휴지통 열기", openTrash)
            }
            val reviewGroups = state.similarGroups + state.burstGroups
            if (resumeReview != null && state.access != MediaAccess.NONE &&
                PhotoReviewInboxPolicy.pending(reviewGroups, state.reviewEntries, state.protectedFolders)) {
                CleanupNavigationRow(
                    title = "사진 검토 이어서",
                    subtitle = "남김·정리 후보·검토 보류 상태가 저장되어 있습니다.",
                    icon = Icons.Outlined.CollectionsBookmark,
                    description = "저장된 사진 검토함 이어서 열기",
                    open = resumeReview,
                )
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxHeight()
                .widthIn(max = 1220.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tabletLayout) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        Modifier.weight(1.1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        HomeWelcome(cleanup)
                        statusCards()
                        if (openAdRemoval != null) {
                            CloudActionGroup(CloudRoute.BILLING_SIGN_IN) { openAdRemoval() }
                        }
                        HomeStorageSummary(state, showBreakdown = true)
                    }
                    HomeQuickActions(
                        browse, duplicates, Modifier.weight(1f),
                        twoColumns = true, tabletTiles = true,
                    )
                }
            } else {
                HomeWelcome(cleanup)
                statusCards()
                if (openAdRemoval != null) {
                    CloudActionGroup(CloudRoute.BILLING_SIGN_IN) { openAdRemoval() }
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
                    if (!prioritizeStorage) HomeStorageSummary(state)
                    HomeQuickActions(browse, duplicates, twoColumns = twoColumns)
                }
            }
        }
    }
}

@Composable
private fun HomePermissionNotice(requestAccess: () -> Unit, openPermissionSettings: (() -> Unit)?) {
    GalliCard(Modifier.fillMaxWidth()) {
        Text("사진·동영상 접근이 필요합니다", style = MaterialTheme.typography.titleSmall)
        Text(
            "기기 안의 파일을 살펴보려면 접근을 허용해 주세요. 파일 이동 전에는 Android 확인창에서 다시 승인합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        ActionButton(if (openPermissionSettings == null) "사진·동영상 선택" else "앱 권한 설정",
            openPermissionSettings ?: requestAccess, Modifier.fillMaxWidth(), filled = true)
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
                        "사진과 영상을\n깔끔하게 정리",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                Text("정리 도구에서 남길 파일을 직접 고르세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                ActionButton(
                    "정리 도구 보기", cleanup, Modifier.fillMaxWidth(),
                    filled = true, icon = Icons.Outlined.ArrowForward,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(GalliUiMetrics.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GalliIllustration(GalliScene.ORGANIZE, Modifier.size(92.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(GalliUiMetrics.cardSpacing),
                ) {
                    Text(
                        "사진과 영상을\n깔끔하게 정리",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text("정리 도구에서 남길 파일을 직접 고르세요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    ActionButton(
                        "정리 도구 보기", cleanup, Modifier.widthIn(max = 260.dp).fillMaxWidth(),
                        filled = true, icon = Icons.Outlined.ArrowForward,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeStorageSummary(
    state: AppUiState,
    modifier: Modifier = Modifier,
    showBreakdown: Boolean = false,
) {
    val total = state.totalStorageBytes.coerceAtLeast(0L)
    val used = (total - state.freeStorageBytes).coerceIn(0L, total)
    val usedFraction = if (total > 0L) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val usedPercent = (usedFraction * 100f).toInt()
    val mediaCountReady = state.access != MediaAccess.NONE && state.catalogError == null && !state.loading &&
        (state.catalogReady || state.items.isNotEmpty())
    Column(
        modifier.galliCardSurface(AppSurface)
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
                GalliIcon(Icons.Outlined.Storage, null, Modifier.size(24.dp), tint = Primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("기기 저장공간", style = MaterialTheme.typography.titleSmall)
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
            when {
                state.access == MediaAccess.NONE -> "접근을 허용하면 관리할 파일을 확인할 수 있습니다."
                state.catalogError != null -> "기기 파일 수를 확인하지 못했습니다."
                !mediaCountReady -> "사진·동영상 목록 확인 중"
                state.access == MediaAccess.PARTIAL -> "선택해 허용한 미디어 " + state.items.size + "개"
                else -> "접근 가능한 미디어 " + state.items.size + "개"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        if (showBreakdown && mediaCountReady) {
            val photoCount = remember(state.items) { state.items.count { it.kind == MediaKind.PHOTO } }
            val videoCount = remember(state.items) { state.items.count { it.kind == MediaKind.VIDEO } }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("사진 ${photoCount}개", style = MaterialTheme.typography.bodySmall, color = Ink)
                Text("동영상 ${videoCount}개", style = MaterialTheme.typography.bodySmall, color = Ink)
            }
        }
    }
}

private const val DUPLICATE_MEDIA_SEARCH_TITLE = "사진·동영상 중복 찾기"

private data class HomeShortcut(
    val title: String,
    val icon: Painter,
    val onClick: () -> Unit,
)

@Composable
private fun HomeQuickActions(
    browse: (MediaFilter) -> Unit,
    duplicates: () -> Unit,
    modifier: Modifier = Modifier,
    twoColumns: Boolean,
    tabletTiles: Boolean = false,
) {
    val oneYearAgo = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
    val shortcuts = listOf(
        HomeShortcut("파일 둘러보기", painterResource(R.drawable.gp_home_photo), onClick = { browse(MediaFilter()) }),
        HomeShortcut(DUPLICATE_MEDIA_SEARCH_TITLE, rememberVectorPainter(Icons.Outlined.CopyAll), onClick = duplicates),
        HomeShortcut("용량 큰 파일", painterResource(R.drawable.gp_home_large_file), onClick = {
            browse(MediaFilter(minSizeBytes = 100L * 1024 * 1024, sortField = SortField.SIZE))
        }),
        HomeShortcut("오래된 파일", painterResource(R.drawable.gp_home_old_files), onClick = {
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
                        HomeShortcutCard(shortcut, Modifier.weight(1f), tabletTiles = tabletTiles)
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
private fun HomeShortcutCard(shortcut: HomeShortcut, modifier: Modifier = Modifier, compact: Boolean = false, tabletTiles: Boolean = false) {
    val surface = AppSurface
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
            GalliIcon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = Muted)
        }
    } else {
        Column(
            modifier.heightIn(min = if (tabletTiles) 158.dp else 124.dp).galliCardSurface(surface)
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
    Icon(
        painter = shortcut.icon,
        contentDescription = null,
        tint = Ink,
        modifier = modifier,
    )
}
