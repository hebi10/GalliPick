package com.hebi.gallipick.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val PREVIEW_MAX_EDGE = 2048
private val photoDecodePermit = Semaphore(1)

/** ImageDecoder applies encoded orientation; only a bounded, software sRGB bitmap is retained. */
internal suspend fun decodePreviewBitmap(resolver: ContentResolver, uri: Uri): Bitmap {
    var ownedBitmap: Bitmap? = null
    try {
        val decoded = withContext(Dispatchers.IO) {
            photoDecodePermit.withPermit {
                val decodingContext = currentCoroutineContext()
                decodingContext.ensureActive()
                // A URI source lets the decoder read the file without copying encoded bytes into RAM.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decodingContext.ensureActive()
                    val ratio = min(1.0, PREVIEW_MAX_EDGE.toDouble() / max(info.size.width, info.size.height))
                    decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.setOnPartialImageListener { false }
                }.also {
                    ownedBitmap = it
                    decodingContext.ensureActive()
                }
            }
        }
        ownedBitmap = null
        return decoded
    } finally {
        // A synchronous native decode can finish after cancellation. Never publish that result.
        ownedBitmap?.recycle()
    }
}

@Composable
internal fun MediaPreviewDialog(item: MediaItem, onDismiss: () -> Unit, onOpen: (MediaItem) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.displayName, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                    IconAction("미리보기 닫기", Icons.Rounded.Close, onClick = onDismiss)
                }
                key(item.uri, item.fingerprint, item.volumeVersion) {
                    if (item.kind == MediaKind.PHOTO) PhotoPreview(item, Modifier.weight(1f))
                    else VideoPreview(item, Modifier.weight(1f))
                }
                TextButton(onClick = { onDismiss(); onOpen(item) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    GalliIcon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("외부 앱에서 원본 열기")
                }
            }
        }
    }
}

@Composable
private fun rememberPreviewStarted(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return started
}

private sealed interface PhotoState {
    data object Loading : PhotoState
    data class Ready(val bitmap: Bitmap) : PhotoState
    data class Failed(val message: String) : PhotoState
}

@Composable
private fun PhotoPreview(item: MediaItem, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val started = rememberPreviewStarted()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(item.uri, item.fingerprint, item.volumeVersion) { mutableStateOf<PhotoState>(PhotoState.Loading) }
    LaunchedEffect(resolver, lifecycle, item.uri, item.fingerprint, item.volumeVersion, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state = PhotoState.Loading
            try {
                state = PhotoState.Ready(decodePreviewBitmap(resolver, Uri.parse(item.uri)))
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                state = PhotoState.Failed("미리보기 메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요.")
                awaitCancellation()
            } catch (_: Exception) {
                state = PhotoState.Failed("사진을 불러올 수 없습니다. 파일과 접근 권한을 확인해 주세요.")
                awaitCancellation()
            } finally {
                // Drop displayed ownership. Recycling a bitmap still referenced by a render frame is unsafe.
                state = PhotoState.Loading
            }
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (!started) Text("앱으로 돌아오면 미리보기를 다시 불러옵니다.", Modifier.padding(24.dp), color = Muted)
        else when (val current = state) {
            PhotoState.Loading -> CircularProgressIndicator(Modifier.semantics { contentDescription = "사진을 불러오는 중" })
            is PhotoState.Failed -> PreviewError(current.message) { attempt++ }
            is PhotoState.Ready -> ZoomablePhoto(current.bitmap, item.displayName)
        }
    }
}

