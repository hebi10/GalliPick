package com.hebi.gallipick.data.cloud

import android.os.SystemClock
import java.io.Closeable
import org.json.JSONObject

internal class CloudSecretVault(
    private val control: CloudBackupTransport,
    private val tokenFor: suspend (String) -> String,
    private val openBackup: (() -> String) -> CloudBackup,
) {
    suspend fun configured(uid: String): Boolean {
        val response = control.call("secretCloud", tokenFor(uid), JSONObject().put("action", "status"))
        return response.opt("configured") as? Boolean ?: failBackup(CloudBackupResult.FAILED)
    }

    suspend fun unlock(uid: String, password: String, setup: Boolean): CloudSecretSession {
        val request = JSONObject().put("action", if (setup) "setup" else "unlock").put("password", password)
        val response = control.call("secretCloud", tokenFor(uid), request)
        val sessionToken = response.opt("token") as? String ?: failBackup(CloudBackupResult.FAILED)
        val duration = response.optLong("expiresInMs")
        if (!VAULT_TOKEN.matches(sessionToken) || duration !in 1..MAX_SESSION_DURATION_MS) {
            failBackup(CloudBackupResult.FAILED)
        }
        val expiresAt = SystemClock.elapsedRealtime() + duration
        val credential = VaultSessionCredential(sessionToken, expiresAt)
        return CloudSecretSession(openBackup(credential::requireToken), expiresAt, credential::clear)
    }

    private class VaultSessionCredential(initialToken: String, private val expiresAt: Long) {
        @Volatile private var token: String? = initialToken

        fun requireToken(): String {
            if (SystemClock.elapsedRealtime() >= expiresAt) clear()
            return token ?: failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
        }

        fun clear() { token = null }
    }

    private companion object {
        val VAULT_TOKEN = Regex("[A-Za-z0-9_-]{43,64}")
        const val MAX_SESSION_DURATION_MS = 20 * 60 * 1000L
    }
}

internal class CloudSecretSession(
    val backup: CloudBackup,
    val expiresAtElapsedMs: Long,
    private val clearCredential: () -> Unit,
) : Closeable {
    override fun close() = clearCredential()
}
