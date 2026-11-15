package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
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
)

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
): URI? {
    if (!generationId.matches(generation)) return null
    val uri = backupObjectUri(downloadUrl, uid, uploadId, allowedBuckets) ?: return null
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
    checkAccount: () -> Unit,
) {
    if (sizeBytes !in 1..CLOUD_BACKUP_MAX_BYTES) failBackup(CloudBackupResult.INTEGRITY_ERROR)
    val bounded = BackupStagingInput(source, output, sizeBytes, CloudBackupResult.INTEGRITY_ERROR)
    val actual = MediaHasher.sha256(bounded, checkAccount)
    if (actual != expectedSha256) failBackup(CloudBackupResult.INTEGRITY_ERROR)
}
