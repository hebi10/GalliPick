package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal const val CLOUD_BACKUP_MAX_BYTES = 64L * 1024L * 1024L

internal fun validateBackupUpload(
    uploadUrl: String,
    uploadId: String,
    uid: String,
    sha256: String,
    sizeBytes: Long,
    mimeType: String,
    headers: Map<String, String>,
    allowedBuckets: Set<String>,
): URI? {
    if (uploadId != "$sha256-$sizeBytes") return null
    val expectedHeaders = mapOf(
        "content-type" to mimeType,
        "x-goog-content-length-range" to "$sizeBytes,$sizeBytes",
        "x-goog-if-generation-match" to "0",
    )
    if (headers.size != expectedHeaders.size ||
        headers.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) } != expectedHeaders
    ) return null

    val uri = try {
        URI(uploadUrl)
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme != "https" || uri.rawAuthority != "storage.googleapis.com" ||
        uri.rawFragment != null || uri.rawQuery.isNullOrBlank()
    ) return null

    val accountHash = MediaHasher.sha256(uid.byteInputStream())
    val objectKey = "cloud02/users/$accountHash/$uploadId"
    return uri.takeIf { allowedBuckets.any { bucket -> uri.rawPath == "/$bucket/$objectKey" } }
}
