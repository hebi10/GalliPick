package com.hebi.gallipick.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.ScanPhase
import com.hebi.gallipick.domain.ScanState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local-only media catalog. The system remains the authority for every destructive action. */
class MediaRepository(context: Context) {
    internal val appContext: Context = context.applicationContext
    internal val database = MediaDatabase.get(appContext)
    internal val dao = database.mediaDao()
    internal val reader = MediaStoreReader(appContext)
    private val workManager by lazy { WorkManager.getInstance(appContext) }

    private val catalog = Coordinator.catalog(database)

    val catalogReady: StateFlow<Boolean> = catalog.ready
    val catalogError: StateFlow<String?> = catalog.error
    val media: Flow<List<MediaItem>> = catalog.media
    val trashed: Flow<List<MediaItem>> = catalog.trashed
    val protectedFolders: Flow<Set<String>> = catalog.protectedFolders
    val scan: Flow<ScanState> = dao.observeScan().map { it?.state() ?: ScanState() }
    val duplicates: Flow<List<DuplicateGroup>> = catalog.duplicates

    suspend fun refresh() = withContext(Dispatchers.IO) {
        Coordinator.refreshMutex.withLock {
            catalog.markRefreshing()
            try {
                val snapshot = reader.load()
                val rows = snapshot.items.map(CachedMedia::from)
                dao.replaceAccessible(rows, snapshot.volumeVersions)
                // Room invalidation is asynchronous. Wait before reopening a shared replay.
                catalog.awaitSnapshot(rows, snapshot.volumeVersions)
                catalog.markReady()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Preserve durable checkpoints and the previous DB transaction for retry,
                // while preventing stale/revoked rows from reaching any catalog consumer.
                catalog.markFailed("파일 목록을 확인하지 못했습니다. 접근 권한을 확인하고 다시 시도해 주세요.")
                throw exception
            }
        }
    }

    suspend fun verifiedCatalog(): VerifiedCatalog = catalog.snapshot()

    fun startScan() = launchControl { queueNewSession(clearFailures = true) }
    fun resumeScan() = launchControl { queueNewSession(clearFailures = false) }

    fun pauseScan() = launchControl {
        Coordinator.controlMutex.withLock {
            val previous = dao.session() ?: ScanSession()
            dao.setSession(previous.copy(token = UUID.randomUUID().toString(),
                phase = ScanPhase.PAUSED.name, message = "분석을 일시정지했습니다."))
            workManager.cancelUniqueWork(WORK_NAME).result.get()
        }
    }

    suspend fun setProtected(uri: String, protected: Boolean) = withContext(Dispatchers.IO) {
        if (protected) dao.protect(ProtectedMedia(uri)) else dao.unprotect(uri)
    }

    /** A protected folder includes its subfolders. An empty path means root files only. */
    suspend fun setFolderProtected(path: String, protected: Boolean) = withContext(Dispatchers.IO) {
        val normalized = MediaCachePolicy.normalizeFolder(path)
        if (protected) dao.protectFolder(ProtectedFolder(normalized))
        else dao.unprotectFolder(normalized)
    }

    suspend fun clearAnalysisCache() = withContext(Dispatchers.IO) {
        Coordinator.controlMutex.withLock {
            val old = dao.session() ?: ScanSession()
            dao.setSession(old.copy(token = UUID.randomUUID().toString(),
                phase = ScanPhase.IDLE.name, completed = 0, total = 0, skipped = 0,
                currentUri = "", currentAttempts = 0, message = "분석 캐시를 비웠습니다."))
            workManager.cancelUniqueWork(WORK_NAME).result.get()
            dao.clearHashes()
        }
    }

    /** Freshly verifies existence, identity, favorites and protection before requesting trash. */
    suspend fun revalidate(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        val fresh = freshUnchanged(items).filterNot { it.isTrashed || it.isFavorite }
        val itemUris = dao.allProtection().mapTo(hashSetOf()) { it.uri }
        val folders = dao.allFolders().map { it.path }
        fresh.filterNot { MediaCachePolicy.isProtected(it.uri, it.relativePath, itemUris, folders) }
    }

    /** Restoring a protected/favorite item is allowed; changed or inaccessible rows are skipped. */
    suspend fun revalidateForRestore(items: List<MediaItem>): List<MediaItem> =
        withContext(Dispatchers.IO) { freshUnchanged(items).filter { it.isTrashed } }

