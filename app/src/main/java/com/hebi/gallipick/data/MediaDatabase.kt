package com.hebi.gallipick.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.ScanPhase
import com.hebi.gallipick.domain.ScanState
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media_cache")
internal data class CachedMedia(
    @PrimaryKey val uri: String,
    val id: Long,
    val volume: String,
    val displayName: String,
    val kind: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTakenMs: Long,
    val dateAddedMs: Long,
    val dateModifiedMs: Long,
    val generationModified: Long,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val fingerprint: String,
) {
    fun item(protected: Boolean = false) = MediaItem(
        uri, id, volume, displayName, MediaKind.valueOf(kind), mimeType, relativePath,
        sizeBytes, width, height, durationMs, dateTakenMs, dateAddedMs, dateModifiedMs,
        generationModified, isFavorite, protected, isTrashed,
    )

    companion object {
        fun from(item: MediaItem) = with(item) {
            CachedMedia(uri, id, volume, displayName, kind.name, mimeType, relativePath,
                sizeBytes, width, height, durationMs, dateTakenMs, dateAddedMs, dateModifiedMs,
                generationModified, isFavorite, isTrashed, fingerprint)
        }
    }
}

/** A null digest records a failed read; it can never qualify as a duplicate. */
@Entity(tableName = "media_hash", primaryKeys = ["uri", "fingerprint"])
internal data class CachedHash(
    val uri: String,
    val fingerprint: String,
    val sha256: String?,
    @ColumnInfo(defaultValue = "''") val volumeVersion: String = "",
)

@Entity(tableName = "media_volume_version")
internal data class MediaVolumeVersion(@PrimaryKey val volume: String, val version: String)

// Protection is deliberately independent of the replaceable media/hash cache.
@Entity(tableName = "protected_media")
internal data class ProtectedMedia(@PrimaryKey val uri: String)

@Entity(tableName = "protected_folder")
internal data class ProtectedFolder(@PrimaryKey val path: String)

@Entity(tableName = "scan_session")
internal data class ScanSession(
    @PrimaryKey val id: Int = 1,
    val token: String = "",
    val phase: String = ScanPhase.IDLE.name,
    val completed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
    val currentUri: String = "",
    val currentAttempts: Int = 0,
) {
    fun state() = ScanState(
        phase = runCatching { ScanPhase.valueOf(phase) }.getOrDefault(ScanPhase.IDLE),
        completed = completed, total = total, skipped = skipped, message = message,
    )
}

@Dao
internal abstract class MediaDao {
    @Query("SELECT * FROM media_cache ORDER BY dateTakenMs DESC, dateAddedMs DESC")
    abstract fun observeMedia(): Flow<List<CachedMedia>>
    @Query("SELECT * FROM media_cache")
    abstract suspend fun allMedia(): List<CachedMedia>
    @Query("SELECT * FROM media_cache WHERE uri = :uri LIMIT 1")
    abstract suspend fun media(uri: String): CachedMedia?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMedia(items: List<CachedMedia>)
    @Query("DELETE FROM media_cache")
    abstract suspend fun deleteMedia()
    @Query("DELETE FROM media_hash WHERE NOT EXISTS (SELECT 1 FROM media_cache AS m WHERE m.uri = media_hash.uri AND m.fingerprint = media_hash.fingerprint AND m.isTrashed = 0)")
    abstract suspend fun pruneHashes()
    @Query("DELETE FROM media_hash WHERE uri IN (SELECT uri FROM media_cache WHERE volume IN (:volumes))")
    abstract suspend fun invalidateVolumeHashes(volumes: Set<String>)
    @Query("SELECT * FROM media_volume_version")
    abstract fun observeVolumeVersions(): Flow<List<MediaVolumeVersion>>
    @Query("SELECT * FROM media_volume_version")
    abstract suspend fun allVolumeVersions(): List<MediaVolumeVersion>
    @Query("SELECT version FROM media_volume_version WHERE volume = :volume LIMIT 1")
    abstract suspend fun volumeVersion(volume: String): String?
    @Query("DELETE FROM media_volume_version")
    abstract suspend fun deleteVolumeVersions()
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertVolumeVersions(versions: List<MediaVolumeVersion>)

    /** Rows, provider epochs, and digest invalidation commit or roll back together. */
    @Transaction
    open suspend fun replaceAccessible(items: List<CachedMedia>, versions: Map<String, String>) {
        val previous = allVolumeVersions().associate { it.volume to it.version }
        val invalidated = MediaCachePolicy.invalidatedVolumes(previous, versions)
        if (invalidated.isNotEmpty()) invalidateVolumeHashes(invalidated)
        deleteMedia()
        insertMedia(items)
        pruneHashes()
        deleteVolumeVersions()
        insertVolumeVersions(versions.map { (volume, version) -> MediaVolumeVersion(volume, version) })
    }

    @Query("SELECT * FROM media_hash")
    abstract fun observeHashes(): Flow<List<CachedHash>>
    @Query("SELECT * FROM media_hash")
    abstract suspend fun allHashes(): List<CachedHash>
    @Query("SELECT * FROM media_hash WHERE uri = :uri AND fingerprint = :fingerprint LIMIT 1")
    abstract suspend fun hash(uri: String, fingerprint: String): CachedHash?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putHash(hash: CachedHash)
    @Query("DELETE FROM media_hash")
    abstract suspend fun clearHashes()
    @Query("DELETE FROM media_hash WHERE sha256 IS NULL")
    abstract suspend fun clearFailedHashes()

    @Query("SELECT * FROM protected_media")
    abstract fun observeProtection(): Flow<List<ProtectedMedia>>
    @Query("SELECT * FROM protected_folder")
    abstract fun observeFolders(): Flow<List<ProtectedFolder>>
    @Query("SELECT * FROM protected_media")
    abstract suspend fun allProtection(): List<ProtectedMedia>
    @Query("SELECT * FROM protected_folder")
    abstract suspend fun allFolders(): List<ProtectedFolder>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun protect(item: ProtectedMedia)
    @Query("DELETE FROM protected_media WHERE uri = :uri")
    abstract suspend fun unprotect(uri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun protectFolder(folder: ProtectedFolder)
    @Query("DELETE FROM protected_folder WHERE path = :path")
    abstract suspend fun unprotectFolder(path: String)

    @Query("SELECT * FROM scan_session WHERE id = 1")
    abstract fun observeScan(): Flow<ScanSession?>
    @Query("SELECT * FROM scan_session WHERE id = 1")
    abstract suspend fun session(): ScanSession?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setSession(session: ScanSession)
}

@Database(
    entities = [CachedMedia::class, CachedHash::class, ProtectedMedia::class,
        ProtectedFolder::class, ScanSession::class, MediaVolumeVersion::class],
    version = 2,
    exportSchema = false,
)
internal abstract class MediaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS media_volume_version (volume TEXT NOT NULL PRIMARY KEY, version TEXT NOT NULL)")
                db.execSQL("ALTER TABLE media_hash ADD COLUMN volumeVersion TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var instance: MediaDatabase? = null
        fun get(context: Context): MediaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                MediaDatabase::class.java, "gallipick-local.db").addMigrations(migration1To2).build().also { instance = it }
        }
    }
}
