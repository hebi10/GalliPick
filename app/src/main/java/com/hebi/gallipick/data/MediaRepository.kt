package com.hebi.gallipick.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.SimilarPhotoGroup
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.ScanPhase
import com.hebi.gallipick.domain.ScanState
import com.hebi.gallipick.domain.PhotoAnalysisPhase
import com.hebi.gallipick.domain.PhotoAnalysisState
import com.hebi.gallipick.domain.TrashIdentity
import com.hebi.gallipick.domain.TrashOutcome
import com.hebi.gallipick.domain.TrashOutcomePolicy
import com.hebi.gallipick.domain.CleanupHistoryEntry
import com.hebi.gallipick.domain.CleanupHistoryPolicy
import com.hebi.gallipick.domain.PhotoReviewDecision
import com.hebi.gallipick.domain.PhotoReviewEntry
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
    internal val photoHashes: Flow<List<CachedPhotoHash>> = dao.observePhotoHashes()
    val photoAnalysis: Flow<PhotoAnalysisState> = dao.observePhotoAnalysis().map { it?.state() ?: PhotoAnalysisState() }
    val reviewEntries: Flow<List<PhotoReviewEntry>> = dao.observeReviewChoices().map { rows ->
        rows.mapNotNull { row ->
            val decision = runCatching { PhotoReviewDecision.valueOf(row.decision) }.getOrNull()
                ?: return@mapNotNull null
            PhotoReviewEntry(
                groupId = row.groupId,
                uri = row.uri,
                fingerprint = row.fingerprint,
                volumeVersion = row.volumeVersion,
                decision = decision,
                updatedAtMs = row.updatedAtMs,
            )
        }
    }
    val cleanupHistory: Flow<List<CleanupHistoryEntry>> =
        dao.observeCleanupHistory().map { rows -> rows.map(CachedCleanupHistory::entry) }

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


    fun startPhotoAnalysis() = launchControl { queuePhotoAnalysis(retryFailed = true) }
    fun resumePhotoAnalysis() = launchControl { queuePhotoAnalysis(retryFailed = false) }

    fun pausePhotoAnalysis() = launchControl {
        Coordinator.controlMutex.withLock {
            val session = dao.photoAnalysisSession() ?: PhotoAnalysisSession()
            dao.setPhotoAnalysisSession(session.copy(
                token = UUID.randomUUID().toString(), phase = PhotoAnalysisPhase.PAUSED.name,
                message = "사진 분석을 일시정지했습니다."
            ))
            workManager.cancelUniqueWork(PHOTO_WORK_NAME).result.get()
        }
    }

    private suspend fun queuePhotoAnalysis(retryFailed: Boolean) {
        Coordinator.controlMutex.withLock {
            workManager.cancelUniqueWork(PHOTO_WORK_NAME).result.get()
            if (retryFailed) {
                // A read failure is never considered a valid photo hash; only explicit retry clears it.
                dao.clearFailedPhotoHashes()
            }
            val token = UUID.randomUUID().toString()
            dao.setPhotoAnalysisSession(PhotoAnalysisSession(token = token, phase = PhotoAnalysisPhase.QUEUED.name,
                message = "접근 가능한 사진을 확인하고 있습니다."))
            enqueuePhotoAnalysis(token, ExistingWorkPolicy.REPLACE)
        }
    }

    internal fun enqueuePhotoAnalysis(token: String, policy: ExistingWorkPolicy) {
        workManager.enqueueUniqueWork(
            PHOTO_WORK_NAME, policy,
            OneTimeWorkRequestBuilder<PhotoAnalysisWorker>()
                .setInputData(workDataOf(TOKEN_KEY to token))
                .addTag(PHOTO_WORK_NAME).build()
        ).result.get()
    }

    internal suspend fun updatePhotoSession(token: String, update: (PhotoAnalysisSession) -> PhotoAnalysisSession): Boolean =
        Coordinator.controlMutex.withLock {
            val session = dao.photoAnalysisSession() ?: return@withLock false
            if (session.token != token || session.phase == PhotoAnalysisPhase.PAUSED.name) return@withLock false
            dao.setPhotoAnalysisSession(update(session))
            true
        }

    internal suspend fun savePhotoHashIfCurrent(token: String, hash: CachedPhotoHash): Boolean =
        Coordinator.controlMutex.withLock {
            if (dao.photoAnalysisSession()?.token != token) return@withLock false
            database.withTransaction {
                val item = dao.media(hash.uri)
                if (item == null || item.isTrashed || item.kind != com.hebi.gallipick.domain.MediaKind.PHOTO.name ||
                    item.fingerprint != hash.fingerprint || hash.volumeVersion.isBlank() ||
                    dao.volumeVersion(item.volume) != hash.volumeVersion) return@withTransaction false
                dao.putPhotoHash(hash)
                true
            }
        }

    internal suspend fun continuePhotoAnalysis(token: String) {
        Coordinator.controlMutex.withLock {
            val session = dao.photoAnalysisSession() ?: return@withLock
            if (session.token != token || session.phase != PhotoAnalysisPhase.RUNNING.name) return@withLock
            dao.setPhotoAnalysisSession(session.copy(phase = PhotoAnalysisPhase.QUEUED.name,
                message = "완료한 사진부터 이어서 분석합니다."))
            enqueuePhotoAnalysis(token, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    suspend fun saveReviewDecision(entry: PhotoReviewEntry) = withContext(Dispatchers.IO) {
        dao.putReviewChoice(CachedReviewChoice(
            entry.groupId, entry.uri, entry.fingerprint, entry.volumeVersion,
            entry.decision.name, entry.updatedAtMs,
        ))
    }

    suspend fun clearReviewDecisions() = withContext(Dispatchers.IO) {
        dao.clearReviewChoices()
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
            val photoSession = dao.photoAnalysisSession() ?: PhotoAnalysisSession()
            dao.setPhotoAnalysisSession(photoSession.copy(
                token = UUID.randomUUID().toString(), phase = PhotoAnalysisPhase.IDLE.name,
                completed = 0, total = 0, skipped = 0, message = "사진 분석 캐시를 비웠습니다."
            ))
            workManager.cancelUniqueWork(PHOTO_WORK_NAME).result.get()
            dao.clearPhotoHashes()
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
        confirmedSurvivingCopy(group, selectedUris) != null

    suspend fun confirmedSurvivingCopy(group: DuplicateGroup, selectedUris: Set<String>, preferredUri: String? = null): MediaItem? =
        withContext(Dispatchers.IO) {
            for (item in group.items.distinctBy { it.uri }.sortedBy { if (it.uri == preferredUri) 0 else 1 }) {
                if (item.uri in selectedUris || item.volumeVersion.isBlank()) continue
                val proof = dao.hash(item.uri, item.fingerprint) ?: continue
                if (proof.sha256 != group.hash || proof.volumeVersion != item.volumeVersion) continue
                freshUnchanged(listOf(item)).firstOrNull { !it.isTrashed }?.let { return@withContext it }
            }
            null
        }

    /** Fail closed unless at least one unchanged, cached, accessible photo remains in the reviewed group. */
    suspend fun confirmedSimilarSurvivor(group: SimilarPhotoGroup, selectedUris: Set<String>): MediaItem? =
        withContext(Dispatchers.IO) {
            if (group.photos.size < 2 || !group.photos.any { it.item.uri in selectedUris }) return@withContext null
            // Check that every selected item still has the specific cached photo signature that formed this group.
            for (photo in group.photos.filter { it.item.uri in selectedUris }) {
                val item = dao.media(photo.item.uri) ?: return@withContext null
                val saved = dao.allPhotoHashes().firstOrNull {
                    it.uri == photo.item.uri && it.fingerprint == photo.item.fingerprint &&
                        it.volumeVersion == photo.item.volumeVersion && it.phash == photo.phash
                } ?: return@withContext null
                if (saved.phash == null || item.fingerprint != photo.item.fingerprint ||
                    dao.volumeVersion(item.volume) != photo.item.volumeVersion) return@withContext null
            }
            val hashes = dao.allPhotoHashes().associateBy { Triple(it.uri, it.fingerprint, it.volumeVersion) }
            for (photo in group.photos.filterNot { it.item.uri in selectedUris }) {
                val item = photo.item
                if (item.volumeVersion.isBlank()) continue
                val proof = hashes[Triple(item.uri, item.fingerprint, item.volumeVersion)] ?: continue
                if (proof.phash != photo.phash) continue
                val current = freshUnchanged(listOf(item)).firstOrNull { !it.isTrashed }
                if (current != null) return@withContext current
            }
            null
        }

    /**
     * The caller must supply a MediaStore-verified outcome after Android approval.
     * A unique request ID guarantees callback replay cannot duplicate a record.
     */
    suspend fun recordConfirmedCleanup(
        operationId: String, restore: Boolean, requestedCount: Int, outcome: TrashOutcome
    ): Boolean = withContext(Dispatchers.IO) {
        val event = CleanupHistoryPolicy.confirmed(
            operationId, System.currentTimeMillis(), requestedCount, restore, outcome
        ) ?: return@withContext false
        dao.insertCleanupHistory(CachedCleanupHistory.from(event))
        true
    }

    suspend fun deleteCleanupHistory(id: String) = withContext(Dispatchers.IO) {
        dao.deleteCleanupHistory(id)
        Unit
    }

    suspend fun clearCleanupHistory() = withContext(Dispatchers.IO) {
        dao.clearCleanupHistory()
        Unit
    }

    suspend fun inspectTrashOutcome(requested: List<TrashIdentity>, restore: Boolean): TrashOutcome = withContext(Dispatchers.IO) {
        val current = requested.distinctBy { it.uri }.associate { it.uri to reader.current(it.uri) }
        TrashOutcomePolicy.classify(requested, current, restore)
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
        internal const val PHOTO_WORK_NAME = "gallipick-photo-perceptual-analysis"
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
