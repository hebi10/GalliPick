package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
import com.hebi.gallipick.domain.MediaItem
import java.text.Normalizer
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

internal data class CloudOriginal(
    val uploadId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val verifiedAtMs: Long,
    val sourceFolders: List<String> = emptyList(),
    val capturedAtMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val transferProtocolVersion: Int = 1,
)

internal data class CloudUploadMetadata(
    val sourceFolder: String? = null,
    val capturedAtMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    companion object {
        fun from(item: MediaItem): CloudUploadMetadata = CloudUploadMetadata(
            sourceFolder = normalizeCloudFolderPath(item.relativePath),
            capturedAtMs = item.dateTakenMs.takeIf { it in 1..MAX_SAFE_JSON_INTEGER },
            width = item.width.takeIf { it in 1..100_000 },
            height = item.height.takeIf { it in 1..100_000 },
            durationMs = item.durationMs.takeIf { it in 1..MAX_SAFE_JSON_INTEGER },
        )
    }
}

private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L

/** Metadata only; no folder name ever becomes a Storage object key. */
internal fun normalizeCloudFolderPath(raw: String): String? {
    if (raw.isBlank() || raw.length > 160 || raw.startsWith('/') || '\\' in raw) return null
    val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).removeSuffix("/")
    val parts = normalized.split('/')
    if (parts.size !in 1..8 || parts.any { part ->
            part.isBlank() || part == "." || part == ".." || part.length > 60 ||
                part.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        }) return null
    return parts.joinToString("/")
}

internal data class CloudDownloadTarget(val url: URI, val generation: String)

private val generationId = Regex("[1-9][0-9]{0,19}")
private val signedAt = Regex("[0-9]{8}T[0-9]{6}Z")
private val signatureHex = Regex("[0-9a-fA-F]{128,1024}")
private val downloadParameters = setOf(
    "generation", "X-Goog-Algorithm", "X-Goog-Credential", "X-Goog-Date", "X-Goog-Expires",
    "X-Goog-SignedHeaders", "X-Goog-Signature",
)

internal fun validateBackupDownload(
    downloadUrl: String,
    uploadId: String,
    uid: String,
    generation: String,
    allowedBuckets: Set<String>,
    namespace: CloudBackupNamespace = CloudBackupNamespace.DEVELOPMENT,
    privateVault: Boolean = false,
    resumable: Boolean = false,
): URI? {
    if (!generationId.matches(generation)) return null
    val uri = backupObjectUri(downloadUrl, uid, uploadId, allowedBuckets, namespace, privateVault, resumable) ?: return null
    val pieces = uri.rawQuery.split('&')
    val query = pieces.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    if (pieces.size != downloadParameters.size || query.keys != downloadParameters ||
        query["generation"] != generation || query["X-Goog-Algorithm"] != "GOOG4-RSA-SHA256" ||
        query["X-Goog-Credential"].isNullOrBlank() || query["X-Goog-SignedHeaders"] != "host" ||
        query["X-Goog-Date"]?.let(signedAt::matches) != true ||
        query["X-Goog-Signature"]?.let(signatureHex::matches) != true ||
        query["X-Goog-Expires"]?.toIntOrNull()?.let { it in 1..300 } != true
    ) return null
    return uri
}

internal fun cloudOriginalBasename(value: String): String = value
    .substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    .trim().trim('.').take(120)

internal fun copyAndVerifyCloudOriginal(
    source: InputStream,
    output: OutputStream,
    sizeBytes: Long,
    expectedSha256: String,
    resumable: Boolean = false,
    checkAccount: () -> Unit,
) {
    val limit = if (resumable) CLOUD_RESUMABLE_ORIGINAL_MAX_BYTES else CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES
    if (sizeBytes !in 1..limit) failBackup(CloudBackupResult.INTEGRITY_ERROR)
    val bounded = BackupStagingInput(source, output, sizeBytes, CloudBackupResult.INTEGRITY_ERROR)
    val actual = MediaHasher.sha256(bounded, checkAccount)
    if (actual != expectedSha256) failBackup(CloudBackupResult.INTEGRITY_ERROR)
}
