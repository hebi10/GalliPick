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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    private val catalog: Flow<List<MediaItem>> = combine(
        dao.observeMedia(), dao.observeProtection(), dao.observeFolders(),
    ) { rows, protectedItems, folders ->
        val itemUris = protectedItems.mapTo(hashSetOf()) { it.uri }
        val paths = folders.map { it.path }
        rows.map { it.item(MediaCachePolicy.isProtected(it.uri, it.relativePath, itemUris, paths)) }
    }.flowOn(Dispatchers.Default)

    val media: Flow<List<MediaItem>> = catalog.map { rows -> rows.filterNot { it.isTrashed } }
    val trashed: Flow<List<MediaItem>> = catalog.map { rows -> rows.filter { it.isTrashed } }
    val protectedFolders: Flow<Set<String>> = dao.observeFolders()
        .map { folders -> folders.mapTo(hashSetOf()) { it.path } }.flowOn(Dispatchers.Default)
    val scan: Flow<ScanState> = dao.observeScan().map { it?.state() ?: ScanState() }
    val duplicates: Flow<List<DuplicateGroup>> = combine(media, dao.observeHashes()) { items, hashes ->
        MediaCachePolicy.duplicateGroups(items, hashes.associate { (it.uri to it.fingerprint) to it.sha256 })
    }.flowOn(Dispatchers.Default)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        Coordinator.refreshMutex.withLock {
            // The complete scoped snapshot replaces old visibility, including revoked access.
            val snapshot = reader.load().map(CachedMedia::from)
            dao.replaceAccessible(snapshot)
        }
    }

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
            freshUnchanged(group.items.filter { it.uri !in selectedUris }).any { !it.isTrashed }
        }

    private suspend fun freshUnchanged(items: List<MediaItem>): List<MediaItem> =
        items.distinctBy { it.uri }.mapNotNull { previous ->
            reader.current(previous.uri)?.takeIf { MediaCachePolicy.sameVersion(previous, it) }
        }

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
                if (item != null && !item.isTrashed && item.fingerprint == hash.fingerprint) {
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
}
