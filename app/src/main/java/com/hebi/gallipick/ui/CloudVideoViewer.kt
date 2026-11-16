package com.hebi.gallipick.ui

import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.CloudVideoStreamCapability
import com.hebi.gallipick.data.cloud.isCloudVideoStreamable
import com.hebi.gallipick.data.cloud.safeCloudVideoPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plays a generation-pinned, short-lived signed Google Cloud Storage URL.
 * Android VideoView handles HTTP byte ranges. No VideoView URI is saved to
 * MediaStore, the app database, logs, or external intents.
 */
@Composable
internal fun CloudVideoViewer(
    uid: String,
    original: CloudOriginal,
    backup: CloudBackup,
    dismiss: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember(uid, original.uploadId) { mutableIntStateOf(0) }
    var capability by remember(uid, original.uploadId) { mutableStateOf<CloudVideoStreamCapability?>(null) }
    var loading by remember(uid, original.uploadId) { mutableStateOf(true) }
    var message by remember(uid, original.uploadId) { mutableStateOf<String?>(null) }
    var player by remember(uid, original.uploadId) { mutableStateOf<VideoView?>(null) }
    var ready by remember(uid, original.uploadId) { mutableStateOf(false) }
    var playing by remember(uid, original.uploadId) { mutableStateOf(false) }
    var buffering by remember(uid, original.uploadId) { mutableStateOf(false) }
    var durationMs by remember(uid, original.uploadId) { mutableIntStateOf(0) }
    var positionMs by remember(uid, original.uploadId) { mutableIntStateOf(0) }
    var scrubbing by remember(uid, original.uploadId) { mutableStateOf(false) }
    var scrubPosition by remember(uid, original.uploadId) { mutableFloatStateOf(0f) }
    var pausedByLifecycle by remember(uid, original.uploadId) { mutableStateOf(false) }

    fun disconnect() {
        player?.let { active ->
            if (active.isPlaying) positionMs = active.currentPosition.coerceAtLeast(0)
            active.pause()
        }
        ready = false
        playing = false
        buffering = false
        capability = null
        player = null
    }

    DisposableEffect(lifecycle, uid, original.uploadId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    player?.pause()
                    playing = false
                }
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    disconnect()
                    pausedByLifecycle = true
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player?.stopPlayback()
            disconnect()
        }
    }

    LaunchedEffect(uid, original.uploadId, refresh, pausedByLifecycle) {
        if (pausedByLifecycle) return@LaunchedEffect
        if (!isCloudVideoStreamable(original)) {
            loading = false
            message = "지원하지 않는 동영상 원본입니다."
            return@LaunchedEffect
        }
        loading = true
        message = null
        ready = false
        buffering = false
        capability = null
        try {
            val next = backup.openVideoStream(original, uid)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                capability = next
            } else {
                pausedByLifecycle = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CloudBackupException) {
            message = when (error.result) {
                CloudBackupResult.ACCOUNT_CHANGED -> "계정 변경으로 영상 재생이 중단됐습니다."
                CloudBackupResult.NETWORK_ERROR -> "인터넷 연결을 확인한 뒤 다시 시도해 주세요."
                CloudBackupResult.INTEGRITY_ERROR -> "클라우드 원본 정보가 일치하지 않습니다."
                CloudBackupResult.TEST_ACCESS_REQUIRED -> "해당 클라우드 계정의 이용 권한을 확인해 주세요."
                else -> "클라우드 영상을 불러오지 못했습니다."
            }
        } catch (_: Exception) {
            message = "클라우드 영상을 불러오지 못했습니다."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(player, ready, playing) {
        while (ready) {
            val active = player ?: break
            positionMs = runCatching { active.currentPosition }.getOrDefault(positionMs)
                .coerceAtLeast(0)
            playing = runCatching { active.isPlaying }.getOrDefault(false)
            delay(300)
        }
    }

    fun reconnect() {
        disconnect()
        pausedByLifecycle = false
        refresh++
    }

    fun updatePosition(next: Int) {
        positionMs = safeCloudVideoPosition(next, durationMs)
        val active = player ?: return
        val stream = capability ?: return
        active.pause()
        playing = false
        if (stream.shouldRenew(SystemClock.elapsedRealtime())) reconnect()
        else active.seekTo(positionMs)
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AppSurface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(original.displayName, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("클라우드 스트리밍 · ${bytesLabel(original.sizeBytes)} · 기기에 저장하지 않음",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    TextAction("닫기", dismiss)
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                    contentAlignment = Alignment.Center) {
                    val activeCapability = capability
                    if (activeCapability != null && !pausedByLifecycle) {
                        key(uid, original.uploadId, refresh) {
                            CloudVideoSurface(
                                signedUrl = activeCapability.signedUrl.toString(),
                                initialPositionMs = positionMs,
                                onView = { view -> player = view },
                                onReady = { length ->
                                    durationMs = length
                                    ready = true
                                    buffering = false
                                },
                                onBuffering = { buffering = it },
                                onCompleted = {
                                    playing = false
                                    buffering = false
                                    positionMs = durationMs
                                },
                                onFailed = {
                                    disconnect()
                                    message = "재생 연결이 중단됐습니다. 다시 시도하면 새 인증으로 연결합니다."
                                },
                            )
                        }
                    }
                    if (!ready || buffering || loading) {
                        CircularProgressIndicator(
                            Modifier.semantics { contentDescription = "클라우드 동영상 버퍼링 또는 연결 중" })
                    }
                    if (capability == null || pausedByLifecycle) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!loading) {
                                Text(if (pausedByLifecycle) "앱 복귀 후 다시 연결해 주세요."
                                    else message ?: "스트리밍 연결을 확인 중입니다.",
                                    color = Color.White)
                                TextButton(onClick = ::reconnect) {
                                    Text("다시 연결", color = Color.White)
                                }
                            }
                        }
                    }
                }
                if (ready) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextAction(if (playing) "일시정지" else "재생", {
                            val active = player
                            val stream = capability
                            if (active != null && stream != null) {
                                if (active.isPlaying) {
                                    active.pause()
                                    playing = false
                                } else if (stream.shouldRenew(SystemClock.elapsedRealtime())) {
                                    reconnect()
                                } else {
                                    if (positionMs >= durationMs && durationMs > 0) {
                                        active.seekTo(0)
                                        positionMs = 0
                                    }
                                    active.start()
                                    playing = true
                                }
                            }
                        })
                        Slider(
                            value = if (scrubbing) scrubPosition else positionMs.toFloat()
                                .coerceIn(0f, max(1, durationMs).toFloat()),
                            onValueChange = {
                                if (!scrubbing) {
                                    player?.pause()
                                    playing = false
                                }
                                scrubbing = true
                                scrubPosition = it
                            },
                            onValueChangeFinished = {
                                updatePosition(scrubPosition.roundToInt())
                                scrubbing = false
                            },
                            valueRange = 0f..max(1, durationMs).toFloat(),
                            enabled = durationMs > 0,
                            modifier = Modifier.weight(1f)
                                .semantics { contentDescription = "클라우드 동영상 재생 위치" },
                        )
                    }
                    Text("${durationLabel(positionMs.toLong())} / ${durationLabel(durationMs.toLong())}",
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                } else {
                    Text("영상 재생은 사용자가 재생 버튼을 누르면 시작됩니다.",
                        Modifier.align(Alignment.CenterHorizontally).padding(10.dp),
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

/** The streaming view owns exactly one private playback session. */
@Composable
private fun CloudVideoSurface(
    signedUrl: String,
    initialPositionMs: Int,
    onView: (VideoView?) -> Unit,
    onReady: (Int) -> Unit,
    onBuffering: (Boolean) -> Unit,
    onCompleted: () -> Unit,
    onFailed: () -> Unit,
) {
    var owned by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            owned?.let { view ->
                view.setOnPreparedListener(null)
                view.setOnInfoListener(null)
                view.setOnCompletionListener(null)
                view.setOnErrorListener(null)
                runCatching { view.stopPlayback() }
                onView(null)
            }
            owned = null
        }
    }
    AndroidView(factory = { context ->
        VideoView(context).also { view ->
            owned = view
            onView(view)
            view.setMediaController(null)
            view.setOnPreparedListener { player ->
                val length = player.duration.coerceAtLeast(0)
                if (initialPositionMs > 0 && length > 0) {
                    view.seekTo(safeCloudVideoPosition(initialPositionMs, length))
                }
                onReady(length)
            }
            view.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> onBuffering(true)
                    MediaPlayer.MEDIA_INFO_BUFFERING_END,
                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> onBuffering(false)
                }
                true
            }
            view.setOnCompletionListener { onCompleted() }
            view.setOnErrorListener { _, _, _ ->
                onFailed()
                true
            }
            // Deny cross-origin redirects of a time-limited signed Storage URL.
            view.setVideoURI(Uri.parse(signedUrl), mapOf("android-allow-cross-domain-redirect" to "0"))
        }
    }, modifier = Modifier.fillMaxSize()
        .semantics { contentDescription = "클라우드 동영상 재생 화면" })
}
