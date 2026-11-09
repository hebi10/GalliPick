package com.hebi.gallipick.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.hebi.gallipick.domain.OcrPhase
import com.hebi.gallipick.domain.OcrResultStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline, user-triggered photo OCR. Both script models are bundled with the APK;
 * no bitmap, recognized text, or user metadata is sent to an app server.
 */
class PhotoOcrWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString(MediaRepository.TOKEN_KEY)
            ?: return@withContext Result.success()
        val repo = MediaRepository(applicationContext)
        val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            if (!repo.updateOcrSession(token) {
                    it.copy(phase = OcrPhase.RUNNING.name,
                        message = "기기 안에서 한국어·영문 글자를 분석하고 있습니다.")
                }) return@withContext Result.success()

            repo.refresh()
            val photoRows = repo.dao.allMedia()
                .filter { it.kind == "PHOTO" && !it.isTrashed && it.sizeBytes > 0 }
                .sortedWith(compareBy<CachedMedia> { it.dateAddedMs }.thenBy { it.uri })
            val epochs = repo.dao.allVolumeVersions().associate { it.volume to it.version }
            val eligible = photoRows.filter { epochs[it.volume] != null }
            val cache = repo.dao.allOcrIndex()
                .associateBy { Triple(it.uri, it.fingerprint, it.volumeVersion) }
            val remaining = eligible.filter {
                cache[Triple(it.uri, it.fingerprint, epochs[it.volume])] == null
            }
            var completed = eligible.count {
                cache[Triple(it.uri, it.fingerprint, epochs[it.volume])]?.status != null &&
                    cache[Triple(it.uri, it.fingerprint, epochs[it.volume])]?.status != "FAILED"
            }
            var skipped = eligible.count {
                cache[Triple(it.uri, it.fingerprint, epochs[it.volume])]?.status == "FAILED"
            }
            if (!repo.updateOcrSession(token) {
                    it.copy(total = eligible.size, completed = completed, skipped = skipped)
                }) return@withContext Result.success()

            val start = SystemClock.elapsedRealtime()
            for (row in remaining) {
                coroutineContext.ensureActive()
                if (isStopped) return@withContext Result.success()
                if (SystemClock.elapsedRealtime() - start >= 90_000L) {
                    repo.continueOcrIndex(token)
                    return@withContext Result.success()
                }
                val epoch = epochs[row.volume] ?: continue
                val value = try {
                    val before = repo.reader.current(row.uri)
                    if (before == null || before.isTrashed || before.fingerprint != row.fingerprint ||
                        repo.reader.volumeVersion(row.volume) != epoch) {
                        throw IOException("Photo changed before OCR.")
                    }
                    val recognized = recognize(Uri.parse(row.uri), korean, latin)
                    coroutineContext.ensureActive()
                    val after = repo.reader.current(row.uri)
                    if (after == null || after.isTrashed || after.fingerprint != row.fingerprint ||
                        repo.reader.volumeVersion(row.volume) != epoch) {
                        throw IOException("Photo changed during OCR.")
                    }
                    recognized
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val status = when {
                    value == null -> OcrResultStatus.FAILED
                    value.isBlank() -> OcrResultStatus.NO_TEXT
                    else -> OcrResultStatus.SUCCESS
                }
                val saved = repo.saveOcrIndexIfCurrent(token,
                    CachedOcrIndex(row.uri, row.fingerprint, epoch,
                        value.orEmpty().take(20_000), status.name))
                if (!saved) return@withContext Result.success()
                if (status == OcrResultStatus.FAILED) skipped++ else completed++
                if (!repo.updateOcrSession(token) {
                        it.copy(completed = completed, skipped = skipped,
                            message = if (status == OcrResultStatus.FAILED)
                                "읽을 수 없는 사진은 검색 대상에서 제외했습니다."
                            else "사진의 글자를 기기에 저장했습니다.")
                    }) return@withContext Result.success()
            }
            repo.updateOcrSession(token) {
                it.copy(phase = OcrPhase.COMPLETE.name, total = eligible.size,
                    completed = completed, skipped = skipped,
                    message = "접근 가능한 사진의 글자 분석을 마쳤습니다. 제외된 항목은 재시도할 수 있습니다.")
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repo.updateOcrSession(token) {
                it.copy(phase = OcrPhase.FAILED.name,
                    message = "사진 속 글자를 분석하지 못했습니다. 권한을 확인하고 다시 시도해 주세요.")
            }
            Result.success()
        } finally {
            korean.close()
            latin.close()
        }
    }

    private suspend fun recognize(uri: Uri, korean: TextRecognizer, latin: TextRecognizer): String {
        val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            if (width <= 0 || height <= 0) throw IOException("Invalid OCR image dimensions.")
            val longest = maxOf(width, height)
            if (longest > 2048) {
                val scale = 2048.0 / longest
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1))
            }
        }
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val lines = linkedSetOf<String>()
            for (recognizer in listOf(korean, latin)) {
                coroutineContext.ensureActive()
                try {
                    val recognized = suspendCancellableCoroutine<String> { continuation ->
                        recognizer.process(image)
                            .addOnSuccessListener {
                                if (continuation.isActive) continuation.resume(it.text)
                            }
                            .addOnFailureListener {
                                if (continuation.isActive) continuation.resumeWithException(it)
                            }
                    }
                    recognized.lineSequence().map(String::trim)
                        .filter(String::isNotBlank).forEach(lines::add)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (lines.isEmpty() && recognizer === latin) throw IOException("OCR scripts failed.")
                }
            }
            lines.joinToString("\n")
        } finally {
            bitmap.recycle()
        }
    }
}
