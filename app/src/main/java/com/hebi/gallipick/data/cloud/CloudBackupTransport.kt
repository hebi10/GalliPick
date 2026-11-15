package com.hebi.gallipick.data.cloud

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal data class CloudUploadTarget(val id: String, val url: URI, val headers: Map<String, String>)

internal class CloudBackupTransport(
    private val endpoint: String,
    private val buckets: Set<String>,
) {
    suspend fun begin(uid: String, token: String, hash: String, size: Long, mimeType: String): CloudUploadTarget? {
        val data = JSONObject().put("sha256", hash).put("sizeBytes", size).put("mimeType", mimeType)
        val result = call("beginBackup", token, data)
        when (result.optString("state")) {
            "VERIFIED" -> return null
            "UPLOAD" -> Unit
            else -> failBackup(CloudBackupResult.FAILED)
        }
        val id = result.optString("uploadId")
        val rawHeaders = result.optJSONObject("headers") ?: failBackup(CloudBackupResult.FAILED)
        val headers = rawHeaders.keys().asSequence().associateWith { name ->
            rawHeaders.opt(name) as? String ?: failBackup(CloudBackupResult.FAILED)
        }
        val url = validateBackupUpload(result.optString("uploadUrl"), id, uid, hash, size, mimeType, headers, buckets)
            ?: failBackup(CloudBackupResult.FAILED)
        return CloudUploadTarget(id, url, headers)
    }

    suspend fun put(target: CloudUploadTarget, source: InputStream, size: Long, checkAccount: () -> Unit) {
        exchange(target.url.toURL()) { connection, ensureActive ->
            connection.requestMethod = "PUT"
            target.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(size)
            checkAccount()
            connection.outputStream.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    ensureActive()
                    checkAccount()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_NO_CONTENT,
                HttpURLConnection.HTTP_PRECON_FAILED -> Unit
                else -> failBackup(CloudBackupResult.FAILED)
            }
        }
    }

    suspend fun finish(id: String, token: String) {
        val result = call("finishBackup", token, JSONObject().put("uploadId", id))
        if (result.optString("state") != "VERIFIED") failBackup(CloudBackupResult.FAILED)
    }

    private suspend fun call(route: String, token: String, data: JSONObject): JSONObject {
        val body = JSONObject().put("data", data).toString().toByteArray(Charsets.UTF_8)
        return exchange(URL("$endpoint/$route")) { connection, ensureActive ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            val response = input?.use { stream ->
                val bytes = ByteArray(MAX_RESPONSE_BYTES + 1)
                var size = 0
                while (size < bytes.size) {
                    ensureActive()
                    val count = stream.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > MAX_RESPONSE_BYTES) failBackup(CloudBackupResult.FAILED)
                try {
                    JSONObject(String(bytes, 0, size, Charsets.UTF_8))
                } catch (_: JSONException) {
                    failBackup(CloudBackupResult.FAILED)
                }
            }
            when (response?.optJSONObject("error")?.optString("status")) {
                "UNAUTHENTICATED", "PERMISSION_DENIED" -> failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
                "RESOURCE_EXHAUSTED" -> failBackup(CloudBackupResult.QUOTA_REACHED)
                "UNAVAILABLE", "DEADLINE_EXCEEDED" -> failBackup(CloudBackupResult.NETWORK_ERROR)
                null -> Unit
                else -> failBackup(CloudBackupResult.FAILED)
            }
            if (status != HttpURLConnection.HTTP_OK) failBackup(CloudBackupResult.FAILED)
            response?.optJSONObject("result") ?: failBackup(CloudBackupResult.FAILED)
        }
    }

    private suspend fun <T> exchange(
        url: URL,
        block: (HttpsURLConnection, ensureActive: () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = try {
                url.openConnection() as HttpsURLConnection
            } catch (_: IOException) {
                continuation.resumeWith(Result.failure(CloudBackupException(CloudBackupResult.NETWORK_ERROR)))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doOutput = true
                val result = block(connection) { continuation.context.ensureActive() }
                continuation.resumeWith(Result.success(result))
            } catch (error: CancellationException) {
                continuation.cancel(error)
            } catch (_: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(CloudBackupException(CloudBackupResult.NETWORK_ERROR)))
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 32 * 1024
    }
}
