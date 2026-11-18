package com.hebi.gallipick.data.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class CloudAutoBackupStatus {
    WAITING, VERIFIED, NO_NEW_FILES, PERMISSION_REQUIRED, WIFI_AND_CHARGING_REQUIRED,
    CLOUD_UNAVAILABLE, LIMIT_REACHED, RETRY_SCHEDULED, ERROR,
}

internal data class CloudAutoBackupSchedule(
    val consent: CloudAutoBackupConsent,
    val verified: Set<String>,
    val skipped: Set<String>,
    val status: CloudAutoBackupStatus,
)

internal class CloudAutoBackupSettings(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.getSharedPreferences("cloud-auto-backup", Context.MODE_PRIVATE)

    fun current(): CloudAutoBackupSchedule? = synchronized(lock) {
        val owner = store.getString("owner", null) ?: return null
        val lease = store.getString(LEASE, null) ?: return null
        val volume = store.getString("volume", null) ?: return null
        val path = store.getString("folder", null) ?: return null
        val folder = CloudAutoBackupFolder(volume, path).takeIf { it.isValid } ?: return null
        CloudAutoBackupSchedule(
            CloudAutoBackupConsent(owner, lease, folder),
            store.getStringSet("verified", emptySet()).orEmpty().toSet(),
            store.getStringSet("skipped", emptySet()).orEmpty().toSet(),
            CloudAutoBackupStatus.entries.firstOrNull { it.name == store.getString("status", null) }
                ?: CloudAutoBackupStatus.WAITING,
        )
    }

    fun observe(uid: String, namespace: CloudBackupNamespace): Flow<CloudAutoBackupSchedule?> = callbackFlow {
        val owner = autoBackupOwnerKey(uid, namespace)
        fun publish() {
            trySend(current()?.takeIf { it.consent.ownerKey == owner })
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> publish() }
        store.registerOnSharedPreferenceChangeListener(listener)
        publish()
        awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    suspend fun enable(
        uid: String,
        folder: CloudAutoBackupFolder,
        namespace: CloudBackupNamespace,
        isStillAllowed: () -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        editMutex.withLock {
            if (!folder.isValid || !isStillAllowed()) return@withLock false
            val consent = CloudAutoBackupConsent(autoBackupOwnerKey(uid, namespace), UUID.randomUUID().toString(), folder)
            val work = PeriodicWorkRequestBuilder<CloudAutoBackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true).build())
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .setInputData(workDataOf(LEASE to consent.lease))
                .build()
            ensureActive()
            // Finish recording consent once WorkManager begins replacing the old schedule.
            withContext(NonCancellable) {
                try {
                    val workManager = WorkManager.getInstance(appContext)
                    workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, work)
                        .result.get()
                    val saved = synchronized(lock) {
                        store.edit().clear().putString("owner", consent.ownerKey).putString(LEASE, consent.lease)
                            .putString("volume", folder.volume).putString("folder", folder.relativePath).commit()
                    }
                    if (!saved) {
                        clear(consent)
                        workManager.cancelUniqueWork(WORK_NAME)
                    }
                    saved
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    suspend fun disable(uid: String, namespace: CloudBackupNamespace): Boolean = withContext(Dispatchers.IO) {
        editMutex.withLock {
            val consent = current()?.consent?.takeIf { it.ownerKey == autoBackupOwnerKey(uid, namespace) }
                ?: return@withLock true
            val cleared = clear(consent)
            // Consent removal stops a running job even if a WorkManager cancellation cannot be written.
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
            cleared
        }
    }

    private fun clear(consent: CloudAutoBackupConsent): Boolean = synchronized(lock) {
        if (current()?.consent != consent) true else store.edit().clear().commit()
    }

    fun matches(consent: CloudAutoBackupConsent): Boolean = synchronized(lock) {
        consent.lease == store.getString(LEASE, null) && consent.ownerKey == store.getString("owner", null)
    }

    fun status(consent: CloudAutoBackupConsent, status: CloudAutoBackupStatus) = synchronized(lock) {
        if (matches(consent)) store.edit().putString("status", status.name).apply()
    }

    fun record(consent: CloudAutoBackupConsent, version: String, verified: Boolean): Boolean = synchronized(lock) {
        val state = current()?.takeIf { it.consent == consent } ?: return false
        if (state.verified.size + state.skipped.size >= AUTO_BACKUP_HISTORY_LIMIT) return false
        val (key, versions) = if (verified) "verified" to state.verified else "skipped" to state.skipped
        store.edit().putStringSet(key, versions + version).commit()
    }

    companion object {
        const val LEASE = "cloud-auto-backup-lease"
        private const val WORK_NAME = "gallipick-cloud-auto-backup"
        private val lock = Any()
        private val editMutex = Mutex()
    }
}
