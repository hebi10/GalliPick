package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaQuery
import com.hebi.gallipick.domain.PhotoOcrEntry

@Composable
internal fun DeviceFilesList(
    gridView: Boolean,
    requestedColumns: Int?,
    availableGridWidth: Dp,
    fontScale: Float,
    tabletGrid: Boolean,
    gridState: LazyGridState,
    visibleDeviceItems: List<MediaItem>,
    filtered: List<MediaItem>,
    fileSections: List<FileSection<MediaItem>>,
    activeFilter: MediaFilter,
    actions: FilesScreenActions,
    selectedUris: Set<String>,
    selectionMode: Boolean,
    dragSelectionSession: Any,
    backedUpDeviceUris: Set<String>,
    showOcrText: Boolean,
    ocrRows: Map<String, PhotoOcrEntry>,
    ocrQuery: String,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier,
) {
    val dragItems = remember(visibleDeviceItems) { visibleDeviceItems.associateBy(MediaItem::uri) }
    if (gridView) {
        if (requestedColumns != null) {
            val applied = resolvedFileGridColumns(
                requestedColumns, availableGridWidth, fontScale, tabletGrid,
            )
            Text("한 줄 ${applied}개 표시 · 설정 ${requestedColumns}개",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = fileGridCells(requestedColumns, availableGridWidth, fontScale, tabletGrid),
            state = gridState,
            modifier = modifier
                .fileDragSelection(gridState, dragSelectionSession, dragItems,
                    selectedUris, actions.addSelected)
                .semantics { contentDescription = "미디어 파일 목록" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        ) {
            if (fileSections.isNotEmpty()) {
                fileSections.forEach { group ->
                    item(key = "section:${group.title}", span = { GridItemSpan(maxLineSpan) }) {
                        FileSectionHeader(group.title, group.items.size, activeFilter.dateField.takeIf { group.isDate },
                            group.isDate && group.items.any { it.dateTakenMs <= 0 && it.dateAddedMs > 0 })
                    }
                    items(group.items, key = { it.uri }) { item ->
                        MediaTile(item, item.uri in selectedUris,
                            { actions.toggleSelection(item.uri) }, { onOpen(item) },
                            activeFilter.sortField, tabletGrid = tabletGrid,
                            selectionMode = selectionMode,
                            ocrText = if (showOcrText)
                                ocrRows[item.uri]?.recognizedText else null,
                            ocrQuery = ocrQuery, hasCloudOriginal = item.uri in backedUpDeviceUris)
                    }
                }
            } else {
                items(filtered, key = { it.uri }) { item ->
                    MediaTile(item, item.uri in selectedUris,
                        { actions.toggleSelection(item.uri) }, { onOpen(item) },
                        activeFilter.sortField, tabletGrid = tabletGrid,
                        selectionMode = selectionMode,
                        ocrText = if (showOcrText)
                            ocrRows[item.uri]?.recognizedText else null,
                        ocrQuery = ocrQuery, hasCloudOriginal = item.uri in backedUpDeviceUris)
                }
            }
        }
    } else {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = modifier
                .fileDragSelection(listState, dragSelectionSession, dragItems,
                    selectedUris, actions.addSelected)
                .semantics { contentDescription = "미디어 파일 목록" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        ) {
            if (fileSections.isNotEmpty()) {
                fileSections.forEach { group ->
                    item(key = "section:${group.title}") {
                        FileSectionHeader(group.title, group.items.size, activeFilter.dateField.takeIf { group.isDate },
                            group.isDate && group.items.any { it.dateTakenMs <= 0 && it.dateAddedMs > 0 })
                    }
                    listItems(group.items, key = { it.uri }) { item ->
                        MediaTile(item, item.uri in selectedUris,
                            { actions.toggleSelection(item.uri) }, { onOpen(item) },
                            activeFilter.sortField, listView = true,
                            selectionMode = selectionMode,
                            dateMs = MediaQuery.dateOf(item, activeFilter.dateField),
                            ocrText = if (showOcrText)
                                ocrRows[item.uri]?.recognizedText else null,
                            ocrQuery = ocrQuery, hasCloudOriginal = item.uri in backedUpDeviceUris)
                    }
                }
            } else {
                listItems(filtered, key = { it.uri }) { item ->
                    MediaTile(item, item.uri in selectedUris,
                        { actions.toggleSelection(item.uri) }, { onOpen(item) },
                        activeFilter.sortField, listView = true,
                        selectionMode = selectionMode,
                        ocrText = if (showOcrText)
                            ocrRows[item.uri]?.recognizedText else null,
                        ocrQuery = ocrQuery, hasCloudOriginal = item.uri in backedUpDeviceUris)
                }
            }
        }
    }
}
