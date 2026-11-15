package com.hebi.gallipick.data.cloud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hebi.gallipick.GalliPickApplication
import com.hebi.gallipick.data.MediaStoreReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class CloudAutoBackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val app = applicationContext as? GalliPickApplication ?: return@coroutineScope Result.success()
        val connection = app.cloudAccount ?: return@coroutineScope Result.success()
        val backup = connection.backup ?: return@coroutineScope Result.success()
        val expectedUid = connection.session.account.value?.uid ?: return@coroutineScope Result.success()
        val settings = CloudAutoBackupSettings(app)
        val schedule = settings.current() ?: return@coroutineScope Result.success()
        val consent = schedule.consent
        val lease = inputData.getString(CloudAutoBackupSettings.LEASE) ?: return@coroutineScope Result.success()
        if (!consent.permits(expectedUid, lease)) return@coroutineScope Result.success()
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val wifiNetwork = connectivity?.activeNetwork

        fun requireTransferNetwork(): Network {
            if (!settings.matches(consent) || connection.session.account.value?.uid != expectedUid) {
                throw CancellationException()
            }
            if (!hasFullAutoBackupPermission(app)) throw BackupPaused(CloudAutoBackupStatus.PERMISSION_REQUIRED)
            if (wifiNetwork == null || connectivity?.isValidatedUnmeteredWifi(wifiNetwork) != true ||
                app.getSystemService(BatteryManager::class.java)?.isCharging != true
            ) {
                throw BackupPaused(CloudAutoBackupStatus.WIFI_AND_CHARGING_REQUIRED)
            }
            return wifiNetwork
        }

        val work = async {
            requireTransferNetwork()
            runScheduledBackup(settings, schedule, backup, expectedUid, ::requireTransferNetwork)
        }
        val guard = launch {
            while (work.isActive) {
                try {
                    requireTransferNetwork()
                } catch (error: CancellationException) {
                    work.cancel(error)
                    return@launch
                }
                delay(500)
            }
        }
        try {
            work.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (error is BackupPaused) {
                settings.status(consent, error.status)
                if (error.status == CloudAutoBackupStatus.WIFI_AND_CHARGING_REQUIRED && runAttemptCount < 2) {
                    Result.retry()
                } else Result.success()
            } else Result.success()
        } finally {
            guard.cancel()
        }
    }

    private suspend fun runScheduledBackup(
        settings: CloudAutoBackupSettings,
        schedule: CloudAutoBackupSchedule,
        backup: CloudBackup,
        uid: String,
        requireTransferNetwork: () -> Network,
    ): Result {
        val consent = schedule.consent
        val handled = schedule.verified + schedule.skipped
        val availableSlots = AUTO_BACKUP_HISTORY_LIMIT - handled.size
        if (availableSlots <= 0) return finish(settings, consent, CloudAutoBackupStatus.LIMIT_REACHED)

        val reader = MediaStoreReader(applicationContext)
        val items = try {
            reader.load().items
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return finish(settings, consent, CloudAutoBackupStatus.ERROR)
        }
        val candidates = cloudAutoBackupCandidates(consent.folder, items, handled).take(availableSlots)
        if (candidates.isEmpty()) return finish(settings, consent, CloudAutoBackupStatus.NO_NEW_FILES)
        requireTransferNetwork()

        // Probe the allowlisted development backend before reading any media bytes.
        try {
            backup.listOriginals(uid)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudBackupException) {
            return when (error.result) {
                CloudBackupResult.ACCOUNT_CHANGED -> Result.success()
                CloudBackupResult.NETWORK_ERROR -> retryOrStop(settings, consent)
                CloudBackupResult.QUOTA_REACHED -> finish(settings, consent, CloudAutoBackupStatus.LIMIT_REACHED)
                else -> finish(settings, consent, CloudAutoBackupStatus.CLOUD_UNAVAILABLE)
            }
        } catch (_: Exception) {
            return finish(settings, consent, CloudAutoBackupStatus.CLOUD_UNAVAILABLE)
        }

        var verifiedAny = false
        for (candidate in candidates) {
            val wifiNetwork = requireTransferNetwork()
            val current = reader.current(candidate.uri)
            val result = if (current != null && matchesCloudBackupOriginal(candidate, current, consent.folder)) {
                backup.upload(current, uid, CloudAutoBackupTransfer(consent.folder, wifiNetwork)) {
                    requireTransferNetwork()
                }
            } else CloudBackupResult.ORIGINAL_UNAVAILABLE
            when (result) {
                CloudBackupResult.VERIFIED, CloudBackupResult.ORIGINAL_UNAVAILABLE, CloudBackupResult.UNSUPPORTED_MEDIA -> {
                    requireTransferNetwork()
                    val verified = result == CloudBackupResult.VERIFIED
                    if (!settings.record(consent, cloudAutoBackupVersion(candidate), verified)) {
                        return finish(settings, consent, CloudAutoBackupStatus.ERROR)
                    }
                    verifiedAny = verifiedAny || verified
                }
                CloudBackupResult.ACCOUNT_CHANGED -> return Result.success()
                CloudBackupResult.QUOTA_REACHED -> return finish(settings, consent, CloudAutoBackupStatus.LIMIT_REACHED)
                CloudBackupResult.TEST_ACCESS_REQUIRED -> return finish(settings, consent, CloudAutoBackupStatus.CLOUD_UNAVAILABLE)
                CloudBackupResult.NETWORK_ERROR, CloudBackupResult.FAILED -> return retryOrStop(settings, consent)
                else -> return finish(settings, consent, CloudAutoBackupStatus.ERROR)
            }
        }
        return finish(settings, consent, if (verifiedAny) CloudAutoBackupStatus.VERIFIED else CloudAutoBackupStatus.ERROR)
    }

    private fun retryOrStop(settings: CloudAutoBackupSettings, consent: CloudAutoBackupConsent): Result {
        if (runAttemptCount >= 2) return finish(settings, consent, CloudAutoBackupStatus.ERROR)
        settings.status(consent, CloudAutoBackupStatus.RETRY_SCHEDULED)
        return Result.retry()
    }

    private fun finish(
        settings: CloudAutoBackupSettings,
        consent: CloudAutoBackupConsent,
        status: CloudAutoBackupStatus,
    ): Result {
        settings.status(consent, status)
        return Result.success()
    }

    private class BackupPaused(val status: CloudAutoBackupStatus) : CancellationException()
}

internal fun hasFullAutoBackupPermission(context: Context): Boolean {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val media = if (Build.VERSION.SDK_INT >= 33) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    return media && granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
}

private fun ConnectivityManager.isValidatedUnmeteredWifi(network: Network): Boolean {
    if (activeNetwork != network) return false
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
