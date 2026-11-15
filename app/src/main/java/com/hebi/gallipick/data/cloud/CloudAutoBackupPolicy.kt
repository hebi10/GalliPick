package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaItem
import java.security.MessageDigest
import java.util.Locale

internal const val AUTO_BACKUP_BATCH_SIZE = 2
internal const val AUTO_BACKUP_HISTORY_LIMIT = 512

internal data class CloudAutoBackupFolder(val volume: String, val relativePath: String) {
    fun contains(item: MediaItem): Boolean = volume == item.volume && relativePath == item.relativePath

    companion object {
        fun from(item: MediaItem): CloudAutoBackupFolder? =
            CloudAutoBackupFolder(item.volume, item.relativePath).takeIf { it.isValid }
    }

    val isValid: Boolean get() = volume.isNotBlank() && relativePath.isNotBlank()
}

internal data class CloudAutoBackupConsent(
    val ownerKey: String,
    val lease: String,
    val folder: CloudAutoBackupFolder,
) {
    fun permits(uid: String?, workLease: String): Boolean =
        uid != null && workLease == lease && autoBackupOwnerKey(uid) == ownerKey
}

internal fun autoBackupOwnerKey(uid: String): String = consentDigest("gallipick-auto-backup\u0000$uid")

internal fun cloudAutoBackupVersion(item: MediaItem): String =
    consentDigest("${item.uri}\u0000${item.volumeVersion}\u0000${item.fingerprint}")

private fun consentDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun cloudAutoBackupCandidates(
    folder: CloudAutoBackupFolder,
    items: List<MediaItem>,
    handled: Set<String>,
): List<MediaItem> = items.asSequence()
    .filter { folder.isValid && folder.contains(it) && it.canAutoBackUp() }
    .distinctBy { it.uri }
    .filter { cloudAutoBackupVersion(it) !in handled }
    .sortedWith(compareByDescending<MediaItem> { it.dateAddedMs }.thenByDescending { it.id })
    .take(AUTO_BACKUP_BATCH_SIZE)
    .toList()

internal fun MediaItem.canAutoBackUp(): Boolean = !isTrashed && volumeVersion.isNotBlank() &&
    sizeBytes in 1..CLOUD_BACKUP_MAX_BYTES && CLOUD_MEDIA_MIME_TYPE.matches(mimeType.lowercase(Locale.ROOT))

internal fun matchesCloudBackupOriginal(
    expected: MediaItem,
    current: MediaItem?,
    requiredFolder: CloudAutoBackupFolder? = null,
): Boolean = current != null && !current.isTrashed && !expected.isTrashed && expected.volumeVersion.isNotBlank() &&
    current.fingerprint == expected.fingerprint && current.volumeVersion == expected.volumeVersion &&
    current.volume == expected.volume && current.relativePath == expected.relativePath &&
    current.kind == expected.kind && current.mimeType == expected.mimeType &&
    (requiredFolder == null || requiredFolder.contains(current))
