package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ABPhotoTransform(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun zoomBy(factor: Float): ABPhotoTransform = copy(zoom = (zoom * factor).coerceIn(1f, 6f)).let {
        if (it.zoom == 1f) it.copy(x = 0f, y = 0f) else it
    }

    fun panBy(pan: Offset, viewport: IntSize, bitmap: Bitmap, factor: Float): ABPhotoTransform {
        val nextZoom = (zoom * factor).coerceIn(1f, 6f)
        if (viewport.width == 0 || viewport.height == 0) return copy(zoom = nextZoom)
        val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val limitX = max(0f, (bitmap.width * fit * nextZoom - viewport.width) / 2f) / viewport.width
        val limitY = max(0f, (bitmap.height * fit * nextZoom - viewport.height) / 2f) / viewport.height
        return ABPhotoTransform(nextZoom,
            (x + pan.x / viewport.width).coerceIn(-limitX, limitX),
            (y + pan.y / viewport.height).coerceIn(-limitY, limitY))
    }
}

private sealed interface ABPhotoState {
    data object Loading : ABPhotoState
    data class Ready(val bitmap: Bitmap) : ABPhotoState
    data object Failed : ABPhotoState
}

/** Only two explicitly compared photos are decoded with the existing 2048px bounded decoder. */
@Composable
private fun ABPhotoCanvas(
    item: MediaItem,
    slot: String,
    transform: ABPhotoTransform,
    onTransform: (ABPhotoTransform) -> Unit,
    gesturesEnabled: Boolean,
    modifier: Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var attempt by remember(item.uri) { mutableIntStateOf(0) }
    var state by remember(item.uri, item.fingerprint, item.volumeVersion) {
        mutableStateOf<ABPhotoState>(ABPhotoState.Loading)
    }
    LaunchedEffect(resolver, lifecycle, item.uri, item.fingerprint, item.volumeVersion, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state = ABPhotoState.Loading
            try {
                state = ABPhotoState.Ready(decodePreviewBitmap(resolver, Uri.parse(item.uri)))
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                state = ABPhotoState.Failed
                awaitCancellation()
            } catch (_: Exception) {
                state = ABPhotoState.Failed
                awaitCancellation()
            } finally {
                state = ABPhotoState.Loading
            }
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val currentTransform by rememberUpdatedState(transform)
    val sendTransform by rememberUpdatedState(onTransform)
    Box(
        modifier.clipToBounds().background(Panel).onSizeChanged { viewport = it }
            .pointerInput(item.uri, gesturesEnabled) {
                if (gesturesEnabled) detectTransformGestures { _, pan, zoom, _ ->
                    val bitmap = (state as? ABPhotoState.Ready)?.bitmap ?: return@detectTransformGestures
                    sendTransform(currentTransform.panBy(pan, viewport, bitmap, zoom))
                }
            }.semantics {
                contentDescription = "$slot 사진 미리보기"
                stateDescription = "${(transform.zoom * 100).roundToInt()}% 확대"
            }.testTag("AB-$slot-preview"),
        contentAlignment = Alignment.Center,
    ) {
        when (val display = state) {
            ABPhotoState.Loading ->
                CircularProgressIndicator(Modifier.semantics { contentDescription = "$slot 사진 로딩 중" })
            ABPhotoState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("사진을 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
                TextButton(onClick = { attempt++ }) { Text("$slot 다시 시도") }
            }
            is ABPhotoState.Ready -> {
                val bitmap = display.bitmap
                val fit = if (viewport.width > 0 && viewport.height > 0)
                    min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
                    else 0f
                val boundX = if (viewport.width > 0)
                    max(0f, (bitmap.width * fit * transform.zoom - viewport.width) / 2f) else 0f
                val boundY = if (viewport.height > 0)
                    max(0f, (bitmap.height * fit * transform.zoom - viewport.height) / 2f) else 0f
                Image(bitmap.asImageBitmap(), "$slot 사진: ${item.displayName}",
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.zoom
                        scaleY = transform.zoom
                        translationX = (transform.x * viewport.width).coerceIn(-boundX, boundX)
                        translationY = (transform.y * viewport.height).coerceIn(-boundY, boundY)
                    }, contentScale = ContentScale.Fit)
            }
        }
    }
}