@Composable
private fun ZoomablePhoto(bitmap: Bitmap, name: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun boundedOffset(candidate: Offset, zoom: Float): Offset {
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = min(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val limitX = max(0f, (bitmap.width * fit * zoom - viewport.width) / 2f)
        val limitY = max(0f, (bitmap.height * fit * zoom - viewport.height) / 2f)
        return Offset(candidate.x.coerceIn(-limitX, limitX), candidate.y.coerceIn(-limitY, limitY))
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black)
            .onSizeChanged { viewport = it; offset = boundedOffset(offset, scale) }
            .pointerInput(bitmap, viewport) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 6f)
                    val focalPoint = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
                    offset = boundedOffset((offset - focalPoint) * (nextScale / scale) + focalPoint + pan, nextScale)
                    scale = nextScale
                }
            }, contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), "사진 미리보기: $name", Modifier.fillMaxSize()
                .semantics { stateDescription = "${(scale * 100).roundToInt()}%" }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconAction("사진 축소", Icons.Rounded.ZoomOut) { scale = max(1f, scale / 1.5f); offset = boundedOffset(offset, scale) }
            Text("${(scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            IconAction("사진 확대", Icons.Rounded.ZoomIn) { scale = min(6f, scale * 1.5f); offset = boundedOffset(offset, scale) }
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("맞춤") }
        }
        Text("두 손가락으로 확대하고 끌어서 이동", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("미리보기 ${bitmap.width} × ${bitmap.height} · 긴 변 최대 2,048px", Modifier.padding(12.dp).align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/** A session is never resumed automatically after the app leaves the foreground. */
private class VideoSession(val view: VideoView) {
    var controller: MediaController? = null
    var released = false
        private set

    fun release(): Int {
        if (released) return 0
        val position = runCatching { view.currentPosition }.getOrDefault(0)
        released = true
        controller?.hide()
        controller = null
        view.setOnPreparedListener(null)
        view.setOnCompletionListener(null)
        view.setOnErrorListener(null)
        view.setMediaController(null)
        // Some platform players reject stop while still preparing; suspend releases that state.
        runCatching { view.stopPlayback() }.onFailure { runCatching { view.suspend() } }
        return position.coerceAtLeast(0)
    }
}

@Composable
internal fun VideoPreview(item: MediaItem, modifier: Modifier = Modifier, createView: (Context) -> VideoView = { VideoView(it) }) {
    key(item.uri, item.fingerprint, item.volumeVersion) { VideoPreviewContent(item, modifier, createView) }
}

@Composable
private fun VideoPreviewContent(item: MediaItem, modifier: Modifier, createView: (Context) -> VideoView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val started = rememberPreviewStarted()
    var attempt by remember { mutableIntStateOf(0) }
    var playbackEpoch by remember { mutableIntStateOf(0) }
    var position by remember(item.uri, item.fingerprint, item.volumeVersion) { mutableIntStateOf(0) }
    var duration by remember(item.uri, item.fingerprint, item.volumeVersion) { mutableIntStateOf(0) }
    var failed by remember(item.uri, item.fingerprint, item.volumeVersion, attempt) { mutableStateOf(false) }
    var prepared by remember(item.uri, item.fingerprint, item.volumeVersion, attempt) { mutableStateOf(false) }
    var playing by remember(item.uri, item.fingerprint, item.volumeVersion, attempt) { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    var session by remember { mutableStateOf<VideoSession?>(null) }

    fun stopSession() {
        session?.takeUnless { it.released }?.let { position = it.release() }
        playing = false
        prepared = false
        scrubbing = false
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // Stop immediately on ON_STOP, including before a deferred recomposition can run.
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                stopSession()
                // A stop/start can happen before Compose draws the stopped state. Recreate anyway.
                playbackEpoch++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopSession() }
    }
    LaunchedEffect(session, prepared, started) {
        while (started && prepared) {
            val current = session?.takeUnless { it.released } ?: break
            position = current.view.currentPosition.coerceAtLeast(0)
            playing = current.view.isPlaying
            delay(250)
        }
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (!started) Text("앱으로 돌아온 뒤 재생 버튼을 눌러 주세요.", Modifier.padding(24.dp), color = Color.White)
            else if (failed) PreviewError("영상을 재생할 수 없습니다. 파일과 접근 권한을 확인해 주세요.") { attempt++ }
            else key(item.uri, item.fingerprint, item.volumeVersion, attempt, playbackEpoch) {
                DisposableEffect(Unit) {
                    onDispose { stopSession(); session = null }
                }
                AndroidView(
                    factory = { context ->
                        createView(context).also { view ->
                            val current = VideoSession(view)
                            session = current
                            val controller = MediaController(context).apply { setAnchorView(view) }
                            current.controller = controller
                            view.setMediaController(controller)
                            view.setOnPreparedListener { player ->
                                if (!current.released && started) {
                                    duration = player.duration.coerceAtLeast(0)
                                    prepared = true
                                    // Explicit user action starts playback, also after returning from background.
                                    view.seekTo(position.coerceIn(0, max(0, duration - 1)))
                                    playing = false
                                }
                            }
                            view.setOnCompletionListener { if (!current.released) { playing = false; position = duration } }
                            view.setOnErrorListener { _, _, _ ->
                                if (!current.released) { failed = true; stopSession() }
                                true // Handle errors here instead of opening the platform's modal error dialog.
                            }
                            try { view.setVideoURI(Uri.parse(item.uri)) }
                            catch (_: Exception) { failed = true; stopSession() }
                        }
                    },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "영상 미리보기: ${item.displayName}" },
                )
                if (!prepared) CircularProgressIndicator(Modifier.semantics { contentDescription = "영상을 여는 중" })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = started && prepared && !failed, onClick = {
                session?.takeUnless { it.released }?.let { current ->
                    if (current.view.isPlaying) current.view.pause()
                    else { if (position >= duration) { current.view.seekTo(0); position = 0 }; current.view.start() }
                    playing = current.view.isPlaying
                }
            }) {
                GalliIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Text(if (playing) "일시정지" else "재생")
            }
            Slider(value = (if (scrubbing) scrubPosition else position.toFloat()).coerceIn(0f, max(1, duration).toFloat()),
                onValueChange = { scrubbing = true; scrubPosition = it },
                onValueChangeFinished = {
                    session?.takeUnless { it.released }?.view?.seekTo(scrubPosition.roundToInt())
                    position = scrubPosition.roundToInt(); scrubbing = false
                }, valueRange = 0f..max(1, duration).toFloat(), enabled = started && prepared && duration > 0 && !failed,
                modifier = Modifier.weight(1f).semantics { contentDescription = "영상 재생 위치" })
        }
        Text("${durationLabel(position.toLong())} / ${durationLabel(duration.toLong())}", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun PreviewError(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(24.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("다시 시도") }
        }
    }
}
