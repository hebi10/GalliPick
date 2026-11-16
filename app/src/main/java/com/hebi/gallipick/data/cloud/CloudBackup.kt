package com.hebi.gallipick.data.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.hebi.gallipick.data.MediaStoreReader
import com.hebi.gallipick.domain.MediaHasher
import com.hebi.gallipick.domain.MediaItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal const val CLOUD_PREVIEW_MAX_BYTES = 8L * 1024L * 1024L

internal enum class CloudBackupPhase { READING_ORIGINAL, UPLOADING, VERIFYING }

internal enum class CloudBackupResult {
    VERIFIED, ORIGINAL_UNAVAILABLE, UNSUPPORTED_MEDIA, ACCOUNT_CHANGED,
    QUOTA_REACHED, TEST_ACCESS_REQUIRED, NETWORK_ERROR, INTEGRITY_ERROR, DEVICE_STORAGE_ERROR, FAILED,
}

internal class CloudBackupException(val result: CloudBackupResult) : Exception()

internal data class CloudAutoBackupTransfer(val folder: CloudAutoBackupFolder, val wifiNetwork: Network)

internal fun failBackup(result: CloudBackupResult): Nothing = throw CloudBackupException(result)

internal class CloudBackup(
    context: Context,
    private val account: StateFlow<CloudAccountIdentity?>,
    private val currentUid: () -> String?,
    private val tokenFor: suspend (String) -> String,
    private val transport: CloudBackupTransport,
) {
    private val appContext = context.applicationContext
    private val reader = MediaStoreReader(appContext)
    private val mutex = Mutex()
    private val restoreStore = CloudRestoreStore(appContext)
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()

    suspend fun upload(
        item: MediaItem,
        expectedUid: String,
        automatic: CloudAutoBackupTransfer? = null,
        onPhase: (CloudBackupPhase) -> Unit = {},
    ): CloudBackupResult {
        val mimeType = item.mimeType.lowercase(Locale.ROOT)
        if (item.sizeBytes !in 1..CLOUD_BACKUP_MAX_BYTES || !CLOUD_MEDIA_MIME_TYPE.matches(mimeType)) {
            return CloudBackupResult.UNSUPPORTED_MEDIA
        }
        if (!mutex.tryLock()) return CloudBackupResult.FAILED
        return try {
            forAccount(expectedUid, 180_000L) { check ->
                transfer(item, mimeType, expectedUid, automatic, onPhase, check)
            }.also { if (it == CloudBackupResult.VERIFIED) _catalogRevision.update { current -> current + 1 } }
        } catch (error: CloudBackupException) {
            error.result
        } finally {
            mutex.unlock()
        }
    }

    suspend fun listOriginals(expectedUid: String): List<CloudOriginal> = forAccount(expectedUid, 40_000L) { check ->
        val token = tokenFor(expectedUid)
        check()
        transport.listOriginals(token)
    }

    suspend fun restore(original: CloudOriginal, expectedUid: String): Uri {
        if (!mutex.tryLock()) failBackup(CloudBackupResult.FAILED)
        return try {
            forAccount(expectedUid, 180_000L) { check ->
                val token = tokenFor(expectedUid)
                check()
                val target = transport.getDownload(expectedUid, token, original)
                check()
                restoreStore.save(original, check) { output -> transport.download(target, original, output, check) }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Decodes a verified cloud photo to a bounded bitmap without publishing an
     * image to MediaStore. The verified original is staged in private cache only
     * while decoding; this supports up to the existing 64 MiB backup limit.
     */
    suspend fun previewPhoto(
        original: CloudOriginal,
        expectedUid: String,
        maxEdgePx: Int = 1024,
    ): Bitmap {
        if (!original.mimeType.startsWith("image/") ||
            original.sizeBytes !in 1..CLOUD_BACKUP_MAX_BYTES ||
            maxEdgePx !in 128..2048
        ) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        mutex.lock()
        try {
            return forAccount(expectedUid, 180_000L) { check ->
                val token = tokenFor(expectedUid)
                check()
                val target = transport.getDownload(expectedUid, token, original)
                withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("gallipick-preview-", ".tmp", appContext.cacheDir)
                    try {
                        FileOutputStream(temporary).use { output ->
                            transport.download(target, original, output, check)
                        }
                        check()
                        val source = ImageDecoder.createSource(temporary)
                        val result = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            val scale = maxEdgePx.toDouble() / maxOf(info.size.width, info.size.height)
                            if (scale < 1) decoder.setTargetSize(
                                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                                (info.size.height * scale).roundToInt().coerceAtLeast(1),
                            )
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        check()
                        result
                    } finally {
                        temporary.delete()
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T : Any> forAccount(uid: String, timeoutMillis: Long, block: suspend (() -> Unit) -> T): T {
        if (!ownsAccount(uid)) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
        return withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val operation = async {
                    val operationContext = currentCoroutineContext()
                    val check = {
                        operationContext.ensureActive()
                        if (!ownsAccount(uid)) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
                    }
                    check()
                    block(check).also { check() }
                }
                val watchAccount = launch(start = CoroutineStart.UNDISPATCHED) {
                    account.first { it?.uid != uid }
                    operation.cancel()
                }
                try {
                    operation.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (ownsAccount(uid)) throw error
                    failBackup(CloudBackupResult.ACCOUNT_CHANGED)
                } finally {
                    watchAccount.cancel()
                }
            }
        } ?: failBackup(CloudBackupResult.NETWORK_ERROR)
    }

    private suspend fun transfer(
        item: MediaItem,
        mimeType: String,
        uid: String,
        automatic: CloudAutoBackupTransfer?,
        onPhase: (CloudBackupPhase) -> Unit,
        checkAccount: () -> Unit,
    ): CloudBackupResult {
        val requiredFolder = automatic?.folder
        var original: FileInputStream? = null
        return try {
            checkAccount()
            onPhase(CloudBackupPhase.READING_ORIGINAL)
            val hash = withContext(Dispatchers.IO) {
                assertOriginal(item, requiredFolder)
                try {
                    val temporary = File.createTempFile("original-", ".tmp", appContext.cacheDir)
                    try {
                        original = FileInputStream(temporary)
                        FileOutputStream(temporary).use { output ->
                            // Keep bytes only in open descriptors, even if the process stops during upload.
                            if (!temporary.delete()) failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
                            readOriginal(item) { input ->
                                MediaHasher.sha256(BackupStagingInput(input, output, item.sizeBytes), checkAccount)
                            }
                        }
                    } finally {
                        temporary.delete()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CloudBackupException) {
                    throw error
                } catch (_: Exception) {
                    failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
                }
            }
            assertOriginal(item, requiredFolder)
            checkAccount()
            val beginToken = tokenFor(uid)
            checkAccount()
            val target = transport.begin(uid, beginToken, hash, item.sizeBytes, mimeType, item.displayName,
                CloudUploadMetadata.from(item))
            checkAccount()
            assertOriginal(item, requiredFolder)
            checkAccount()
            if (target != null) {
                onPhase(CloudBackupPhase.UPLOADING)
                transport.put(target, checkNotNull(original), item.sizeBytes, automatic?.wifiNetwork, checkAccount)
                checkAccount()
                onPhase(CloudBackupPhase.VERIFYING)
                val finishToken = tokenFor(uid)
                checkAccount()
                transport.finish(target.id, finishToken)
            }
            checkAccount()
            CloudBackupResult.VERIFIED
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudBackupException) {
            error.result
        } catch (_: Exception) {
            CloudBackupResult.FAILED
        } finally {
            original?.close()
        }
    }

    private fun ownsAccount(uid: String): Boolean = account.value?.uid == uid && currentUid() == uid

    private suspend fun assertOriginal(expected: MediaItem, requiredFolder: CloudAutoBackupFolder?) {
        if (!matchesCloudBackupOriginal(expected, reader.current(expected.uri), requiredFolder)) {
            failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
        }
    }

    private fun <T> readOriginal(item: MediaItem, read: (InputStream) -> T): T {
        val uri = MediaStore.setRequireOriginal(Uri.parse(item.uri))
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) }
            val descriptor = resolver.openTypedAssetFileDescriptor(uri, "*/*", options)
                ?: failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            return descriptor.use { it.createInputStream().use(read) }
        }
        val stream = resolver.openInputStream(uri) ?: failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
        return stream.use(read)
    }

}

internal class BackupStagingInput(
    private val original: InputStream,
    private val output: OutputStream,
    private val size: Long,
    private val mismatch: CloudBackupResult = CloudBackupResult.ORIGINAL_UNAVAILABLE,
) : InputStream() {
    private var copied = 0L

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) < 0) -1 else buffer[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = original.read(buffer, offset, minOf(length.toLong(), size - copied + 1).toInt())
        if (count == -1) {
            if (copied != size) failBackup(mismatch)
        } else {
            copied += count
            if (copied > size) failBackup(mismatch)
            output.write(buffer, offset, count)
        }
        return count
    }
}
