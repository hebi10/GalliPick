package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.cloudPhotoPosition
import com.hebi.gallipick.data.cloud.cloudPhotoSupportsThumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Volatile, per-account bitmap thumbnails; never persists cloud originals in MediaStore. */
internal object CloudPhotoThumbnailStore {
    private const val CACHE_BYTES = 12 * 1024 * 1024
    private val requests = Semaphore(1)
    private val images = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.allocationByteCount
    }
    private var ownerUid: String? = null
    private var session = 0L

    @Synchronized fun activate(uid: String?) {
        if (ownerUid != uid) {
            images.evictAll()
            ownerUid = uid
            session++
        }
    }

    @Synchronized fun peek(uid: String, original: CloudOriginal): Bitmap? =
        if (ownerUid == uid) images.get(original.uploadId) else null

    suspend fun load(uid: String, original: CloudOriginal, backup: CloudBackup): Bitmap? {
        if (!cloudPhotoSupportsThumbnail(original)) return null
        // Requests started before an account switch must not repopulate the cache after switching back.
        val requestSession = synchronized(this) { if (ownerUid == uid) session else null } ?: return null
        peek(uid, original)?.let { return it }
        return requests.withPermit {
            if (!isCurrent(uid, requestSession)) return@withPermit null
            peek(uid, original)?.let { return@withPermit it }
            val bitmap = backup.previewPhoto(original, uid, maxEdgePx = 384)
            synchronized(this) {
                if (ownerUid == uid && session == requestSession) {
                    images.put(original.uploadId, bitmap)
                    bitmap
                } else null
            }
        }
    }

    @Synchronized private fun isCurrent(uid: String, requestSession: Long): Boolean =
        ownerUid == uid && session == requestSession
}

@Composable
internal fun CloudPhotoThumbnail(
    original: CloudOriginal,
    uid: String?,
    backup: CloudBackup?,
    modifier: Modifier = Modifier,
    open: () -> Unit,
    autoLoad: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(uid, original.uploadId) {
        mutableStateOf(uid?.let { CloudPhotoThumbnailStore.peek(it, original) })
    }
    LaunchedEffect(uid, backup, original.uploadId, original.sizeBytes, autoLoad) {
        if (uid == null || backup == null || !autoLoad ||
            !cloudPhotoSupportsThumbnail(original)
        ) return@LaunchedEffect
        try {
            bitmap = CloudPhotoThumbnailStore.load(uid, original, backup)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            bitmap = null
        }
    }
    Box(modifier.background(Panel).plainClick(action = open)
        .semantics { contentDescription = "클라우드 사진: ${original.displayName}" }) {
        val loaded = bitmap
        if (loaded == null) {
            GalliIcon(Icons.Outlined.Image, null, Modifier.align(Alignment.Center).size(32.dp), tint = Muted)
        } else {
            Image(loaded.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

@Composable
internal fun CloudPhotoViewer(
    uid: String,
    original: CloudOriginal,
    photos: List<CloudOriginal>,
    backup: CloudBackup,
    choose: (CloudOriginal) -> Unit,
    dismiss: () -> Unit,
    offline: Boolean = false,
) {
    var retry by remember(uid, original.uploadId) { mutableIntStateOf(0) }
    var bitmap by remember(uid, original.uploadId) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(uid, original.uploadId) { mutableStateOf(true) }
    var error by remember(uid, original.uploadId) { mutableStateOf<String?>(null) }
    val position = cloudPhotoPosition(photos, original)

    LaunchedEffect(uid, original.uploadId, retry, offline) {
        if (offline) {
            bitmap = null
            loading = false
            error = "인터넷 연결이 필요합니다. 연결 후 다시 시도해 주세요."
            return@LaunchedEffect
        }
        if (!backup.canPreviewPhoto(original)) {
            error = "지원하지 않는 클라우드 사진입니다."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        bitmap = null
        try {
            bitmap = backup.previewPhoto(original, uid, maxEdgePx = 2048)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: CloudBackupException) {
            error = when (exception.result) {
                CloudBackupResult.ACCOUNT_CHANGED -> "계정이 변경되어 사진 열람을 중단했습니다."
                CloudBackupResult.NETWORK_ERROR -> "네트워크 연결을 확인해 주세요."
                CloudBackupResult.INTEGRITY_ERROR -> "검증된 원본과 파일 정보가 일치하지 않습니다."
                else -> "클라우드 사진을 열지 못했습니다."
            }
        } catch (_: Exception) {
            error = "클라우드 사진을 열지 못했습니다."
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = if (backup.isPrivateVault) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = AppSurface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(original.displayName, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("클라우드 원본 · ${bytesLabel(original.sizeBytes)} · 기기에 저장하지 않음",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    TextAction("닫기", dismiss)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val loaded = bitmap
                    when {
                        loaded != null -> ZoomablePhoto(loaded, original.displayName)
                        loading -> CircularProgressIndicator(
                            Modifier.semantics { contentDescription = "클라우드 사진을 불러오는 중" })
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(checkNotNull(error), color = Muted)
                            if (!offline) TextAction("다시 시도", { retry++ })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextAction("이전", { if (position > 0) choose(photos[position - 1]) })
                    Text("${(position + 1).coerceAtLeast(1)} / ${photos.size}",
                        style = MaterialTheme.typography.labelMedium, color = Muted)
                    TextAction("다음", { if (position in 0 until photos.lastIndex) choose(photos[position + 1]) })
                }
            }
        }
    }
}
