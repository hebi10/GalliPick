package com.hebi.gallipick.data.cloud

import android.app.Activity

internal class CloudAccountConnection(
    val session: CloudAccountSession,
    private val backupTransport: CloudBackup? = null,
    val billing: CloudBilling? = null,
    private val productionBackupAccess: CloudBackupAccessState? = null,
    val secretVault: CloudSecretVault? = null,
    private val getGoogleIdToken: suspend (Activity) -> String,
) {
    val backupAvailability = productionBackupAccess?.observations

    val backup: CloudBackup?
        get() = backupTransport?.takeIf {
            productionBackupAccess == null ||
                session.account.value?.uid?.let(productionBackupAccess::forAccount) != null
        }

    val usesProductionBackupPlan: Boolean get() = productionBackupAccess?.development == false

    suspend fun refreshBackupAccess(uid: String) {
        if (productionBackupAccess == null || session.account.value?.uid != uid) return
        billing?.open(uid)?.use { it.loadPlan() }
    }

    suspend fun chooseAccount(activity: Activity) = session.chooseAccount { getGoogleIdToken(activity) }
}
