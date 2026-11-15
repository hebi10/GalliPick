package com.hebi.gallipick.domain

import java.util.Locale

internal enum class CloudSpaceTarget(val label: String, val bytes: Long?) {
    FIVE_GB("5 GB", 5_000_000_000L),
    TEN_GB("10 GB", 10_000_000_000L),
    TWENTY_GB("20 GB", 20_000_000_000L),
    MAXIMUM("최대한 확보", null),
}

internal data class CloudSpaceMatchedFiles(
    val files: List<MediaItem>, val scannedCount: Int, val matchingSizeCount: Int,
)

internal data class CloudSpaceCandidate(val file: MediaItem, val verifiedBackup: Boolean)

internal data class CloudSpaceRecommendations(
    val candidates: List<CloudSpaceCandidate>,
    val potentialBytes: Long,
    val verifiedBackupBytes: Long,
)

internal object CloudSpacePolicy {
    private const val MAX_SCAN_FILES = 24
    private const val MAX_SCAN_BYTES = 256L * 1024L * 1024L
    private const val OLD_MEDIA_AGE_MS = 90L * 24L * 60L * 60L * 1000L
    private val photoExtensions = setOf("jpg", "jpeg", "heic", "heif", "png", "webp", "gif")
    private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "3gp", "3gpp")

    fun eligible(items: List<MediaItem>, protectedFolders: Set<String>, keepUris: Set<String>): List<MediaItem> {
        val snapshots = items.groupBy { it.uri }
        val sharedNames = items.distinctBy { it.uri }.groupingBy { item ->
            Triple(item.volume, item.relativePath.trim('/').lowercase(Locale.ROOT),
                item.displayName.substringBeforeLast('.').lowercase(Locale.ROOT))
        }.eachCount()
        return snapshots.values.mapNotNull { versions ->
            val item = versions.first()
            if (!SimilarPhotoReviewPolicy.allowed(item, protectedFolders) || item.uri in keepUris ||
                item.sizeBytes <= 0 || item.volumeVersion.isBlank() || item.generationModified <= 0 ||
                versions.any { !sameOriginal(item, it) }
            ) return@mapNotNull null
            val extension = item.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val approvedFormat = when (item.kind) {
                MediaKind.PHOTO -> item.mimeType.startsWith("image/") && extension in photoExtensions
                MediaKind.VIDEO -> item.mimeType.startsWith("video/") && extension in videoExtensions
            }
            val stem = Triple(item.volume, item.relativePath.trim('/').lowercase(Locale.ROOT),
                item.displayName.substringBeforeLast('.').lowercase(Locale.ROOT))
            item.takeIf { approvedFormat && sharedNames[stem] == 1 }
        }
    }

    fun scanCandidates(
        eligible: List<MediaItem>, cloudSizes: Set<Long>, nowMs: Long,
        maxBytes: Long = MAX_SCAN_BYTES, maxFiles: Int = MAX_SCAN_FILES,
    ): List<MediaItem> {
        val oldVideoThreshold = nowMs - OLD_MEDIA_AGE_MS
        val candidates = eligible.asSequence().filter { it.sizeBytes > 0 && it.sizeBytes in cloudSizes }
            .sortedWith(compareByDescending<MediaItem> {
                it.kind == MediaKind.VIDEO && it.capturedAt in 1..oldVideoThreshold
            }.thenByDescending { it.sizeBytes }.thenBy { it.uri })
        return buildList {
            var remainingBytes = maxBytes
            for (file in candidates) {
                if (size >= maxFiles || remainingBytes <= 0) break
                if (file.sizeBytes > remainingBytes) continue
                add(file)
                remainingBytes -= file.sizeBytes
            }
        }
    }

    fun recommend(
        eligible: List<MediaItem>,
        verifiedMatches: List<MediaItem>,
        target: CloudSpaceTarget,
        nowMs: Long,
    ): CloudSpaceRecommendations {
        val verifiedByUri = verifiedMatches.associateBy { it.uri }
        val oldMediaThreshold = nowMs - OLD_MEDIA_AGE_MS
        val ordered = eligible.asSequence().filter { it.sizeBytes > 0 }.distinctBy { it.uri }
            .mapNotNull { file ->
                val verified = verifiedByUri[file.uri]?.let { sameOriginal(it, file) } == true
                if (verified || file.capturedAt in 1..oldMediaThreshold) CloudSpaceCandidate(file, verified) else null
            }.sortedWith(compareByDescending<CloudSpaceCandidate> { it.verifiedBackup }
                .thenByDescending { it.file.kind == MediaKind.VIDEO }
                .thenByDescending { it.file.sizeBytes }
                .thenBy { it.file.capturedAt }
                .thenBy { it.file.uri })
        var potentialBytes = 0L
        var verifiedBackupBytes = 0L
        val files = buildList {
            for (candidate in ordered) {
                if (target.bytes?.let { potentialBytes >= it } == true) break
                val bytes = minOf(candidate.file.sizeBytes, Long.MAX_VALUE - potentialBytes)
                add(candidate)
                potentialBytes += bytes
                if (candidate.verifiedBackup) verifiedBackupBytes += bytes
            }
        }
        return CloudSpaceRecommendations(files, potentialBytes, verifiedBackupBytes)
    }

    fun sameOriginal(expected: MediaItem, live: MediaItem): Boolean =
        expected.uri == live.uri && expected.id == live.id && expected.volume == live.volume &&
            expected.volumeVersion.isNotBlank() && expected.volumeVersion == live.volumeVersion &&
            expected.fingerprint == live.fingerprint && expected.kind == live.kind && expected.mimeType == live.mimeType &&
            expected.displayName == live.displayName && expected.relativePath == live.relativePath &&
            !live.isFavorite && !live.isProtected && !live.isTrashed
}
