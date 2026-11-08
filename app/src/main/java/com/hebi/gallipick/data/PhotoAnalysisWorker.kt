package com.hebi.gallipick.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hebi.gallipick.GalliPickApplication
import com.hebi.gallipick.domain.PhotoPerceptualHash
import com.hebi.gallipick.domain.ScanPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Computes bounded thumbnails locally, with an independent resumable per-photo cache. */
class PhotoAnalysisWorker(
    context: android.content.Context, parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString(PhotoAnalysisRepository.TOKEN_KEY)
            ?: return@withContext Result.success()
        val repository = (applicationContext as GalliPickApplication).repository
        val control = repository.photoAnalysis
        val dao = repository.dao
        try {
            if (!control.update(token) { it.copy(phase = ScanPhase.RUNNING.name, message = "사진 파일을 확인합니다.") }) {
                return@withContext Result.success()
            }
            repository.refresh()
            val plan = plan(dao)
            if (!control.update(token) {
                it.copy(completed = plan.completed, skipped = plan.skipped, total = plan.candidates.size,
                    message = "사진의 작은 미리보기를 분석합니다. 파일을 전송하지 않습니다.")
            }) return@withContext Result.success()

            var completed = plan.completed
            var skipped = plan.skipped
            val startedAt = SystemClock.elapsedRealtime()
            for (candidate in plan.remaining) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - startedAt > CHUNK_BUDGET_MS) {
                    control.continueAnalysis(token)
                    return@withContext Result.success()
                }
                if (!control.beginFile(token, candidate.item.uri)) return@withContext Result.success()
                val hash = try {
                    verifyCurrent(repository.reader, candidate)
                    val result = thumbnailHash(candidate.item.uri)
                    verifyCurrent(repository.reader, candidate)
                    result
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
                coroutineContext.ensureActive()
                if (!control.saveIfCurrent(token, CachedPhotoHash(
                    candidate.item.uri, candidate.item.fingerprint, candidate.volumeVersion, hash,
                ))) return@withContext Result.success()
                if (hash == null) skipped++ else completed++
                if (!control.update(token) {
                    it.copy(completed = completed, skipped = skipped, total = plan.candidates.size,
                        currentUri = "", currentAttempts = 0,
                        message = if (hash == null) "읽거나 분석할 수 없는 사진은 제외했습니다."
                            else "분석한 사진을 기기에 저장했습니다.")
                }) return@withContext Result.success()
            }
            repository.refresh()
            val latest = plan(dao)
            if (latest.remaining.isNotEmpty()) {
                control.continueAnalysis(token)
            } else {
                control.update(token) {
                    it.copy(phase = ScanPhase.COMPLETE.name, completed = latest.completed,
                        total = latest.candidates.size, skipped = latest.skipped,
                        currentUri = "", currentAttempts = 0,
                        message = if (latest.skipped > 0)
                            "사진 분석 완료 · 읽지 못한 ${latest.skipped}개 제외"
                        else "사진 분석이 완료됐습니다.")
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            control.update(token) {
                it.copy(phase = ScanPhase.FAILED.name,
                    message = "분석을 마치지 못했습니다. 권한과 파일 상태를 확인하고 재개해 주세요.")
            }
            Result.success()
        }
    }

    private suspend fun verifyCurrent(reader: MediaStoreReader, candidate: Candidate) {
        coroutineContext.ensureActive()
        val current = reader.current(candidate.item.uri)
        if (current == null || current.isTrashed ||
            current.fingerprint != candidate.item.fingerprint ||
            reader.volumeVersion(candidate.item.volume) != candidate.volumeVersion
        ) throw IOException("Photo changed or cannot be accessed")
    }

    private fun thumbnailHash(uri: String): String {
        val pixels = IntArray(PhotoPerceptualHash.SIDE * PhotoPerceptualHash.SIDE)
        val source = ImageDecoder.createSource(applicationContext.contentResolver, Uri.parse(uri))
        val image: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(PhotoPerceptualHash.SIDE, PhotoPerceptualHash.SIDE)
        }
        try {
            image.getPixels(pixels, 0, PhotoPerceptualHash.SIDE, 0, 0,
                PhotoPerceptualHash.SIDE, PhotoPerceptualHash.SIDE)
        } finally {
            image.recycle()
        }
        return PhotoPerceptualHash.fromArgb(pixels)
    }

    private suspend fun plan(dao: MediaDao): ScanPlan {
        val versions = dao.allVolumeVersions().associate { it.volume to it.version }
        val candidates = dao.allMedia().asSequence()
            .filter { it.kind == "PHOTO" && !it.isTrashed && it.sizeBytes > 0L }
            .distinctBy { it.uri }
            .sortedBy { it.uri }
            .mapNotNull { item ->
                versions[item.volume]?.let { version -> Candidate(item, version) }
            }.toList()
        val known = dao.allPhotoHashes().associateBy {
            Triple(it.uri, it.fingerprint, it.volumeVersion)
        }
        fun Candidate.key() = Triple(item.uri, item.fingerprint, volumeVersion)
        return ScanPlan(candidates,
            remaining = candidates.filter { it.key() !in known },
            completed = candidates.count { known[it.key()]?.perceptualHash != null },
            skipped = candidates.count { val entry = known[it.key()]; entry != null && entry.perceptualHash == null },
        )
    }

    private data class Candidate(val item: CachedMedia, val volumeVersion: String)
    private data class ScanPlan(
        val candidates: List<Candidate>,
        val remaining: List<Candidate>,
        val completed: Int,
        val skipped: Int,
    )

    companion object {
        private const val CHUNK_BUDGET_MS = 115_000L
    }
}
