package com.hebi.gallipick.data.cloud

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Network
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.os.SystemClock
import com.hebi.gallipick.data.MediaStoreReader
import com.hebi.gallipick.domain.MediaHasher
import com.hebi.gallipick.domain.MediaItem
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
private const val RESUMABLE_PART_ATTEMPTS = 3
private const val RESUMABLE_VERIFICATION_POLLS = 80
private const val RESUMABLE_VERIFICATION_INTERVAL_MS = 15_000L

internal enum class CloudBackupPhase { READING_ORIGINAL, UPLOADING, VERIFYING }

internal enum class CloudBackupResult {
    VERIFIED, ORIGINAL_UNAVAILABLE, UNSUPPORTED_MEDIA, ACCOUNT_CHANGED,
    QUOTA_REACHED, TEST_ACCESS_REQUIRED, NETWORK_ERROR, INTEGRITY_ERROR, DEVICE_STORAGE_ERROR, FAILED,
    VERIFICATION_PENDING,
}

internal enum class CloudBackupNetworkFailure { CONNECTION, TIMEOUT, SERVICE }

internal class CloudBackupException(
    val result: CloudBackupResult,
    val permissionDenied: Boolean = false,
    val networkFailure: CloudBackupNetworkFailure? = null,
) : Exception()

internal data class CloudAutoBackupTransfer(val folder: CloudAutoBackupFolder, val wifiNetwork: Network)

internal data class RecentVerifiedCloudUpload(
    val accountUid: String,
    val uri: String,
    val fingerprint: String,
    val volumeVersion: String,
)

internal fun failBackup(result: CloudBackupResult): Nothing = throw CloudBackupException(result)

internal fun cloudBackupNetworkError(reason: CloudBackupNetworkFailure) =
    CloudBackupException(CloudBackupResult.NETWORK_ERROR, networkFailure = reason)

