package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
        val previewHeight = if (tablet) 340.dp else 270.dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { mode = "side" },
                    modifier = Modifier.semantics { contentDescription = "나란히 비교 모드" }) {
                    Text("나란히", color = if (mode == "side") Primary else Muted)
                }
                TextButton(onClick = { mode = "slider" },
                    modifier = Modifier.semantics { contentDescription = "슬라이더 비교 모드" }) {
                    Text("슬라이더", color = if (mode == "slider") Primary else Muted)
                }
                Spacer(Modifier.weight(1f))
                Text("동기화", style = MaterialTheme.typography.labelSmall, color = Muted)
                Switch(checked = synchronized, onCheckedChange = { enabled ->
                    if (enabled) shared = if (activeSlot == "A") first else second
                    else { first = shared; second = shared }
                    synchronized = enabled
                }, modifier = Modifier.semantics { contentDescription = "A/B 확대 동기화" })
            }
            if (!synchronized) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A", "B").forEach { slot ->
                        TextButton(
                            onClick = { activeSlot = slot },
                            modifier = Modifier.semantics { contentDescription = "$slot 확대 대상" },
                        ) {
                            Text("$slot 개별 조절", color = if (activeSlot == slot) Primary else Muted)
                        }
                    }
                }
            }
            if (mode == "side") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("A" to a, "B" to b).forEach { (slot, item) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$slot · ${item.displayName}", maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium)
                            ABPhotoCanvas(item, slot, zoomFor(slot), { next ->
                                activeSlot = slot; change(slot, next)
                            }, true, Modifier.fillMaxWidth().height(previewHeight))
                        }
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().height(previewHeight)
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
                        .width(2.dp).fillMaxHeight().background(Primary))
                    Text("A", Modifier.align(Alignment.TopStart).padding(8.dp),
                        color = Ink, style = MaterialTheme.typography.titleSmall)
                    Text("B", Modifier.align(Alignment.TopEnd).padding(8.dp),
                        color = Ink, style = MaterialTheme.typography.titleSmall)
                }
                Slider(value = divider, onValueChange = { divider = it },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "A/B 슬라이더 위치" })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconAction("A/B 축소", Icons.Outlined.ZoomOut) {
                    change(activeSlot, zoomFor(activeSlot).zoomBy(1f / 1.5f))
                }
                Text("${(zoomFor(activeSlot).zoom * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge)
                IconAction("A/B 확대", Icons.Outlined.ZoomIn) {
                    change(activeSlot, zoomFor(activeSlot).zoomBy(1.5f))
                }
                IconAction("A/B 화면 맞춤", Icons.Outlined.CenterFocusStrong) {
                    change(activeSlot, ABPhotoTransform())
                }
                Spacer(Modifier.weight(1f))
                Text(if (synchronized) "양쪽 동기화" else "$activeSlot 조절",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Text("비교 정보 · 미리보기는 긴 변 최대 2,048px",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("A" to a, "B" to b).forEach { (slot, item) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("$slot · ${item.displayName}", maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        Text("${item.width} × ${item.height}", style = MaterialTheme.typography.bodySmall)
                        Text(bytesLabel(item.sizeBytes), style = MaterialTheme.typography.bodySmall)
                        Text("${if (item.dateTakenMs > 0) "촬영일" else "저장일"} ${dateLabel(item.capturedAt)}",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        HorizontalDivider(color = Line)
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
            }
            Text("사진을 보는 것만으로 정리 후보가 선택되지 않습니다. 실제 이동은 Android 확인창에서 승인합니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
