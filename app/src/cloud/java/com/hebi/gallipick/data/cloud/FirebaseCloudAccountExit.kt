package com.hebi.gallipick.data.cloud

import android.app.Activity
import org.json.JSONObject

internal class FirebaseCloudAccountExit(
    private val currentUid: () -> String?,
    private val idTokenFor: suspend (String, Boolean) -> String,
    private val reauthenticate: suspend (Activity, String) -> Unit,
    private val transport: CloudBackupTransport,
) : CloudAccountExit {
    private fun requireAccount(uid: String) {
        if (uid.isBlank() || currentUid() != uid) failBackup(CloudBackupResult.ACCOUNT_CHANGED)
    }

    override suspend fun status(uid: String): CloudExitSnapshot {
        requireAccount(uid)
        val token = idTokenFor(uid, false)
        requireAccount(uid)
        val result = transport.call("getAccountExitStatus", token, JSONObject(), includeProtocol = false)
        requireAccount(uid)
        return parseExitSnapshot(result)
    }

    override suspend fun request(activity: Activity, uid: String, scope: CloudExitScope): CloudExitSnapshot {
        requireAccount(uid)
        reauthenticate(activity, uid)
        requireAccount(uid)
        val token = idTokenFor(uid, true)
        requireAccount(uid)
        val data = JSONObject()
            .put("scope", scope.name)
            .put("confirmation", if (scope == CloudExitScope.ACCOUNT) "DELETE_ACCOUNT" else "DELETE_CLOUD")
            .put("acknowledgesPermanentDeletion", true)
            .put("acknowledgesPlayCancellation", true)
        val result = transport.call("requestAccountExit", token, data, includeProtocol = false)
        requireAccount(uid)
        return parseExitSnapshot(result).also {
            if (it.scope != scope || it.state != CloudExitState.REQUESTED &&
                it.state != CloudExitState.RUNNING && it.state != CloudExitState.IN_PROGRESS &&
                it.state != CloudExitState.RETRY && it.state != CloudExitState.MANUAL_REVIEW) {
                failBackup(CloudBackupResult.INTEGRITY_ERROR)
            }
        }
    }

    override suspend fun resumeCloudBackup(activity: Activity, uid: String): CloudExitSnapshot {
        requireAccount(uid)
        reauthenticate(activity, uid)
        requireAccount(uid)
        val token = idTokenFor(uid, true)
        requireAccount(uid)
        val result = transport.call("resumeCloudAfterDeletion", token,
            JSONObject().put("confirmation", "RESUME_CLOUD_BACKUP"), includeProtocol = false)
        requireAccount(uid)
        return parseExitSnapshot(result).also {
            if (it.state != CloudExitState.RESUMED || it.scope != CloudExitScope.CLOUD) {
                failBackup(CloudBackupResult.INTEGRITY_ERROR)
            }
        }
    }
}

internal fun parseExitSnapshot(result: JSONObject): CloudExitSnapshot {
    val state = CloudExitState.entries.firstOrNull { it.name == result.optString("status") }
        ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
    val scope = when (val value = result.opt("scope")) {
        null, JSONObject.NULL -> null
        is String -> CloudExitScope.entries.firstOrNull { it.name == value }
            ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        else -> failBackup(CloudBackupResult.INTEGRITY_ERROR)
    }
    if ((state == CloudExitState.NONE) != (scope == null)) failBackup(CloudBackupResult.INTEGRITY_ERROR)
    fun exactTime(field: String): Long? {
        val value = result.opt(field)
        if (value == null || value == JSONObject.NULL) return null
        val number = value as? Number ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        val ms = number.toLong()
        if (ms < 0L || number.toDouble() != ms.toDouble()) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return ms
    }
    return CloudExitSnapshot(
        state = state,
        scope = scope,
        requestedAtMs = exactTime("requestedAtMs"),
        deadlineAtMs = exactTime("deadlineAtMs"),
        completedAtMs = exactTime("completedAtMs"),
    )
}
