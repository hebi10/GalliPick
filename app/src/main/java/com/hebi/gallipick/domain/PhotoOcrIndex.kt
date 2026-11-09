package com.hebi.gallipick.domain

import java.text.Normalizer
import java.util.Locale

enum class OcrPhase { IDLE, QUEUED, RUNNING, PAUSED, COMPLETE, FAILED }

data class OcrScanState(
    val phase: OcrPhase = OcrPhase.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
)

enum class OcrResultStatus { SUCCESS, NO_TEXT, FAILED }

data class PhotoOcrEntry(
    val uri: String,
    val fingerprint: String,
    val volumeVersion: String,
    val recognizedText: String,
    val status: OcrResultStatus,
)

/** OCR search never grants permission to move/delete a photo. */
object PhotoOcrIndexPolicy {
    fun valid(items: List<MediaItem>, entries: List<PhotoOcrEntry>): Map<String, PhotoOcrEntry> {
        val photos = items.filter { it.kind == MediaKind.PHOTO && !it.isTrashed }
            .associateBy { it.uri }
        return entries.mapNotNull { entry ->
            val photo = photos[entry.uri] ?: return@mapNotNull null
            if (photo.volumeVersion.isBlank() || photo.fingerprint != entry.fingerprint ||
                photo.volumeVersion != entry.volumeVersion) null
            else entry.uri to entry
        }.toMap()
    }

    fun searchable(items: List<MediaItem>, entries: List<PhotoOcrEntry>, query: String): List<MediaItem> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val current = valid(items, entries)
        return items.filter { photo ->
            val entry = current[photo.uri]
            entry?.status == OcrResultStatus.SUCCESS &&
                terms.all { term -> normalized(entry.recognizedText).contains(term) }
        }
    }

    fun snippet(text: String, query: String, limit: Int = 110): String {
        if (limit < 1) return ""
        val plain = text.replace(Regex("\\s+"), " ").trim()
        if (plain.isEmpty()) return ""
        val term = terms(query).firstOrNull() ?: return plain.take(limit)
        val index = normalized(plain).indexOf(term)
        val start = if (index < 0) 0 else (index - 24).coerceIn(0, plain.length)
        val end = (start + limit).coerceAtMost(plain.length)
        return (if (start > 0) "…" else "") +
            plain.substring(start, end) +
            (if (end < plain.length) "…" else "")
    }

    private fun terms(query: String): List<String> =
        normalized(query.take(120)).split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
