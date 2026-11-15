package com.hebi.gallipick.data.cloud

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CloudRestoreStore(private val context: Context) {
    private val resolver = context.contentResolver
    private val journal = context.getSharedPreferences("cloud_restore", Context.MODE_PRIVATE)

    suspend fun save(item: CloudOriginal, checkAccount: () -> Unit, writeVerified: suspend (OutputStream) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            val video = item.mimeType.startsWith("video/")
            val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            var pending: Uri? = null
            var published = false
            try {
                checkAccount()
                journal.getString(PENDING_URI, null)?.let { value ->
                    deleteIfPending(Uri.parse(value))
                    journal.edit().remove(PENDING_URI).commit()
                }
                val storage = context.getSystemService(StorageManager::class.java)
                if (storage == null || storage.getAllocatableBytes(StorageManager.UUID_DEFAULT) < item.sizeBytes) {
                    failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                }
                checkAccount()
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/GalliPick/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                pending = uri
                if (!journal.edit().putString(PENDING_URI, uri.toString()).commit()) {
                    failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                }
                val descriptor = resolver.openFileDescriptor(uri, "w") ?: failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    writeVerified(output)
                    output.fd.sync()
                }
                checkAccount()
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, publish, null, null) != 1) failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
                published = true
                journal.edit().remove(PENDING_URI).apply()
                uri
            } catch (_: IOException) {
                failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
            } catch (_: SecurityException) {
                failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
            } finally {
                if (pending != null && !published) {
                    // Retain the journal for retry if MediaStore is temporarily unavailable.
                    runCatching { deleteIfPending(pending) }.onSuccess { journal.edit().remove(PENDING_URI).apply() }
                }
            }
        }

    private fun deleteIfPending(uri: Uri) {
        val column = MediaStore.MediaColumns.IS_PENDING
        val isPending = resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) == 1
        } ?: failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
        if (isPending && resolver.delete(uri, "$column = ?", arrayOf("1")) != 1) {
            failBackup(CloudBackupResult.DEVICE_STORAGE_ERROR)
        }
    }

    private companion object {
        const val PENDING_URI = "pending_media_uri"
    }
}
