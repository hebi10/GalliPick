package com.hebi.gallipick.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface as VideoOutputSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudOriginal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun SecretCloudVideoViewer(
    uid: String,
    original: CloudOriginal,
    backup: CloudBackup,
    dismiss: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var screen by remember { mutableStateOf<VideoOutputSurface?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var aspectRatio by remember { mutableStateOf<Float?>(null) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycle, screen, backup, uid, original.uploadId, failed) {
        val output = screen ?: return@LaunchedEffect
        if (failed) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var opened: MediaPlayer? = null
            try {
                val media = MediaPlayer()
                opened = media
                val error = CompletableDeferred<Unit>()
                media.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                media.setOnVideoSizeChangedListener { _, width, height ->
                    if (player === media && width > 0 && height > 0) aspectRatio = width.toFloat() / height
                }
                media.setOnPreparedListener {
                    if (player === media && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        ready = true
                    }
                }
                media.setOnCompletionListener {
                    if (player === media) playing = false
                }
                media.setOnErrorListener { _, _, _ ->
                    error.complete(Unit)
                    true
                }
                backup.openPrivateVideo(original, uid).use { descriptor ->
                    currentCoroutineContext().ensureActive()
                    media.setDataSource(descriptor.fileDescriptor)
                }
                media.setSurface(output)
                player = media
                media.prepareAsync()
                error.await()
                failed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                player = null
                ready = false
                playing = false
                opened?.release()
            }
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Surface(Modifier.fillMaxSize(), color = AppSurface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(original.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextAction("닫기", dismiss)
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    AndroidView(
                        modifier = aspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
                        factory = { context ->
                            SurfaceView(context).apply {
                                setSecure(true)
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        screen = holder.surface
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        screen = null
                                    }
                                })
                            }
                        },
                    )
                    if (failed) Text("동영상을 재생하지 못했습니다.", color = Color.White)
                    else if (!ready) CircularProgressIndicator()
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                    val media = player
                    if (ready && media != null) {
                        ActionButton(if (playing) "일시정지" else "재생", onClick = {
                            try {
                                if (playing) media.pause() else media.start()
                                playing = !playing
                            } catch (_: IllegalStateException) {
                                failed = true
                                ready = false
                                playing = false
                            }
                        })
                    }
                }
            }
        }
    }
}
