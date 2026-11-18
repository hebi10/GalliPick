package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaHasher
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal const val CLOUD_BACKUP_MAX_BYTES = 64L * 1024L * 1024L
internal const val CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES = 256L * 1024L * 1024L
internal const val CLOUD_RESUMABLE_ORIGINAL_MAX_BYTES = 100_000_000_000L
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
    privateVault: Boolean = false,
): URI? {
    if (uploadId != "$sha256-$sizeBytes") return null
    if (!validBackupUploadHeaders(headers, mimeType, sizeBytes)) return null
    return backupObjectUri(uploadUrl, uid, uploadId, allowedBuckets, namespace, privateVault)
}

internal fun validBackupUploadHeaders(headers: Map<String, String>, mimeType: String, sizeBytes: Long): Boolean {
    val expectedHeaders = mapOf(
        "content-type" to mimeType,
        "x-goog-content-length-range" to "$sizeBytes,$sizeBytes",
        "x-goog-if-generation-match" to "0",
    )
    return headers.size == expectedHeaders.size &&
        headers.mapKeys { (name, _) -> name.lowercase(Locale.ROOT) } == expectedHeaders
}

internal fun backupObjectUri(
    url: String, uid: String, uploadId: String, allowedBuckets: Set<String>, namespace: CloudBackupNamespace,
    privateVault: Boolean = false,
    resumable: Boolean = false,
): URI? {
    val root = backupOwnerObjectPrefix(uid, namespace, privateVault)
    val objectKey = if (resumable) "$root/v2/files/$uploadId" else "$root/$uploadId"
    return backupStorageUri(url, objectKey, allowedBuckets)
}

internal fun backupOwnerObjectPrefix(uid: String, namespace: CloudBackupNamespace, privateVault: Boolean): String {
    val accountHash = MediaHasher.sha256(uid.byteInputStream())
    val folder = if (privateVault) "$accountHash/secret" else accountHash
    return "${namespace.objectPrefix}/$folder"
}

internal fun backupStorageUri(url: String, objectKey: String, allowedBuckets: Set<String>): URI? {
    val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
    if (uri.scheme != "https" || uri.rawAuthority != "storage.googleapis.com" ||
        uri.rawFragment != null || uri.rawQuery.isNullOrBlank()
    ) return null
    return uri.takeIf { allowedBuckets.any { bucket -> uri.rawPath == "/$bucket/$objectKey" } }
}
