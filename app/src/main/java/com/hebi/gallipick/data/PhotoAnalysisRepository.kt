package com.hebi.gallipick.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hebi.gallipick.domain.ScanPhase
import com.hebi.gallipick.domain.ScanState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Independent local perceptual-photo analysis. Never touches the SHA-256 duplicate session.
 * Every file write is fenced by the current session token and catalog epoch.
 */
internal class PhotoAnalysisRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = MediaDatabase.get(appContext)
    private val dao = database.mediaDao()
    private val workManager by lazy { WorkManager.getInstance(appContext) }
    val scan: Flow<ScanState> = dao.observePhotoScan().map { it?.state() ?: ScanState() }

    fun start() = control { 
        lock.withLock {
            workManager.cancelUniqueWork(WORK_NAME).result.get()
            dao.clearFailedPhotoHashes()
            val token = UUID.randomUUID().toString()
            dao.setPhotoScanSession(PhotoScanSession(token = token, phase = ScanPhase.QUEUED.name,
                message = "사진 유사도 분석을 준비합니다."))
            enqueue(token, ExistingWorkPolicy.REPLACE)
        }
    }

    fun pause() = control {
        lock.withLock {
            val session = dao.photoScanSession() ?: PhotoScanSession()
            if (session.phase !in listOf(ScanPhase.RUNNING.name, ScanPhase.QUEUED.name)) return@withLock
            dao.setPhotoScanSession(session.copy(token = UUID.randomUUID().toString(),
                phase = ScanPhase.PAUSED.name, currentUri = "", currentAttempts = 0,
                message = "사진 분석을 일시정지했습니다."))
            workManager.cancelUniqueWork(WORK_NAME).result.get()
        }
    }

    fun resume() = control {
        lock.withLock {
            val session = dao.photoScanSession() ?: return@withLock
            if (session.phase != ScanPhase.PAUSED.name && session.phase != ScanPhase.FAILED.name) return@withLock
            workManager.cancelUniqueWork(WORK_NAME).result.get()
            val token = UUID.randomUUID().toString()
            dao.setPhotoScanSession(session.copy(token = token, phase = ScanPhase.QUEUED.name,
                currentUri = "", currentAttempts = 0, message = "이전에 완료한 사진부터 이어서 분석합니다."))
            enqueue(token, ExistingWorkPolicy.REPLACE)
        }
    }

    private fun control(action: suspend () -> Unit) {
        Coordinator.scope.launch {
            try { action() }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) {
                lock.withLock {
                    val previous = dao.photoScanSession() ?: PhotoScanSession()
                    dao.setPhotoScanSession(previous.copy(
                        phase = ScanPhase.FAILED.name,
                        message = "사진 분석 작업을 시작하지 못했습니다. 다시 시도해 주세요.",
                    ))
                }
            }
        }
    }

    private fun enqueue(token: String, policy: ExistingWorkPolicy) {
        val job = OneTimeWorkRequestBuilder<PhotoAnalysisWorker>()
            .setInputData(workDataOf(TOKEN_KEY to token))
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, policy, job).result.get()
    }

    suspend fun beginFile(token: String, uri: String): Boolean = lock.withLock {
        val session = dao.photoScanSession() ?: return@withLock false
        if (session.token != token || session.phase != ScanPhase.RUNNING.name) return@withLock false
        val attempts = if (session.currentUri == uri) session.currentAttempts else 0
        if (attempts >= 3) {
            dao.setPhotoScanSession(session.copy(phase = ScanPhase.PAUSED.name,
                message = "동일한 사진에서 분석이 반복 중단되어 일시정지했습니다."))
            return@withLock false
        }
        dao.setPhotoScanSession(session.copy(currentUri = uri, currentAttempts = attempts + 1))
        true
    }

    suspend fun update(token: String, transform: (PhotoScanSession) -> PhotoScanSession): Boolean =
        lock.withLock {
            val session = dao.photoScanSession() ?: return@withLock false
            if (session.token != token || session.phase == ScanPhase.PAUSED.name) return@withLock false
            dao.setPhotoScanSession(transform(session))
            true
        }

    suspend fun saveIfCurrent(token: String, result: CachedPhotoHash): Boolean = lock.withLock {
        val session = dao.photoScanSession() ?: return@withLock false
        if (session.token != token || session.phase != ScanPhase.RUNNING.name) return@withLock false
        database.withTransaction {
            val current = dao.media(result.uri)
            if (current != null && current.kind == "PHOTO" && !current.isTrashed &&
                current.fingerprint == result.fingerprint && result.volumeVersion.isNotBlank() &&
                dao.volumeVersion(current.volume) == result.volumeVersion
            ) dao.putPhotoHash(result)
        }
        true
    }

    suspend fun continueAnalysis(token: String) = lock.withLock {
        val session = dao.photoScanSession() ?: return@withLock
        if (session.token != token || session.phase != ScanPhase.RUNNING.name) return@withLock
        dao.setPhotoScanSession(session.copy(phase = ScanPhase.QUEUED.name,
            currentUri = "", currentAttempts = 0, message = "분석한 사진을 저장했습니다. 이어서 검사합니다."))
        enqueue(token, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    companion object {
        const val WORK_NAME = "gallipick-perceptual-photo-analysis"
        const val TOKEN_KEY = "photo-session-token"
        private val lock = Mutex()
    }
}