    suspend fun hasSurvivingCopy(group: DuplicateGroup, selectedUris: Set<String>): Boolean =
        withContext(Dispatchers.IO) {
            for (item in group.items) {
                if (item.uri in selectedUris || item.volumeVersion.isBlank()) continue
                val proof = dao.hash(item.uri, item.fingerprint) ?: continue
                if (proof.sha256 != group.hash || proof.volumeVersion != item.volumeVersion) continue
                if (freshUnchanged(listOf(item)).any { !it.isTrashed }) return@withContext true
            }
            false
        }

    private suspend fun freshUnchanged(items: List<MediaItem>): List<MediaItem> =
        MediaCachePolicy.freshUnchanged(items,
            dao.allVolumeVersions().associate { it.volume to it.version },
            reader::volumeVersion, reader::current)

    private fun launchControl(action: suspend () -> Unit) {
        Coordinator.scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                Coordinator.controlMutex.withLock {
                    val previous = dao.session() ?: ScanSession()
                    dao.setSession(previous.copy(phase = ScanPhase.FAILED.name,
                        message = "분석 작업을 시작하지 못했습니다. 다시 시도해 주세요."))
                }
            }
        }
    }

    private suspend fun queueNewSession(clearFailures: Boolean) {
        Coordinator.controlMutex.withLock {
            workManager.cancelUniqueWork(WORK_NAME).result.get()
            if (clearFailures) dao.clearFailedHashes()
            val token = UUID.randomUUID().toString()
            dao.setSession(ScanSession(token = token, phase = ScanPhase.QUEUED.name,
                message = "기기 안에서 중복 분석을 준비합니다."))
            enqueue(token, ExistingWorkPolicy.REPLACE)
        }
    }

    internal fun enqueue(token: String, policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<DuplicateScanWorker>()
            .setInputData(workDataOf(TOKEN_KEY to token))
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, policy, work).result.get()
    }

    internal suspend fun saveHashIfCurrent(token: String, hash: CachedHash) {
        Coordinator.controlMutex.withLock {
            if (dao.session()?.token != token) return@withLock
            database.withTransaction {
                val item = dao.media(hash.uri)
                if (item != null && !item.isTrashed && item.fingerprint == hash.fingerprint &&
                    hash.volumeVersion.isNotBlank() && dao.volumeVersion(item.volume) == hash.volumeVersion) {
                    dao.putHash(hash)
                }
            }
        }
    }

    internal suspend fun beginFile(token: String, uri: String): Boolean =
        Coordinator.controlMutex.withLock {
            val session = dao.session() ?: return@withLock false
            if (session.token != token || session.phase != ScanPhase.RUNNING.name) return@withLock false
            val attempts = if (session.currentUri == uri) session.currentAttempts else 0
            if (attempts >= 3) {
                dao.setSession(session.copy(phase = ScanPhase.PAUSED.name,
                    message = "같은 파일의 분석이 3번 중단되어 일시정지했습니다. 재개하면 다시 시도합니다."))
                return@withLock false
            }
            dao.setSession(session.copy(currentUri = uri, currentAttempts = attempts + 1))
            true
        }

    internal suspend fun updateSession(token: String, update: (ScanSession) -> ScanSession): Boolean =
        Coordinator.controlMutex.withLock {
            val session = dao.session() ?: return@withLock false
            if (session.token != token || session.phase == ScanPhase.PAUSED.name) return@withLock false
            dao.setSession(update(session))
            true
        }

    internal suspend fun continueScan(token: String) {
        Coordinator.controlMutex.withLock {
            val session = dao.session() ?: return@withLock
            if (session.token != token || session.phase != ScanPhase.RUNNING.name) return@withLock
            dao.setSession(session.copy(phase = ScanPhase.QUEUED.name,
                message = "완료한 파일을 저장했습니다. 이어서 분석합니다."))
            // Sharing this lock with pause prevents a paused run from appending a new job.
            enqueue(token, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    companion object {
        internal const val WORK_NAME = "gallipick-duplicate-analysis"
        internal const val TOKEN_KEY = "scan-token"

    }
}

internal object Coordinator {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val controlMutex = Mutex()
    val refreshMutex = Mutex()
    @Volatile private var sharedCatalog: SharedMediaCatalog? = null

    fun catalog(database: MediaDatabase): SharedMediaCatalog = sharedCatalog ?: synchronized(this) {
        sharedCatalog ?: database.mediaDao().let { dao ->
            SharedMediaCatalog(dao.observeMedia(), dao.observeProtection(), dao.observeFolders(),
                dao.observeVolumeVersions(), dao.observeHashes(), scope)
        }.also { sharedCatalog = it }
    }
}
