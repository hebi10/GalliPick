package com.hebi.gallipick.data.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CloudBackupAccess(
    val namespace: CloudBackupNamespace,
    val maximumOriginalBytes: Long,
    val uploadExpiryMillis: Long?,
) {
    fun allowsUpload(nowMillis: Long): Boolean = uploadExpiryMillis?.let { nowMillis < it } == true

    companion object {
        val development = CloudBackupAccess(CloudBackupNamespace.DEVELOPMENT, CLOUD_BACKUP_MAX_BYTES, Long.MAX_VALUE)
    }
}

internal data class CloudBackupAccessObservation(val uid: String, val access: CloudBackupAccess?)

internal class CloudBackupAccessState(private val currentUid: () -> String?) {
    private val latest = MutableStateFlow<CloudBackupAccessObservation?>(null)
    val observations = latest.asStateFlow()

    fun observe(uid: String, plan: CloudBillingPlan) {
        if (currentUid() == uid) latest.value = CloudBackupAccessObservation(uid, plan.productionBackupAccess())
    }

    fun clearForAccountChange() {
        if (latest.value?.uid != currentUid()) latest.value = null
    }

    fun forAccount(uid: String): CloudBackupAccess? =
        latest.value?.takeIf { it.uid == uid && currentUid() == uid }?.access
}
