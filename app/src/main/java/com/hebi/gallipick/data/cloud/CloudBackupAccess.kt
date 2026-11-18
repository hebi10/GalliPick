package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CloudBackupAccess(
    val namespace: CloudBackupNamespace,
    val maximumOriginalBytes: Long,
    val uploadExpiryMillis: Long?,
    val supportsResumableTransfers: Boolean = false,
    val resumableUploadExpiryMillis: Long? = null,
    val maximumResumableOriginalBytes: Long = 0L,
) {
    fun allowsUpload(nowMillis: Long): Boolean = uploadExpiryMillis?.let { nowMillis < it } == true
    fun allowsResumableUpload(nowMillis: Long): Boolean =
        supportsResumableTransfers && resumableUploadExpiryMillis?.let { nowMillis < it } == true

    fun allowsManualUpload(nowMillis: Long): Boolean = allowsUpload(nowMillis) || allowsResumableUpload(nowMillis)

    fun maximumManualOriginalBytes(nowMillis: Long): Long =
        if (allowsResumableUpload(nowMillis)) maximumResumableOriginalBytes else maximumOriginalBytes

    companion object {
        val development = CloudBackupAccess(CloudBackupNamespace.DEVELOPMENT, CLOUD_BACKUP_MAX_BYTES, Long.MAX_VALUE)
    }
}

internal data class CloudBackupAccessObservation(val uid: String, val access: CloudBackupAccess?)

internal class CloudBackupAccessState(
    val development: Boolean = false,
    private val currentUid: () -> String?,
) {
    private val latest = MutableStateFlow<CloudBackupAccessObservation?>(null)
    val observations = latest.asStateFlow()

    fun observe(uid: String, plan: CloudBillingPlan) {
        if (currentUid() == uid) {
            latest.value = CloudBackupAccessObservation(
                uid,
                if (development) plan.developmentBackupAccess() else plan.productionBackupAccess(),
            )
        }
    }

    fun clearForAccountChange() {
        if (latest.value?.uid != currentUid()) latest.value = null
    }

    fun forAccount(uid: String): CloudBackupAccess? {
        if (currentUid() != uid) return null
        return latest.value?.takeIf { it.uid == uid }?.access
            ?: CloudBackupAccess.development.takeIf { development }
    }
}
