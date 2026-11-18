package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal const val CLOUD_BACKUP_MAX_BYTES = 64L * 1024L * 1024L
internal const val CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES = 256L * 1024L * 1024L
internal val CLOUD_MEDIA_MIME_TYPE = Regex("(?:image|video)/[a-z0-9][a-z0-9.+-]{0,62}")

internal enum class CloudBackupNamespace(val objectPrefix: String) {
    DEVELOPMENT("cloud02/users"),
    LICENSE_TEST("cloud02/license-test/users"),
    PRODUCTION("cloud02/production/users"),
}

internal fun validateBackupUpload(
    uploadUrl: String,
    uploadId: String,
    uid: String,
    sha256: String,
    sizeBytes: Long,
    mimeType: String,
    headers: Map<String, String>,
    allowedBuckets: Set<String>,
    namespace: CloudBackupNamespace = CloudBackupNamespace.DEVELOPMENT,
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

    return backupObjectUri(uploadUrl, uid, uploadId, allowedBuckets, namespace)
}

internal fun backupObjectUri(
    url: String, uid: String, uploadId: String, allowedBuckets: Set<String>, namespace: CloudBackupNamespace,
): URI? {
    val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
    if (uri.scheme != "https" || uri.rawAuthority != "storage.googleapis.com" ||
        uri.rawFragment != null || uri.rawQuery.isNullOrBlank()
    ) return null
    val accountHash = MediaHasher.sha256(uid.byteInputStream())
    val objectKey = "${namespace.objectPrefix}/$accountHash/$uploadId"
    return uri.takeIf { allowedBuckets.any { bucket -> uri.rawPath == "/$bucket/$objectKey" } }
}
