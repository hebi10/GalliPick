package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.GalleryEntry
import com.hebi.gallipick.data.cloud.GalleryEntryKey
import com.hebi.gallipick.data.cloud.GalleryFileQuery
import com.hebi.gallipick.data.cloud.GallerySource
import com.hebi.gallipick.data.cloud.GallerySourceFilter
import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField

internal fun galleryItemKey(entry: GalleryEntry): String = when (val key = entry.key) {
    is GalleryEntryKey.Device -> "device:" + key.uri
    is GalleryEntryKey.Cloud -> "cloud:" + key.accountUid + ":" + key.uploadId
}

@Composable
internal fun UnifiedFilesList(
    entries: List<GalleryEntry>,
    filter: MediaFilter,
    source: GallerySourceFilter,
    gridView: Boolean,
    wide: Boolean,
    availableWidth: Dp,
    gridState: LazyGridState,
    selected: Set<String>,
    toggleDevice: (String) -> Unit,
    openDevice: (String) -> Unit,
    openCloud: (CloudOriginal) -> Unit,
    backup: CloudBackup? = null,
    accountUid: String? = null,
    modifier: Modifier = Modifier,
) {
    val groups = remember(entries, filter.dateField, filter.sortField, filter.sortDirection, filter.groupByDate) {
        if (!filter.groupByDate) emptyList() else {
            val field = when (filter.dateField) {
                DateField.CAPTURED -> SortField.CAPTURED
                DateField.ADDED -> SortField.ADDED
                DateField.MODIFIED -> SortField.MODIFIED
            }
            val ascending = filter.sortField != field || filter.sortDirection == SortDirection.ASCENDING
            GalleryFileQuery.groupByDate(entries, filter.dateField, ascending)
        }
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val tabletGrid = wide && availableWidth >= 720.dp

    if (gridView) {
        LazyVerticalGrid(
            columns = if (tabletGrid) GridCells.Fixed(tabletMediaGridColumns(availableWidth, fontScale))
                else GridCells.Adaptive(if (fontScale >= 1.5f) 132.dp else 108.dp),
            state = gridState,
            modifier = modifier.semantics { contentDescription = "기기와 클라우드 미디어 파일 목록" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        ) {
            if (filter.groupByDate) {
                groups.forEach { group ->
                    item(key = "date:${group.date}", span = { GridItemSpan(maxLineSpan) }) {
                        DateGroupHeader(group.date, group.items.size, filter.dateField)
                    }
                    items(group.items, key = ::galleryItemKey) { item ->
                        UnifiedMediaTile(item, source, selected, toggleDevice, openDevice, openCloud, false, tabletGrid,
                            backup = backup, accountUid = accountUid)
                    }
                }
            } else {
                items(entries, key = ::galleryItemKey) { item ->
                    UnifiedMediaTile(item, source, selected, toggleDevice, openDevice, openCloud, false, tabletGrid,
                        backup = backup, accountUid = accountUid)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.semantics { contentDescription = "기기와 클라우드 미디어 파일 목록" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        ) {
            if (filter.groupByDate) {
                groups.forEach { group ->
                    item(key = "date:${group.date}") {
                        DateGroupHeader(group.date, group.items.size, filter.dateField)
                    }
                    listItems(group.items, key = ::galleryItemKey) { item ->
                        UnifiedMediaTile(item, source, selected, toggleDevice, openDevice, openCloud, true, false, filter.dateField,
                            backup, accountUid)
                    }
                }
            } else {
                listItems(entries, key = ::galleryItemKey) { item ->
                    UnifiedMediaTile(item, source, selected, toggleDevice, openDevice, openCloud, true, false, filter.dateField,
                        backup, accountUid)
                }
            }
        }
    }
}

@Composable
private fun UnifiedMediaTile(
    entry: GalleryEntry,
    sourceFilter: GallerySourceFilter,
    selected: Set<String>,
    toggleDevice: (String) -> Unit,
    openDevice: (String) -> Unit,
    openCloud: (CloudOriginal) -> Unit,
    listView: Boolean,
    tabletGrid: Boolean,
    dateField: DateField = DateField.CAPTURED,
    backup: CloudBackup? = null,
    accountUid: String? = null,
) {
    val local = entry.device
    if (local != null) {
        MediaTile(
            item = local,
            selected = local.uri in selected && sourceFilter != GallerySourceFilter.CLOUD,
            toggle = { toggleDevice(local.uri) },
            detail = { openDevice(local.uri) },
            sortField = SortField.CAPTURED,
            listView = listView,
            dateMs = GalleryFileQuery.dateOf(entry, dateField) ?: 0L,
            tabletGrid = tabletGrid,
            sourceLabel = if (entry.source == GallerySource.BOTH) "기기·클라우드" else "기기",
            allowSelection = sourceFilter != GallerySourceFilter.CLOUD,
        )
    } else {
        val original = checkNotNull(entry.cloud)
        CloudOnlyTile(original, listView, tabletGrid, backup, accountUid) { openCloud(original) }
    }
}

@Composable
private fun CloudOnlyTile(
    original: CloudOriginal, listView: Boolean, tabletGrid: Boolean,
    backup: CloudBackup?, accountUid: String?, open: () -> Unit,
) {
    if (listView) {
        Row(
            Modifier.fillMaxWidth().galliCardSurface().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudThumbnail(original, Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)), backup, accountUid, open)
            Column(
                Modifier.weight(1f).plainClick(action = open),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(original.displayName, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(bytesLabel(original.sizeBytes) + " · 클라우드",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CloudThumbnail(original, Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)), backup, accountUid, open)
            Text(original.displayName, style = if (tabletGrid) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.labelSmall, maxLines = if (tabletGrid) 2 else 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().plainClick(action = open))
            if (tabletGrid) Text(bytesLabel(original.sizeBytes), style = MaterialTheme.typography.labelSmall,
                color = Muted, maxLines = 1)
        }
    }
}

@Composable
private fun CloudThumbnail(
    original: CloudOriginal, modifier: Modifier,
    backup: CloudBackup?, accountUid: String?, open: () -> Unit,
) {
    if (original.mimeType.startsWith("image/")) {
        CloudPhotoThumbnail(original, accountUid, backup, modifier, open)
    } else {
        Box(modifier.background(Panel).plainClick(action = open)
            .semantics { contentDescription = "클라우드 동영상: ${original.displayName}" }) {
            GalliIcon(Icons.Outlined.Movie, null, Modifier.align(Alignment.Center).size(32.dp), tint = Muted)
            Row(
                Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(AppSurface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                GalliIcon(Icons.Outlined.Cloud, null, Modifier.size(13.dp), tint = Ink)
                Text("클라우드", style = MaterialTheme.typography.labelSmall, color = Ink)
            }
        }
    }
}
