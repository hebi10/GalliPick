package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.PhotoOcrIndexPolicy
import com.hebi.gallipick.domain.SortField

@Composable
internal fun FileSectionHeader(title: String, count: Int, dateField: DateField?, includesAddedDate: Boolean = false) {
    if (dateField != null) {
        DateGroupHeader(title, count, dateField, includesAddedDate)
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("${count}개", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
internal fun DateGroupHeader(date: String, count: Int, field: DateField, includesAddedDate: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = Ink)
            if (field == DateField.CAPTURED && includesAddedDate) {
                Text("촬영 정보가 없는 기기 파일은 추가일 기준", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        Text("${count}개", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
internal fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    toggle: () -> Unit,
    detail: () -> Unit,
    sortField: SortField,
    listView: Boolean = false,
    dateMs: Long = item.capturedAt,
    tabletGrid: Boolean = false,
    ocrText: String? = null,
    ocrQuery: String = "",
    hasCloudOriginal: Boolean = false,
    allowSelection: Boolean = true,
    selectionMode: Boolean,
) {
    var tileFocused by remember(item.uri) { mutableStateOf(false) }
    val selectable = allowSelection && !item.isTrashed && item.canTrash()
    val showSelection = selectionMode && allowSelection
    val tileAction = Modifier.onFocusChanged { tileFocused = it.isFocused }.then(
        if (selectionMode && selectable) {
            Modifier.semantics { contentDescription = "${item.displayName} 선택" }
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { toggle() })
        } else {
            val selectionAction = if (selectable) Modifier.semantics {
                onLongClick(label = "선택 시작") {
                    toggle()
                    true
                }
            } else Modifier
            selectionAction.clickable(
                role = Role.Button,
                onClickLabel = "파일 상세 열기",
                onClick = detail,
            )
        },
    )
    val showOutline = (selected && showSelection) || tileFocused
    if (listView) {
        Row(
            Modifier.fillMaxWidth().galliCardSurface()
                .then(if (showOutline) Modifier.border(
                    2.dp, Primary, RoundedCornerShape(GalliUiMetrics.cardCorner),
                ) else Modifier)
                .then(tileAction).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(76.dp)) {
                MediaThumbnail(item, Modifier.fillMaxSize(), contentDescription = null)
                if (showSelection) SelectionIndicator(selected, selectable,
                    Modifier.align(Alignment.TopStart).padding(4.dp))
                if (hasCloudOriginal) CloudFileBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(bytesLabel(item.sizeBytes) + " · " + dateLabel(dateMs).substringBefore(" "),
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (ocrText != null) {
                    Text("인식된 글자: " + PhotoOcrIndexPolicy.snippet(ocrText, ocrQuery),
                        style = MaterialTheme.typography.labelSmall, color = Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (item.kind == MediaKind.VIDEO) {
                    Text(durationLabel(item.durationMs), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().then(
            if (tabletGrid) Modifier.testTag("tablet-media-${item.id}") else Modifier,
        ).then(tileAction), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                MediaThumbnail(item, Modifier.fillMaxSize(), contentDescription = null)
                if (showOutline) {
                    Box(Modifier.matchParentSize().border(2.dp, Primary)
                        .padding(2.dp).border(1.dp, AppSurface))
                }
                if (showSelection) SelectionIndicator(selected, selectable,
                    Modifier.align(Alignment.TopStart).padding(4.dp).testTag("grid-selection-indicator"))
                if (hasCloudOriginal) CloudFileBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
                if (item.kind == MediaKind.VIDEO) {
                    Text(durationLabel(item.durationMs), color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(Ink, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (item.isFavorite || item.isProtected) {
                    GalliIcon(
                        if (item.isFavorite) Icons.Outlined.Star else Icons.Outlined.Shield,
                        if (item.isFavorite) "즐겨찾기" else "보호됨",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                            .background(Ink, RoundedCornerShape(8.dp)).padding(4.dp).size(16.dp),
                    )
                }
            }
            Text(item.displayName, style = if (tabletGrid) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.labelSmall, maxLines = if (tabletGrid) 2 else 1,
                overflow = if (tabletGrid) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth())
            if (tabletGrid) {
                Text(bytesLabel(item.sizeBytes), style = MaterialTheme.typography.labelSmall,
                    color = Muted, maxLines = 1)
            }
            if (ocrText != null) {
                Text("인식된 글자: " + PhotoOcrIndexPolicy.snippet(ocrText, ocrQuery, 72),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