internal class CloudBackup(
    context: Context,
    private val account: StateFlow<CloudAccountIdentity?>,
    private val currentUid: () -> String?,
    private val tokenFor: suspend (String) -> String,
    private val transport: CloudBackupTransport,
    private val productionAccess: CloudBackupAccessState? = null,
) {
    private val appContext = context.applicationContext
    private val reader = MediaStoreReader(appContext)
    private val mutex = Mutex()
    private val restoreStore = CloudRestoreStore(appContext)
    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision = _catalogRevision.asStateFlow()
    private val _recentVerifiedUpload = MutableStateFlow<RecentVerifiedCloudUpload?>(null)
    val recentVerifiedUpload = _recentVerifiedUpload.asStateFlow()
    val isPrivateVault: Boolean get() = transport.isPrivateVault

    fun canPreviewPhoto(original: CloudOriginal): Boolean =
        cloudPhotoSupportsViewer(original, if (isPrivateVault) CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES else CLOUD_BACKUP_MAX_BYTES)

    fun canPlayPrivateVideo(original: CloudOriginal): Boolean =
        isPrivateVault && isCloudVideoStreamable(original, CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES)

    fun accessFor(uid: String): CloudBackupAccess? {
        if (!ownsAccount(uid)) return null
        return if (productionAccess == null) CloudBackupAccess.development else productionAccess.forAccount(uid)
    }

    suspend fun upload(
        item: MediaItem,
        expectedUid: String,
        automatic: CloudAutoBackupTransfer? = null,
        onProgress: (CloudBackupProgress) -> Unit = {},
        onPhase: (CloudBackupPhase) -> Unit = {},
    ): CloudBackupResult {
        if (!ownsAccount(expectedUid)) return CloudBackupResult.ACCOUNT_CHANGED
        val access = accessFor(expectedUid) ?: return CloudBackupResult.TEST_ACCESS_REQUIRED
        val resumable = automatic == null && access.supportsResumableTransfers && item.sizeBytes > access.maximumOriginalBytes
        val now = System.currentTimeMillis()
        val allowed = if (resumable) access.allowsResumableUpload(now) else access.allowsUpload(now)
        if (!allowed) return CloudBackupResult.TEST_ACCESS_REQUIRED
        val sizeLimit = when {
            resumable -> access.maximumResumableOriginalBytes
            automatic != null -> minOf(access.maximumOriginalBytes, CLOUD_BACKUP_MAX_BYTES)
            else -> access.maximumOriginalBytes
        }
        val mimeType = item.mimeType.lowercase(Locale.ROOT)
        if (item.sizeBytes !in 1..sizeLimit || !CLOUD_MEDIA_MIME_TYPE.matches(mimeType)) {
            return CloudBackupResult.UNSUPPORTED_MEDIA
        }
        if (!mutex.tryLock()) return CloudBackupResult.FAILED
        return try {
            val timeout = when {
                resumable -> null
                access.namespace == CloudBackupNamespace.DEVELOPMENT || automatic != null -> 180_000L
                else -> 1_200_000L
            }
            forAccount(expectedUid, timeout, access.namespace) { check ->
                if (resumable) transferResumable(item, mimeType, expectedUid, access.namespace, onProgress, onPhase, check)
                else transfer(item, mimeType, expectedUid, access.namespace, automatic, onPhase, check)
            }.also {
                if (it == CloudBackupResult.VERIFIED) {
                    _recentVerifiedUpload.value = RecentVerifiedCloudUpload(
                        expectedUid, item.uri, item.fingerprint, item.volumeVersion,
                    )
                    _catalogRevision.update { current -> current + 1 }
                }
            }
        } catch (error: CloudBackupException) {
            error.result
        } finally {
            mutex.unlock()
        }
    }

    suspend fun listOriginals(expectedUid: String): List<CloudOriginal> {
        val access = requireAccess(expectedUid)
        val namespace = access.namespace
        val timeout = if (namespace == CloudBackupNamespace.DEVELOPMENT) 40_000L else 120_000L
        return forAccount(expectedUid, timeout, namespace) { check ->
            val token = tokenFor(expectedUid)
            check()
            val legacy = transport.listOriginals(token, namespace)
            if (!access.supportsResumableTransfers) return@forAccount legacy
            check()
            val currentToken = tokenFor(expectedUid)
            check()
            val resumable = transport.listOriginals(currentToken, namespace, resumable = true)
            (resumable + legacy).distinctBy(CloudOriginal::uploadId).sortedWith(
                compareByDescending<CloudOriginal> { it.verifiedAtMs }.thenBy { it.uploadId },
            )
        }
    }

    /**
     * Authorizes direct HTTP byte-range playback for a verified video without
     * creating a gallery file. The URL is a short-lived private bearer token.
     *
     * Streamed ranges cannot be fully SHA-256 checked before decoding; the
     * object generation and metadata are validated before URL issuance.
     */
    suspend fun openVideoStream(original: CloudOriginal, expectedUid: String): CloudVideoStreamCapability {
        if (transport.isPrivateVault) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        if (!isCloudVideoStreamable(original)) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        val namespace = requireAccess(expectedUid).namespace
        return forAccount(expectedUid, 40_000L, namespace) { check ->
            val token = tokenFor(expectedUid)
            check()
            val target = transport.getDownload(expectedUid, token, original, namespace)
            check()
            transport.verifyVideoRange(target, original, check)
            check()
            CloudVideoStreamCapability(
                signedUrl = target.url,
                generation = target.generation,
                obtainedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
        }
    }

    /** MediaPlayer may log a failing URL. For the vault, pass only an open, unlinked file. */
    suspend fun openPrivateVideo(original: CloudOriginal, expectedUid: String): ParcelFileDescriptor {
        if (!canPlayPrivateVideo(original)) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        return downloadPrivateOriginal(original, expectedUid)
    }

    private suspend fun downloadPrivateOriginal(original: CloudOriginal, expectedUid: String): ParcelFileDescriptor {
        val namespace = requireAccess(expectedUid).namespace
        if (!mutex.tryLock()) failBackup(CloudBackupResult.FAILED)
        var descriptor: ParcelFileDescriptor? = null
        try {
            val timeout = if (namespace == CloudBackupNamespace.DEVELOPMENT) 180_000L else 480_000L
            return forAccount(expectedUid, timeout, namespace) { check ->
                val token = tokenFor(expectedUid)
                check()
                val target = transport.getDownload(expectedUid, token, original, namespace)
                withContext(Dispatchers.IO) {
                    if (appContext.cacheDir.usableSpace < original.sizeBytes) failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                    val temporary = File.createTempFile("gallipick-vault-", ".tmp", appContext.cacheDir)
                    try {
                        FileOutputStream(temporary).use { output ->
                            val readOnly = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
                            descriptor = readOnly
                            if (!temporary.delete()) failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                            transport.download(target, original, output, check)
                            readOnly
                        }
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                }
            }
        } catch (failure: Throwable) {
            runCatching { descriptor?.close() }
            throw failure
        } finally {
            mutex.unlock()
        }
    }

    suspend fun restore(original: CloudOriginal, expectedUid: String): Uri {
        if (transport.isPrivateVault) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        val access = requireAccess(expectedUid)
        val namespace = access.namespace
        val resumable = original.transferProtocolVersion == CLOUD_RESUMABLE_PROTOCOL_VERSION
        if (resumable && !access.supportsResumableTransfers) failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
        if (!mutex.tryLock()) failBackup(CloudBackupResult.FAILED)
        return try {
            val timeout = when {
                resumable -> null
                namespace == CloudBackupNamespace.DEVELOPMENT -> 180_000L
                else -> 480_000L
            }
            forAccount(expectedUid, timeout, namespace) { check ->
                val token = tokenFor(expectedUid)
                check()
                val target = transport.getDownload(expectedUid, token, original, namespace)
                check()
                restoreStore.save(original, check) { output -> transport.download(target, original, output, check) }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Decodes a verified cloud photo to a bounded bitmap without publishing an
     * image to MediaStore. Vault originals are decoded from an unlinked file.
     */
    suspend fun previewPhoto(
        original: CloudOriginal,
        expectedUid: String,
        maxEdgePx: Int = 1024,
    ): Bitmap {
        if (!canPreviewPhoto(original) || maxEdgePx !in 128..2048) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        if (isPrivateVault) {
            return downloadPrivateOriginal(original, expectedUid).use { descriptor ->
                withContext(Dispatchers.IO) {
                    val source = ImageDecoder.createSource {
                        AssetFileDescriptor(descriptor.dup(), 0L, original.sizeBytes)
                    }
                    val bitmap = decodeCloudPhoto(source, maxEdgePx)
                    if (!ownsAccount(expectedUid)) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
                    transport.assertVaultSessionOpen()
                    bitmap
                }
            }
        }
        val namespace = requireAccess(expectedUid).namespace
        mutex.lock()
        try {
            return forAccount(expectedUid, 180_000L, namespace) { check ->
                val token = tokenFor(expectedUid)
                check()
                val target = transport.getDownload(expectedUid, token, original, namespace)
                withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("gallipick-preview-", ".tmp", appContext.cacheDir)
                    try {
                        FileOutputStream(temporary).use { output ->
                            transport.download(target, original, output, check)
                        }
                        check()
                        val source = ImageDecoder.createSource(temporary)
                        val result = decodeCloudPhoto(source, maxEdgePx)
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

    private fun decodeCloudPhoto(source: ImageDecoder.Source, maxEdgePx: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = maxEdgePx.toDouble() / maxOf(info.size.width, info.size.height)
            if (scale < 1) decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun requireAccess(uid: String): CloudBackupAccess {
        if (!ownsAccount(uid)) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
        return accessFor(uid) ?: failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
    }

    private suspend fun <T : Any> forAccount(
        uid: String, timeoutMillis: Long?, namespace: CloudBackupNamespace, block: suspend (() -> Unit) -> T,
    ): T {
        if (!ownsAccount(uid)) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
        val execute: suspend () -> T = {
            coroutineScope {
                val operation = async {
                    val operationContext = currentCoroutineContext()
                    val check = {
                        operationContext.ensureActive()
                        if (!ownsAccount(uid)) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
                        if (accessFor(uid)?.namespace != namespace) failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
                        transport.assertVaultSessionOpen()
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
        }
        return if (timeoutMillis == null) execute() else withTimeoutOrNull(timeoutMillis) { execute() }
            ?: throw cloudBackupNetworkError(CloudBackupNetworkFailure.TIMEOUT)
    }

    private suspend fun transferResumable(
        item: MediaItem,
        mimeType: String,
        uid: String,
        namespace: CloudBackupNamespace,
        onProgress: (CloudBackupProgress) -> Unit,
        onPhase: (CloudBackupPhase) -> Unit,
        checkAccount: () -> Unit,
    ): CloudBackupResult {
        fun checkUploadAccess() {
            checkAccount()
            val access = accessFor(uid)
            if (access?.allowsResumableUpload(System.currentTimeMillis()) != true) {
                failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
            }
            if (item.sizeBytes !in 1..access.maximumResumableOriginalBytes) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
        }

        checkUploadAccess()
        onPhase(CloudBackupPhase.READING_ORIGINAL)
        val hash = withContext(Dispatchers.IO) {
            assertOriginal(item, requiredFolder = null)
            try {
                readOriginal(item) { input ->
                    MediaHasher.sha256(BackupStagingInput(input, null, item.sizeBytes), ::checkUploadAccess)
                }
            } catch (_: IOException) {
                failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            } catch (_: SecurityException) {
                failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            }
        }
        assertOriginal(item, requiredFolder = null)
        val beginToken = tokenFor(uid)
        checkUploadAccess()
        val (upload, initialState) = transport.beginResumable(
            beginToken, hash, item.sizeBytes, mimeType, item.displayName, CloudUploadMetadata.from(item),
        )
        checkAccount()
        if (initialState == CloudResumableState.VERIFIED) return CloudBackupResult.VERIFIED

        var verification = initialState
        if (initialState == CloudResumableState.RESUMABLE) {
            onPhase(CloudBackupPhase.UPLOADING)
            onProgress(CloudBackupProgress(upload.part(upload.nextPartIndex)?.offsetBytes ?: upload.sizeBytes, upload.sizeBytes))
            CloudResumableSource { openOriginal(item) }.use { source ->
                for (index in upload.nextPartIndex until upload.partCount) {
                    val part = checkNotNull(upload.part(index))
                    uploadResumablePart(uid, upload, part, mimeType, namespace, source, ::checkUploadAccess)
                    checkAccount()
                    onProgress(CloudBackupProgress(part.offsetBytes + part.sizeBytes, upload.sizeBytes))
                }
            }
            assertOriginal(item, requiredFolder = null)
            val finishToken = tokenFor(uid)
            checkUploadAccess()
            onPhase(CloudBackupPhase.VERIFYING)
            verification = transport.finishResumable(upload.id, finishToken)
        }
        onPhase(CloudBackupPhase.VERIFYING)
        return waitForResumableVerification(upload.id, uid, verification, checkAccount)
    }

    private suspend fun uploadResumablePart(
        uid: String,
        upload: CloudResumableUpload,
        part: CloudResumablePart,
        mimeType: String,
        namespace: CloudBackupNamespace,
        source: CloudResumableSource,
        check: () -> Unit,
    ) {
        repeat(RESUMABLE_PART_ATTEMPTS) { attempt ->
            try {
                check()
                val token = tokenFor(uid)
                check()
                val target = transport.resumablePart(uid, token, upload, part, mimeType, namespace)
                if (target != null) transport.putResumablePart(target, part, source, check)
                return
            } catch (error: CloudBackupException) {
                if (error.result != CloudBackupResult.NETWORK_ERROR || attempt + 1 == RESUMABLE_PART_ATTEMPTS) throw error
                delay(1_000L * (attempt + 1))
            }
        }
    }

    private suspend fun waitForResumableVerification(
        uploadId: String, uid: String, initialState: CloudResumableState, checkAccount: () -> Unit,
    ): CloudBackupResult {
        var state = initialState
        for (attempt in 0..RESUMABLE_VERIFICATION_POLLS) {
            checkAccount()
            when (state) {
                CloudResumableState.VERIFIED -> return CloudBackupResult.VERIFIED
                CloudResumableState.FAILED -> return CloudBackupResult.INTEGRITY_ERROR
                CloudResumableState.RESUMABLE, CloudResumableState.CANCELLED -> return CloudBackupResult.NETWORK_ERROR
                CloudResumableState.VERIFYING -> Unit
            }
            if (attempt == RESUMABLE_VERIFICATION_POLLS) break
            delay(RESUMABLE_VERIFICATION_INTERVAL_MS)
            try {
                val token = tokenFor(uid)
                checkAccount()
                state = transport.resumableStatus(uploadId, token)
            } catch (error: CloudBackupException) {
                if (error.result != CloudBackupResult.NETWORK_ERROR) throw error
                return CloudBackupResult.VERIFICATION_PENDING
            }
        }
        return CloudBackupResult.VERIFICATION_PENDING
    }

    private suspend fun transfer(
        item: MediaItem,
        mimeType: String,
        uid: String,
        namespace: CloudBackupNamespace,
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
                if (appContext.cacheDir.usableSpace < item.sizeBytes) failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
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
            val currentAccess = accessFor(uid)
            if (currentAccess?.allowsUpload(System.currentTimeMillis()) != true) {
                failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
            }
            if (item.sizeBytes > currentAccess.maximumOriginalBytes) failBackup(CloudBackupResult.UNSUPPORTED_MEDIA)
            val beginToken = tokenFor(uid)
            checkAccount()
            val target = transport.begin(uid, beginToken, hash, item.sizeBytes, mimeType, item.displayName,
                CloudUploadMetadata.from(item), namespace)
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
                transport.finish(target.id, finishToken, namespace)
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

    private fun <T> readOriginal(item: MediaItem, read: (InputStream) -> T): T = openOriginal(item).use(read)

    private fun openOriginal(item: MediaItem): InputStream {
        val uri = MediaStore.setRequireOriginal(Uri.parse(item.uri))
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) }
            val descriptor = resolver.openTypedAssetFileDescriptor(uri, "*/*", options)
                ?: failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
            return try { descriptor.createInputStream() } catch (failure: Throwable) {
                descriptor.close()
                throw failure
            }
        }
        return resolver.openInputStream(uri) ?: failBackup(CloudBackupResult.ORIGINAL_UNAVAILABLE)
    }

}

internal class BackupStagingInput(
    private val original: InputStream,
    private val output: OutputStream?,
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
            output?.write(buffer, offset, count)
        }
        return count
    }
}
