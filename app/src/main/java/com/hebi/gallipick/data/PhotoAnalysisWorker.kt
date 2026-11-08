package com.hebi.gallipick.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hebi.gallipick.domain.PerceptualHash
import com.hebi.gallipick.domain.PhotoAnalysisPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Separate from SHA-256: reads bounded decoded pixels, never uploads or modifies originals. */
class PhotoAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString(MediaRepository.TOKEN_KEY) ?: return@withContext Result.success()
        val repository = MediaRepository(applicationContext)
        try {
            if (!repository.updatePhotoSession(token) { it.copy(
                    phase = PhotoAnalysisPhase.RUNNING.name,
                    message = "접근 가능한 사진을 분석하고 있습니다."
                ) }) return@withContext Result.success()

            repository.refresh()
            val photos = repository.dao.allMedia()
                .filter { it.kind == "PHOTO" && !it.isTrashed && it.sizeBytes > 0 }
                .sortedWith(compareBy<CachedMedia> { it.dateAddedMs }.thenBy { it.uri })
            val epochs = repository.dao.allVolumeVersions().associate { it.volume to it.version }
            val eligible = photos.filter { epochs[it.volume] != null }
            val cached = repository.dao.allPhotoHashes().associateBy { Triple(it.uri, it.fingerprint, it.volumeVersion) }
            val remaining = eligible.filter { Triple(it.uri, it.fingerprint, epochs[it.volume]) !in cached }
            val completedBefore = eligible.count {
                cached[Triple(it.uri, it.fingerprint, epochs[it.volume])]?.phash != null
            }
            val skippedBefore = eligible.count {
                val found = cached[Triple(it.uri, it.fingerprint, epochs[it.volume])]
                found != null && found.phash == null
            }
            if (!repository.updatePhotoSession(token) {
                    it.copy(total = eligible.size, completed = completedBefore,
                        skipped = skippedBefore, phase = PhotoAnalysisPhase.RUNNING.name)
                }) return@withContext Result.success()

            var completed = completedBefore
            var skipped = skippedBefore
            val started = SystemClock.elapsedRealtime()
            for (photo in remaining) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - started >= 100_000L) {
                    repository.continuePhotoAnalysis(token)
                    return@withContext Result.success()
                }
                val epoch = epochs[photo.volume] ?: continue
                val phash = try {
                    val before = repository.reader.current(photo.uri)
                    if (before == null || before.isTrashed || before.fingerprint != photo.fingerprint ||
                        repository.reader.volumeVersion(photo.volume) != epoch) {
                        throw IOException("The photo changed before decoding.")
                    }
                    val hash = imageHash(Uri.parse(photo.uri))
                    coroutineContext.ensureActive()
                    val after = repository.reader.current(photo.uri)
                    if (after == null || after.isTrashed || after.fingerprint != photo.fingerprint ||
                        repository.reader.volumeVersion(photo.volume) != epoch) {
                        throw IOException("The photo changed during decoding.")
                    }
                    hash
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    null
                }
                if (!repository.savePhotoHashIfCurrent(token, CachedPhotoHash(
                        photo.uri, photo.fingerprint, epoch, phash
                    ))) return@withContext Result.success()
                if (phash == null) skipped++ else completed++
                if (!repository.updatePhotoSession(token) { it.copy(
                        completed = completed, skipped = skipped,
                        message = if (phash == null) "읽을 수 없는 사진은 제외했습니다."
                                  else "사진의 시각적 특징을 저장했습니다."
                    ) }) return@withContext Result.success()
            }
            repository.refresh()
            val latest = repository.dao.allMedia()
                .filter { it.kind == "PHOTO" && !it.isTrashed && it.sizeBytes > 0 }
            val latestVersions = repository.dao.allVolumeVersions().associate { it.volume to it.version }
            val latestCache = repository.dao.allPhotoHashes().associateBy { Triple(it.uri, it.fingerprint, it.volumeVersion) }
            val newCandidates = latest.filter { latestVersions[it.volume] != null &&
                Triple(it.uri, it.fingerprint, latestVersions[it.volume]) !in latestCache }
            if (newCandidates.isNotEmpty()) {
                repository.continuePhotoAnalysis(token)
            } else {
                repository.updatePhotoSession(token) { it.copy(
                    phase = PhotoAnalysisPhase.COMPLETE.name,
                    total = latest.count { latestVersions[it.volume] != null },
                    completed = latest.count {
                        latestCache[Triple(it.uri, it.fingerprint, latestVersions[it.volume])]?.phash != null
                    },
                    skipped = latest.count {
                        val hash = latestCache[Triple(it.uri, it.fingerprint, latestVersions[it.volume])]
                        hash != null && hash.phash == null
                    },
                    message = "사진 특징 분석을 마쳤습니다. 유사 사진 묶음 비교는 다음 단계에서 제공합니다."
                ) }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.updatePhotoSession(token) { it.copy(
                phase = PhotoAnalysisPhase.FAILED.name,
                message = "분석하지 못했습니다. 사진 접근 권한을 확인하고 다시 시작해 주세요."
            ) }
            Result.success()
        }
    }

    private fun imageHash(uri: Uri): String {
        val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(32, 32)
            decoder.isMutableRequired = false
        }
        return try {
            val argb = IntArray(32 * 32)
            bitmap.getPixels(argb, 0, 32, 0, 0, 32, 32)
            val luminance = IntArray(argb.size) { i ->
                val color = argb[i]
                val alpha = color ushr 24
                val red = (color ushr 16) and 255
                val green = (color ushr 8) and 255
                val blue = color and 255
                // Composite transparent pixels onto white; avoid false negatives from hidden RGB.
                val y = (299 * red + 587 * green + 114 * blue) / 1000
                (y * alpha + 255 * (255 - alpha)) / 255
            }
            PerceptualHash.fromLuminance(luminance)
        } finally {
            bitmap.recycle()
        }
    }
}
