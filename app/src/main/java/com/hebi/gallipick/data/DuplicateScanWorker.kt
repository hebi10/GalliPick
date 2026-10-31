package com.hebi.gallipick.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hebi.gallipick.domain.MediaHasher
import com.hebi.gallipick.domain.ScanPhase
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Bounded standard jobs, with a durable checkpoint after every fully read file. */
class DuplicateScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString(MediaRepository.TOKEN_KEY) ?: return@withContext Result.success()
        val repository = MediaRepository(applicationContext)
        try {
            if (!repository.updateSession(token) {
                    it.copy(phase = ScanPhase.RUNNING.name, message = "접근 가능한 파일을 확인합니다.")
                }) return@withContext Result.success()
            repository.refresh()
            val plan = plan(repository)
            if (!repository.updateSession(token) {
                    it.copy(phase = ScanPhase.RUNNING.name, completed = plan.completed,
                        total = plan.candidates.size, skipped = plan.skipped,
                        message = "파일 전체를 읽어 SHA-256으로 비교합니다.")
                }) return@withContext Result.success()

            var completed = plan.completed
            var skipped = plan.skipped
            val startedAt = SystemClock.elapsedRealtime()
            for (item in plan.remaining) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - startedAt >= CHUNK_BUDGET_MS) {
                    repository.refresh()
                    repository.continueScan(token)
                    return@withContext Result.success()
                }
                if (!repository.beginFile(token, item.uri)) return@withContext Result.success()
                val hash = try {
                    val before = repository.reader.current(item.uri)
                    if (before == null || before.isTrashed || before.fingerprint != item.fingerprint) {
                        throw IOException("미디어가 변경되었거나 접근할 수 없습니다.")
                    }
                    val context = coroutineContext
                    // Exact duplicates require the original bytes, including location metadata.
                    // Scoped-storage redaction can otherwise make distinct originals hash equal.
                    val originalUri = MediaStore.setRequireOriginal(Uri.parse(item.uri))
                    val digest = applicationContext.contentResolver.openInputStream(originalUri)
                        ?.let { stream ->
                            CountingInputStream(stream).use { counted ->
                                val value = MediaHasher.sha256(counted) {
                                    context.ensureActive()
                                    if (isStopped) throw CancellationException("분석이 중단되었습니다.")
                                }
                                if (counted.count != item.sizeBytes) {
                                    throw IOException("파일 크기가 분석 중 변경되었습니다.")
                                }
                                value
                            }
                        } ?: throw IOException("파일을 열 수 없습니다.")
                    val after = repository.reader.current(item.uri)
                    if (after == null || after.isTrashed || after.fingerprint != item.fingerprint) {
                        throw IOException("미디어가 분석 중 변경되었습니다.")
                    }
                    digest
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Persist failed reads too, so a denied/corrupt file cannot loop forever.
                    null
                }
                coroutineContext.ensureActive()
                repository.saveHashIfCurrent(token, CachedHash(item.uri, item.fingerprint, hash))
                if (hash == null) skipped++ else completed++
                if (!repository.updateSession(token) {
                        it.copy(completed = completed, skipped = skipped,
                            currentUri = "", currentAttempts = 0,
                            message = if (hash == null) "원본을 읽을 수 없는 파일은 건너뛰었습니다." else "완료한 파일을 저장했습니다.")
                    }) return@withContext Result.success()
            }

            // A permission or content change during scanning must also reach the UI cache.
            repository.refresh()
            val latest = plan(repository)
            if (latest.remaining.isNotEmpty()) {
                repository.continueScan(token)
            } else {
                repository.updateSession(token) {
                    it.copy(phase = ScanPhase.COMPLETE.name, completed = latest.completed,
                        total = latest.candidates.size, skipped = latest.skipped,
                        currentUri = "", currentAttempts = 0,
                        message = if (latest.skipped > 0) "분석을 마쳤습니다. 원본을 읽을 수 없는 ${latest.skipped}개는 제외했습니다."
                        else "분석을 마쳤습니다. 완전히 일치하는 파일만 표시합니다.")
                }
            }
            Result.success()
        } catch (exception: CancellationException) {
            // WorkManager can resume a stopped job. The unfinished file has no cached hash.
            throw exception
        } catch (_: Exception) {
            repository.updateSession(token) {
                it.copy(phase = ScanPhase.FAILED.name,
                    message = "분석을 마치지 못했습니다. 권한을 확인한 뒤 다시 시작해 주세요.")
            }
            Result.success()
        }
    }

    private suspend fun plan(repository: MediaRepository): ScanPlan {
        val candidates = repository.dao.allMedia().asSequence()
            .filter { !it.isTrashed && it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }.values.filter { it.size > 1 }.flatten()
            .sortedWith(compareBy<CachedMedia> { it.sizeBytes }.thenBy { it.uri })
        val known = repository.dao.allHashes().associateBy { it.uri to it.fingerprint }
        return ScanPlan(
            candidates = candidates,
            remaining = candidates.filter { (it.uri to it.fingerprint) !in known },
            completed = candidates.count { known[it.uri to it.fingerprint]?.sha256 != null },
            skipped = candidates.count {
                val value = known[it.uri to it.fingerprint]
                value != null && value.sha256 == null
            },
        )
    }

    private data class ScanPlan(
        val candidates: List<CachedMedia>,
        val remaining: List<CachedMedia>,
        val completed: Int,
        val skipped: Int,
    )

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            `in`.read(buffer, offset, length).also { if (it > 0) count += it }
    }

    private companion object {
        const val CHUNK_BUDGET_MS = 115_000L
    }
}
