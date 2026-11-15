package com.hebi.gallipick.data.cloud

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    suspend fun begin(uid: String, token: String, hash: String, size: Long, mimeType: String, displayName: String): CloudUploadTarget? {
        val data = JSONObject().put("sha256", hash).put("sizeBytes", size).put("mimeType", mimeType)
        cloudOriginalBasename(displayName).takeIf { it.isNotEmpty() }?.let { data.put("displayName", it) }
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
            connection.doOutput = true
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

    suspend fun listOriginals(token: String): List<CloudOriginal> {
        val items = call("listBackups", token, JSONObject(), MAX_CATALOG_RESPONSE_BYTES).optJSONArray("items")
            ?: failBackup(CloudBackupResult.FAILED)
        val count = items.length()
        if (count > MAX_CATALOG_ITEMS) failBackup(CloudBackupResult.FAILED)
        val catalog = List(count) { index ->
            val item = items.optJSONObject(index) ?: failBackup(CloudBackupResult.FAILED)
            val verifiedAtMs = item.optLong("verifiedAtMs", -1)
            if (verifiedAtMs < 0) failBackup(CloudBackupResult.FAILED)
            item.cloudOriginal(verifiedAtMs)
        }
        if (catalog.distinctBy(CloudOriginal::uploadId).size != count) failBackup(CloudBackupResult.FAILED)
        return catalog
    }

    suspend fun getDownload(uid: String, token: String, item: CloudOriginal): CloudDownloadTarget {
        val result = call("getBackupDownload", token, JSONObject().put("uploadId", item.uploadId))
        val returned = result.cloudOriginal(item.verifiedAtMs)
        if (returned != item) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        val generation = result.optString("generation")
        val target = validateBackupDownload(result.optString("downloadUrl"), item.uploadId, uid, generation, buckets)
            ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return CloudDownloadTarget(target, generation)
    }

    suspend fun download(target: CloudDownloadTarget, item: CloudOriginal, output: OutputStream, checkAccount: () -> Unit) {
        exchange(target.url.toURL()) { connection, ensureActive ->
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            checkAccount()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) failBackup(CloudBackupResult.FAILED)
            val length = connection.getHeaderField("Content-Length")
            if (connection.getHeaderField("x-goog-generation") != target.generation ||
                connection.getHeaderField("Content-Encoding")?.equals("identity", ignoreCase = true) == false ||
                (length != null && length.toLongOrNull() != item.sizeBytes)
            ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
            connection.inputStream.use { source ->
                copyAndVerifyCloudOriginal(source, output, item.sizeBytes, item.sha256) {
                    ensureActive()
                    checkAccount()
                }
            }
        }
    }

    internal suspend fun call(route: String, token: String, data: JSONObject, maxResponseBytes: Int = MAX_RESPONSE_BYTES): JSONObject {
        val body = JSONObject().put("data", data).toString().toByteArray(Charsets.UTF_8)
        return exchange(URL("$endpoint/$route")) { connection, ensureActive ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val input = if (status == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            val response = input?.use { stream ->
                val bytes = ByteArray(maxResponseBytes + 1)
                var size = 0
                while (size < bytes.size) {
                    ensureActive()
                    val count = stream.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > maxResponseBytes) failBackup(CloudBackupResult.FAILED)
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

    private fun JSONObject.cloudOriginal(verifiedAtMs: Long): CloudOriginal {
        val sha256 = optString("sha256")
        val uploadId = optString("uploadId")
        val size = optLong("sizeBytes", -1)
        val mimeType = optString("mimeType")
        val name = cloudOriginalBasename(optString("displayName"))
        if (!SHA256.matches(sha256) || uploadId != "$sha256-$size" ||
            size !in 1..CLOUD_BACKUP_MAX_BYTES || !CLOUD_MEDIA_MIME_TYPE.matches(mimeType) || name.isBlank()
        ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return CloudOriginal(uploadId, name, mimeType, size, sha256, verifiedAtMs)
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_CATALOG_ITEMS = 250
        const val MAX_CATALOG_RESPONSE_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 32 * 1024
    }
}