/** Explicit candidate selection delegates to the existing screen's file-protection policy. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ABPhotoComparison(
    a: MediaItem,
    b: MediaItem,
    selectedUris: Set<String>,
    canSelect: (MediaItem) -> Boolean,
    onToggleCandidate: (MediaItem) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    allowEditing: Boolean = true,
    keeperUri: String? = null,
    onChooseKeeper: ((MediaItem) -> Unit)? = null,
    onProtect: ((MediaItem) -> Unit)? = null,
    canProtect: (MediaItem) -> Boolean = { true },
    statusLabel: ((MediaItem) -> String)? = null,
) {
    var mode by rememberSaveable { mutableStateOf("side") }
    var synchronized by rememberSaveable { mutableStateOf(true) }
    var activeSlot by rememberSaveable { mutableStateOf("A") }
    var divider by rememberSaveable { mutableFloatStateOf(.5f) }
    var shared by remember(a.uri, b.uri) { mutableStateOf(ABPhotoTransform()) }
    var first by remember(a.uri) { mutableStateOf(ABPhotoTransform()) }
    var second by remember(b.uri) { mutableStateOf(ABPhotoTransform()) }
    fun zoomFor(slot: String): ABPhotoTransform = if (synchronized) shared else if (slot == "A") first else second
    fun change(slot: String, next: ABPhotoTransform) {
        if (synchronized) shared = next
        else if (slot == "A") first = next else second = next
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val tablet = maxWidth >= 700.dp * fontScale
        val stackedInformation = maxWidth < 360.dp * fontScale
        val previewHeight = if (tablet) 340.dp else (maxWidth * .92f).coerceIn(240.dp, 340.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ABModeSelector(
                firstLabel = "나란히", secondLabel = "슬라이더",
                selectedSecond = mode == "slider", onSelectSecond = { mode = if (it) "slider" else "side" },
                firstDescription = "나란히 비교 모드", secondDescription = "슬라이더 비교 모드",
            )
            if (!synchronized) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A", "B").forEach { slot ->
                        ActionButton("$slot 개별 조절", { activeSlot = slot },
                            Modifier.semantics { contentDescription = "$slot 확대 대상" },
                            filled = activeSlot == slot)
                    }
                }
            }
            if (mode == "side") {
                Row(Modifier.fillMaxWidth().height(previewHeight)
                    .clip(RoundedCornerShape(GalliUiMetrics.cardCorner)).background(Line),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    listOf("A" to a, "B" to b).forEach { (slot, item) ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ABPhotoCanvas(item, slot, zoomFor(slot), { next ->
                                activeSlot = slot; change(slot, next)
                            }, true, Modifier.fillMaxSize())
                            ABPhotoBadge(slot, Modifier.align(if (slot == "A") Alignment.TopStart else Alignment.TopEnd).padding(10.dp))
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().height(previewHeight)
                    .clip(RoundedCornerShape(GalliUiMetrics.cardCorner))
                    .semantics {
                        contentDescription = "A/B 슬라이더 위치"
                        progressBarRangeInfo = ProgressBarRangeInfo(divider, 0f..1f)
                        setProgress { next ->
                            if (next.isFinite()) {
                                divider = next.coerceIn(0f, 1f)
                                true
                            } else false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false
                        else when (event.key) {
                            Key.DirectionLeft, Key.DirectionDown -> {
                                divider = (divider - .05f).coerceIn(0f, 1f); true
                            }
                            Key.DirectionRight, Key.DirectionUp -> {
                                divider = (divider + .05f).coerceIn(0f, 1f); true
                            }
                            Key.MoveHome -> { divider = 0f; true }
                            Key.MoveEnd -> { divider = 1f; true }
                            else -> false
                        }
                    }.focusable()
                    .pointerInput(a.uri, b.uri) {
                        detectHorizontalDragGestures { event, drag ->
                            if (size.width > 0) {
                                divider = (divider + drag / size.width).coerceIn(0f, 1f)
                                event.consume()
                            }
                        }
                    }.testTag("AB-slider-preview")) {
                    ABPhotoCanvas(a, "A", zoomFor("A"), { next -> change("A", next) },
                        false, Modifier.fillMaxSize())
                    ABPhotoCanvas(b, "B", zoomFor("B"), { next -> change("B", next) },
                        false, Modifier.fillMaxSize().drawWithContent {
                            clipRect(left = size.width * divider) { this@drawWithContent.drawContent() }
                        })
                    Box(Modifier.align(Alignment.CenterStart)
                        .offset(x = maxWidth * divider - 1.dp)
                        .width(2.dp).fillMaxHeight().background(AppSurface))
                    ABPhotoBadge("A", Modifier.align(Alignment.TopStart).padding(10.dp))
                    ABPhotoBadge("B", Modifier.align(Alignment.TopEnd).padding(10.dp))
                    Box(Modifier.align(Alignment.CenterStart)
                        .offset(x = (maxWidth * divider - 24.dp).coerceIn(0.dp, maxWidth - 48.dp))
                        .size(48.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp).background(AppSurface, CircleShape)
                            .border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                            Text("‹ ›", style = MaterialTheme.typography.labelLarge, color = Ink)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("끌어서 두 사진의 차이 확인", Modifier.weight(1f),
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("${(divider * 100).roundToInt()} : ${(100 - divider * 100).roundToInt()}",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.heightIn(min = GalliUiMetrics.touchTarget)
                        .clip(RoundedCornerShape(GalliUiMetrics.buttonCorner))
                        .toggleable(value = synchronized, role = Role.Switch, onValueChange = { enabled ->
                            if (enabled) shared = if (activeSlot == "A") first else second
                            else { first = shared; second = shared }
                            synchronized = enabled
                        }).semantics { contentDescription = "A/B 확대 동기화" },
                    shape = RoundedCornerShape(GalliUiMetrics.buttonCorner),
                    color = if (synchronized) SelectedContainer else AppSurface,
                    border = BorderStroke(1.dp, Line),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GalliIcon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                        Text(if (synchronized) "동기화 켜짐" else "동기화 꺼짐",
                            style = MaterialTheme.typography.labelLarge, color = Ink)
                    }
                }
                ActionButton("축소", { change(activeSlot, zoomFor(activeSlot).zoomBy(1f / 1.5f)) },
                    Modifier.semantics { contentDescription = "A/B 축소" }, icon = Icons.Outlined.ZoomOut)
                ActionButton("확대", { change(activeSlot, zoomFor(activeSlot).zoomBy(1.5f)) },
                    Modifier.semantics { contentDescription = "A/B 확대" }, icon = Icons.Outlined.ZoomIn)
                ActionButton("맞춤", { change(activeSlot, ABPhotoTransform()) },
                    Modifier.semantics { contentDescription = "A/B 화면 맞춤" }, icon = Icons.Outlined.CenterFocusStrong)
            }
            Text("${(zoomFor(activeSlot).zoom * 100).roundToInt()}% · ${if (synchronized) "양쪽 동기화" else "$activeSlot 개별 조절"}",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Column(Modifier.fillMaxWidth().galliCardSurface(AppSurface)) {
                Text("사진 정보 비교", Modifier.padding(12.dp),
                    style = MaterialTheme.typography.titleSmall, color = Ink)
                HorizontalDivider(color = Line)
                val information: @Composable (String, MediaItem, Modifier) -> Unit = { slot, item, cellModifier ->
                    Column(cellModifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$slot · ${item.displayName}", maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text("${item.width} × ${item.height} · ${bytesLabel(item.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text("${if (item.dateTakenMs > 0) "촬영일" else "저장일"} ${dateLabel(item.capturedAt)}",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                if (stackedInformation) {
                    information("A", a, Modifier.fillMaxWidth())
                    HorizontalDivider(color = Line)
                    information("B", b, Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        information("A", a, Modifier.weight(1f))
                        VerticalDivider(color = Line)
                        information("B", b, Modifier.weight(1f))
                    }
                }
            }
            val actions: @Composable (String, MediaItem, Modifier) -> Unit = { slot, item, cellModifier ->
                    Column(cellModifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        val selected = item.uri in selectedUris
                        Text(statusLabel?.invoke(item) ?: if (selected) "정리 후보 · 선택됨" else "남길 사진 · 유지",
                            style = MaterialTheme.typography.labelMedium, color = Ink)
                        ActionButton(
                            if (selected) "$slot 후보 해제·남기기" else "$slot 정리 후보 선택",
                            { onToggleCandidate(item) }, Modifier.fillMaxWidth(),
                            filled = !selected, enabled = allowEditing && (selected || canSelect(item)),
                        )
                        if (!selected && !canSelect(item)) {
                            Text("보존 규칙에 따라 정리 후보로 선택할 수 없습니다.",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (onChooseKeeper != null && keeperUri != null && item.uri != keeperUri) {
                            TextButton(onClick = { onChooseKeeper(item) }, enabled = allowEditing,
                                modifier = Modifier.fillMaxWidth()) { Text("$slot 남길 기준 파일로 지정") }
                        }
                        if (onProtect != null) {
                            TextButton(onClick = { onProtect(item) }, enabled = allowEditing && canProtect(item),
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (item.isProtected) "$slot 보호 해제" else "$slot 파일 보호")
                            }
                        }
                        TextButton(onClick = { onOpenMedia(item) }, modifier = Modifier.fillMaxWidth()) {
                            Text("$slot 원본 열기")
                        }
                    }
            }
            if (stackedInformation) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions("A", a, Modifier.fillMaxWidth())
                    HorizontalDivider(color = Line)
                    actions("B", b, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions("A", a, Modifier.weight(1f))
                    actions("B", b, Modifier.weight(1f))
                }
            }
            Text("사진을 보는 것만으로 정리 후보가 선택되지 않습니다. 실제 이동은 Android 확인창에서 승인합니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun ABPhotoBadge(slot: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = AppSurface, contentColor = Ink,
        shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Line)) {
        Text(slot, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ABModeSelector(
    firstLabel: String,
    secondLabel: String,
    selectedSecond: Boolean,
    onSelectSecond: (Boolean) -> Unit,
    firstDescription: String,
    secondDescription: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(GalliUiMetrics.buttonCorner)
    Row(modifier.fillMaxWidth().background(Panel, shape).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false, true).forEach { second ->
            val selected = selectedSecond == second
            Box(Modifier.weight(1f).heightIn(min = GalliUiMetrics.touchTarget)
                .clip(shape).background(if (selected) AppSurface else Color.Transparent)
                .border(1.dp, if (selected) Line else Color.Transparent, shape)
                .selectable(selected = selected, role = Role.Tab, onClick = { onSelectSecond(second) })
                .semantics { contentDescription = if (second) secondDescription else firstDescription }
                .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(if (second) secondLabel else firstLabel,
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                    color = if (selected) Ink else Muted)
            }
        }
    }
}

/** Keep the legacy transition action on the inactive tab so existing navigation remains accessible. */
@Composable
internal fun ABComparisonViewSelector(
    compareAB: Boolean,
    onSelect: (Boolean) -> Unit,
    transitionDescription: String,
    modifier: Modifier = Modifier,
) {
    ABModeSelector("한 장 보기", "A/B 비교", compareAB, onSelect,
        if (compareAB) transitionDescription else "한 장 보기 선택됨",
        if (compareAB) "A/B 비교 선택됨" else transitionDescription, modifier)
}
