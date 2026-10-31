package com.hebi.gallipick.data

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.OperationCanceledException
import android.provider.MediaStore
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Reads only the MediaStore rows visible under the current permission grant. */
internal class MediaStoreReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val appContext = context.applicationContext

    suspend fun load(): List<MediaItem> = withContext(Dispatchers.IO) {
        val volumes = try {
            MediaStore.getExternalVolumeNames(appContext)
        } catch (_: SecurityException) {
            emptySet()
        }
        val result = mutableListOf<MediaItem>()
        for (volume in volumes.sorted()) {
            ensureActive()
            for (kind in MediaKind.entries) {
                ensureActive()
                val collection = when (kind) {
                    MediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(volume)
                    MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
                }
                // Keep the category result local: permission revocation during cursor
                // traversal must not leave a partially collected category in the cache.
                val rows = try {
                    query(collection, collection, volume, kind)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Permission or a removable volume can disappear between
                    // enumeration and query. Do not retain this category's old rows.
                    emptyList()
                }
                result.addAll(rows)
            }
        }
        result.distinctBy { it.uri }
    }

    /** A fresh row is required immediately before a destructive system request. */
    suspend fun current(uri: String): MediaItem? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content" || parsed.authority != MediaStore.AUTHORITY) {
            return@withContext null
        }
        val segments = parsed.pathSegments
        if (segments.size != 4 || segments[2] != "media" || segments[3].toLongOrNull() == null) {
            return@withContext null
        }
        val volume = segments[0]
        val kind = when (segments[1]) {
            "images" -> MediaKind.PHOTO
            "video" -> MediaKind.VIDEO
            else -> return@withContext null
        }
        val collection = when (kind) {
            MediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(volume)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        }
        try {
            query(parsed, collection, volume, kind).singleOrNull()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun query(
        uri: Uri,
        collection: Uri,
        volume: String,
        kind: MediaKind,
    ): List<MediaItem> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val arguments = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            }
            val rows = resolver.query(uri, PROJECTION, arguments, signal)?.use { cursor ->
                val columns = Columns(cursor)
                buildList {
                    while (cursor.moveToNext()) {
                        continuation.context.ensureActive()
                        if (columns.long(MediaStore.MediaColumns.IS_PENDING) != 0L) continue
                        val id = columns.long(MediaStore.MediaColumns._ID)
                        if (id <= 0L) continue
                        val rawWidth = columns.long(MediaStore.MediaColumns.WIDTH).toPositiveInt()
                        val rawHeight = columns.long(MediaStore.MediaColumns.HEIGHT).toPositiveInt()
                        val rotation = columns.long(MediaStore.MediaColumns.ORIENTATION)
                        val swapsAxes = (rotation % 180L + 180L) % 180L == 90L
                        add(
                            MediaItem(
                                uri = ContentUris.withAppendedId(collection, id).toString(),
                                id = id,
                                volume = volume,
                                displayName = columns.string(MediaStore.MediaColumns.DISPLAY_NAME)
                                    .ifBlank { "$id" },
                                kind = kind,
                                mimeType = columns.string(MediaStore.MediaColumns.MIME_TYPE)
                                    .ifBlank { if (kind == MediaKind.PHOTO) "image/*" else "video/*" },
                                relativePath = columns.string(MediaStore.MediaColumns.RELATIVE_PATH),
                                sizeBytes = columns.long(MediaStore.MediaColumns.SIZE).coerceAtLeast(0),
                                width = if (swapsAxes) rawHeight else rawWidth,
                                height = if (swapsAxes) rawWidth else rawHeight,
                                durationMs = if (kind == MediaKind.VIDEO) {
                                    columns.long(MediaStore.MediaColumns.DURATION).coerceAtLeast(0)
                                } else 0L,
                                dateTakenMs = columns.long(MediaStore.MediaColumns.DATE_TAKEN).coerceAtLeast(0),
                                dateAddedMs = columns.long(MediaStore.MediaColumns.DATE_ADDED).secondsToMillis(),
                                dateModifiedMs = columns.long(MediaStore.MediaColumns.DATE_MODIFIED).secondsToMillis(),
                                generationModified = columns.long(MediaStore.MediaColumns.GENERATION_MODIFIED)
                                    .coerceAtLeast(0),
                                isFavorite = columns.long(MediaStore.MediaColumns.IS_FAVORITE) != 0L,
                                isTrashed = columns.long(MediaStore.MediaColumns.IS_TRASHED) != 0L,
                            ),
                        )
                    }
                }
            }.orEmpty()
            continuation.context.ensureActive()
            continuation.resumeWith(Result.success(rows))
        } catch (exception: OperationCanceledException) {
            continuation.cancel(CancellationException("미디어 조회가 취소되었습니다.", exception))
        } catch (exception: Exception) {
            continuation.resumeWith(Result.failure(exception))
        }
    }

    private class Columns(private val cursor: Cursor) {
        private val indexes = PROJECTION.associateWith { cursor.getColumnIndex(it) }
        fun long(name: String): Long = indexes[name]?.takeIf { it >= 0 && !cursor.isNull(it) }
            ?.let(cursor::getLong) ?: 0L
        fun string(name: String): String = indexes[name]?.takeIf { it >= 0 && !cursor.isNull(it) }
            ?.let(cursor::getString).orEmpty()
    }

    private fun Long.toPositiveInt(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    private fun Long.secondsToMillis(): Long = coerceIn(0, Long.MAX_VALUE / 1_000L) * 1_000L

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
        )
    }
}
