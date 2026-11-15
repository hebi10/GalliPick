package com.hebi.gallipick.data.cloud

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.hebi.gallipick.data.MediaRepository
import com.hebi.gallipick.domain.CloudSpaceMatchedFiles
import com.hebi.gallipick.domain.CloudSpacePolicy
import com.hebi.gallipick.domain.MediaHasher
import com.hebi.gallipick.domain.MediaItem
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class CloudSpaceBackupProof(val sizeBytes: Long, val sha256: String)

/** Compares on-device originals with verified catalog metadata without staging or changing media. */
internal class CloudSpaceAnalysis(context: Context, private val repository: MediaRepository) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun matchOnDevice(
        eligible: List<MediaItem>,
        catalog: Set<CloudSpaceBackupProof>,
        isCurrentAccount: () -> Boolean,
    ): CloudSpaceMatchedFiles = withContext(Dispatchers.IO) {
        val scanContext = currentCoroutineContext()
        val checkAccount = {
            scanContext.ensureActive()
            if (!isCurrentAccount()) throw CancellationException("Cloud account changed")
        }
        checkAccount()
        val validProofs = catalog.filterTo(hashSetOf()) {
            it.sizeBytes in 1..CLOUD_BACKUP_MAX_BYTES && SHA256.matches(it.sha256)
        }
        val cloudSizes = validProofs.mapTo(hashSetOf()) { it.sizeBytes }
        val scan = CloudSpacePolicy.scanCandidates(eligible, cloudSizes, System.currentTimeMillis())
        val matches = buildList {
            for (item in scan) {
                checkAccount()
                val before = repository.revalidate(listOf(item)).singleOrNull() ?: continue
                if (!CloudSpacePolicy.sameOriginal(item, before)) continue
                val sha256 = try {
                    hashOriginal(before, checkAccount)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: SecurityException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: UnsupportedOperationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: continue
                if (CloudSpaceBackupProof(before.sizeBytes, sha256) !in validProofs) continue
                val after = repository.revalidate(listOf(before)).singleOrNull() ?: continue
                if (CloudSpacePolicy.sameOriginal(before, after)) add(after)
            }
        }
        checkAccount()
        CloudSpaceMatchedFiles(matches, scan.size, eligible.count { it.sizeBytes in cloudSizes })
    }

    private fun hashOriginal(item: MediaItem, checkAccount: () -> Unit): String? {
        val uri = MediaStore.setRequireOriginal(Uri.parse(item.uri))
        fun hash(input: InputStream): String? {
            val counted = BoundedOriginal(input, item.sizeBytes)
            val sha256 = MediaHasher.sha256(counted, checkAccount)
            return sha256.takeIf { counted.bytesRead == item.sizeBytes }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return resolver.openInputStream(uri)?.use(::hash)
        val options = Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) }
        return resolver.openTypedAssetFileDescriptor(uri, "*/*", options)?.use { descriptor ->
            descriptor.createInputStream().use(::hash)
        }
    }

    private class BoundedOriginal(source: InputStream, private val expectedBytes: Long) : FilterInputStream(source) {
        var bytesRead = 0L
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = super.read(buffer, offset, minOf(length.toLong(), expectedBytes - bytesRead + 1).toInt())
            if (count == 0) throw IOException("Original read made no progress")
            if (count > 0) recordBytes(count)
            return count
        }

        override fun read(): Int = super.read().also { if (it >= 0) recordBytes(1) }

        private fun recordBytes(bytes: Int) {
            bytesRead += bytes
            if (bytesRead > expectedBytes) throw IOException("Original changed while hashing")
        }
    }

    private companion object { val SHA256 = Regex("[a-f0-9]{64}") }
}
