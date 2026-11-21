package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class CloudBackupAccess(
    val namespace: CloudBackupNamespace,
    val maximumOriginalBytes: Long,
    val uploadExpiryMillis: Long?,
    val supportsResumableTransfers: Boolean = false,
    val resumableUploadExpiryMillis: Long? = null,
    val maximumResumableOriginalBytes: Long = 0L,
    val lastKnownRemainingBytes: Long? = null,
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

internal data class CloudBackupAccessObservation(
    val uid: String,
    val access: CloudBackupAccess?,
    val mayReadCatalog: Boolean?,
    val checkFailure: Exception? = null,
)

internal class CloudBackupAccessState(
    val development: Boolean = false,
    private val currentUid: () -> String?,
) {
    private val latest = MutableStateFlow<CloudBackupAccessObservation?>(null)
    val observations = latest.asStateFlow()

    fun observe(uid: String, plan: CloudBillingPlan) {
        if (currentUid() == uid) {
            val access = if (development) plan.developmentBackupAccess() else plan.productionBackupAccess()
            latest.value = CloudBackupAccessObservation(
                uid, access, mayReadCatalog = access != null && plan.cloudReadAllowed != false,
            )
        }
    }

    fun observeReadCheckFailure(uid: String, error: Exception) {
        latest.update { current ->
            if (currentUid() != uid) return@update current
            val previous = current?.takeIf { it.uid == uid }
            if (previous?.mayReadCatalog == false) return@update previous
            val invalidatedAccess = (error as? CloudBackupException)?.let {
                it.permissionDenied || it.result == CloudBackupResult.TEST_ACCESS_REQUIRED
            } == true
            val access = previous?.access ?: CloudBackupAccess.development.takeIf { development && previous == null }
            CloudBackupAccessObservation(uid, access.takeUnless { invalidatedAccess },
                previous?.mayReadCatalog, checkFailure = error)
        }
    }

    fun clearForAccountChange() {
        if (latest.value?.uid != currentUid()) latest.value = null
    }

    fun forAccount(uid: String): CloudBackupAccess? {
        if (currentUid() != uid) return null
        val observation = latest.value?.takeIf { it.uid == uid }
        return if (observation != null) observation.access else CloudBackupAccess.development.takeIf { development }
    }
}
