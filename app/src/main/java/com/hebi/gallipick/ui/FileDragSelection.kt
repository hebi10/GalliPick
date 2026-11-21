package com.hebi.gallipick.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val FILE_SELECTION_HOLD_MILLIS = 500L
private const val EDGE_SCROLL_INTERVAL_MILLIS = 16L

@Composable
internal fun Modifier.fileDragSelection(
    state: LazyGridState,
    sessionKey: Any?,
    itemsByKey: Map<String, MediaItem>,
    selected: Set<String>,
    addSelected: (List<MediaItem>) -> Unit,
): Modifier = fileDragSelection(state, sessionKey, selected, addSelected) {
    val layout = state.layoutInfo
    layout.visibleItemsInfo.mapNotNull { cell ->
        val key = cell.key as? String ?: return@mapNotNull null
        val item = itemsByKey[key] ?: return@mapNotNull null
        val left = cell.offset.x.toFloat()
        val top = (cell.offset.y - layout.viewportStartOffset).toFloat()
        item.visibleDragTarget(Rect(left, top, left + cell.size.width, top + cell.size.height), layout.viewportSize)
    }
}

@Composable
internal fun Modifier.fileDragSelection(
    state: LazyListState,
    sessionKey: Any?,
    itemsByKey: Map<String, MediaItem>,
    selected: Set<String>,
    addSelected: (List<MediaItem>) -> Unit,
): Modifier = fileDragSelection(state, sessionKey, selected, addSelected) {
    val layout = state.layoutInfo
    layout.visibleItemsInfo.mapNotNull { row ->
        val key = row.key as? String ?: return@mapNotNull null
        val item = itemsByKey[key] ?: return@mapNotNull null
        val top = (row.offset - layout.viewportStartOffset).toFloat()
        item.visibleDragTarget(Rect(0f, top, layout.viewportSize.width.toFloat(), top + row.size), layout.viewportSize)
    }
}

private data class FileDragTarget(val item: MediaItem, val bounds: Rect)

private fun MediaItem.visibleDragTarget(bounds: Rect, viewport: IntSize): FileDragTarget? {
    if (isTrashed || !canTrash()) return null
    val clipped = bounds.intersect(Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat()))
    return FileDragTarget(this, clipped).takeUnless { clipped.isEmpty }
}

@Composable
private fun Modifier.fileDragSelection(
    state: ScrollableState,
    sessionKey: Any?,
    selected: Set<String>,
    addSelected: (List<MediaItem>) -> Unit,
    visibleTargets: () -> List<FileDragTarget>,
): Modifier {
    val currentTargets by rememberUpdatedState(visibleTargets)
    val currentSelected by rememberUpdatedState(selected)
    val currentAddSelected by rememberUpdatedState(addSelected)
    return pointerInput(state, sessionKey) {
        val edgeWidth = 48.dp.toPx()
        val slowStep = 2.dp.toPx()
        val fastStep = 14.dp.toPx()
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Touch) return@awaitEachGesture
                val initial = currentTargets().firstOrNull { it.bounds.contains(down.position) }
                    ?: return@awaitEachGesture
                val heldPosition = awaitFileHold(down) ?: return@awaitEachGesture
                val held = currentTargets().firstOrNull { it.bounds.contains(heldPosition) }
                    ?: return@awaitEachGesture
                if (held.item.detailVersion() != initial.item.detailVersion()) return@awaitEachGesture

                val visited = currentSelected.toMutableSet()
                if (visited.add(held.item.uri)) currentAddSelected(listOf(held.item))

                fun selectAlong(from: Offset, to: Offset) {
                    val added = currentTargets().mapNotNull { target ->
                        if (target.item.uri in visited || target.item.uri in currentSelected) return@mapNotNull null
                        target.bounds.entryFraction(from, to)?.let { fraction -> fraction to target.item }
                    }.sortedBy { it.first }.mapNotNull { (_, item) -> item.takeIf { visited.add(it.uri) } }
                    if (added.isNotEmpty()) currentAddSelected(added)
                }

                var position = heldPosition
                var hasDragged = false
                var autoScroll: Job? = null
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        change.consume()
                        if (!change.pressed || event.changes.any { it.id != down.id && it.pressed }) break
                        val next = change.position
                        if (!hasDragged && (next - heldPosition).getDistance() < viewConfiguration.touchSlop) continue
                        hasDragged = true
                        selectAlong(position, next)
                        position = next

                        if (edgeScrollFraction(position, size, edgeWidth) == 0f) {
                            autoScroll?.cancel()
                        } else if (autoScroll?.isActive != true) {
                            autoScroll = launch {
                                while (true) {
                                    delay(EDGE_SCROLL_INTERVAL_MILLIS)
                                    val fraction = edgeScrollFraction(position, size, edgeWidth)
                                    if (fraction == 0f) break
                                    val delta = fraction * fastStep + if (fraction > 0f) slowStep else -slowStep
                                    if (abs(state.scrollBy(delta)) < 0.5f) break
                                    selectAlong(position, position)
                                }
                            }
                        }
                    }
                } finally {
                    autoScroll?.cancel()
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitFileHold(down: PointerInputChange): Offset? {
    var position = down.position
    val completedBeforeTimeout = withTimeoutOrNull(FILE_SELECTION_HOLD_MILLIS) {
        var holding = true
        while (holding) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed || change.isConsumed ||
                (change.position - down.position).getDistance() >= viewConfiguration.touchSlop ||
                event.changes.any { it.id != down.id && it.pressed }
            ) break
            position = change.position
            val final = awaitPointerEvent(PointerEventPass.Final)
            holding = final.changes.none { it.id == down.id && it.isConsumed }
        }
        Unit
    }
    return position.takeIf { completedBeforeTimeout == null }
}

private fun edgeScrollFraction(position: Offset, size: IntSize, edgeWidth: Float): Float {
    val edge = minOf(edgeWidth, size.height / 3f)
    if (edge <= 0f || position.x !in 0f..size.width.toFloat()) return 0f
    return when {
        position.y < edge -> ((position.y - edge) / edge).coerceAtLeast(-1f)
        position.y > size.height - edge -> ((position.y - (size.height - edge)) / edge).coerceAtMost(1f)
        else -> 0f
    }
}

// A pointer event may cross several cells. Order hits along the path even when it arrives as one move.
private fun Rect.entryFraction(start: Offset, end: Offset): Float? {
    val x = overlapAlongAxis(start.x, end.x, left, right) ?: return null
    val y = overlapAlongAxis(start.y, end.y, top, bottom) ?: return null
    val entry = maxOf(0f, x.start, y.start)
    val exit = minOf(1f, x.endInclusive, y.endInclusive)
    return entry.takeIf { it <= exit }
}

private fun overlapAlongAxis(start: Float, end: Float, low: Float, high: Float): ClosedFloatingPointRange<Float>? {
    val delta = end - start
    if (delta == 0f) return if (start >= low && start < high) 0f..1f else null
    val near = (low - start) / delta
    val far = (high - start) / delta
    return minOf(near, far)..maxOf(near, far)
}
