package com.hebi.gallipick.data.cloud

import android.net.Network
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class CloudUploadTarget(val id: String, val url: URI, val headers: Map<String, String>)

internal fun openCloudBackupConnection(
    url: URL,
    openOnNetwork: ((URL) -> URLConnection)?,
): HttpsURLConnection {
    val connection = if (openOnNetwork == null) url.openConnection() else openOnNetwork(url)
    return connection as HttpsURLConnection
}

internal class CloudBackupTransport(
    private val endpoint: String,
    private val buckets: Set<String>,
    private val productionProtocol: Boolean = false,
) {
    suspend fun begin(
        uid: String, token: String, hash: String, size: Long, mimeType: String, displayName: String,
        metadata: CloudUploadMetadata = CloudUploadMetadata(),
        namespace: CloudBackupNamespace = CloudBackupNamespace.DEVELOPMENT,
    ): CloudUploadTarget? {
        val data = JSONObject().put("sha256", hash).put("sizeBytes", size).put("mimeType", mimeType)
        cloudOriginalBasename(displayName).takeIf { it.isNotEmpty() }?.let { data.put("displayName", it) }
        metadata.sourceFolder?.let { data.put("sourceFolder", it) }
        metadata.capturedAtMs?.let { data.put("capturedAtMs", it) }
        metadata.width?.let { data.put("width", it) }
        metadata.height?.let { data.put("height", it) }
        metadata.durationMs?.let { data.put("durationMs", it) }
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
        val url = validateBackupUpload(result.optString("uploadUrl"), id, uid, hash, size, mimeType, headers, buckets, namespace)
            ?: failBackup(CloudBackupResult.FAILED)
        return CloudUploadTarget(id, url, headers)
    }

    suspend fun put(
        target: CloudUploadTarget,
        source: InputStream,
        size: Long,
        wifiNetwork: Network?,
        checkAccount: () -> Unit,
    ) {
        exchange(target.url.toURL(), wifiNetwork) { connection, ensureActive ->
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

    suspend fun finish(id: String, token: String, namespace: CloudBackupNamespace) {
        val result = call(
            "finishBackup", token, JSONObject().put("uploadId", id),
            readTimeoutMillis = if (namespace == CloudBackupNamespace.DEVELOPMENT) DEFAULT_READ_TIMEOUT_MS
            else PRODUCTION_FINISH_READ_TIMEOUT_MS,
        )
        if (result.optString("state") != "VERIFIED") failBackup(CloudBackupResult.FAILED)
    }

    suspend fun listOriginals(token: String, namespace: CloudBackupNamespace): List<CloudOriginal> {
        val paginated = namespace != CloudBackupNamespace.DEVELOPMENT
        val catalog = linkedMapOf<String, CloudOriginal>()
        var cursor: String? = null
        repeat(if (paginated) MAX_PRODUCTION_CATALOG_PAGES else 1) {
            val query = JSONObject()
            cursor?.let { query.put("cursor", it) }
            val page = call("listBackups", token, query, MAX_CATALOG_RESPONSE_BYTES)
            val items = page.optJSONArray("items") ?: failBackup(CloudBackupResult.FAILED)
            val limit = if (paginated) PRODUCTION_CATALOG_PAGE_SIZE else MAX_DEV_CATALOG_ITEMS
            if (items.length() > limit) failBackup(CloudBackupResult.FAILED)
            var lastId = cursor
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: failBackup(CloudBackupResult.FAILED)
                val verifiedAtMs = item.optLong("verifiedAtMs", -1)
                if (verifiedAtMs < 0) failBackup(CloudBackupResult.FAILED)
                val original = item.cloudOriginal(verifiedAtMs, namespace)
                if ((paginated && lastId?.let { original.uploadId <= it } == true) ||
                    catalog.put(original.uploadId, original) != null
                ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
                lastId = original.uploadId
            }
            val next = page.opt("nextCursor")
            if (next == null || next == JSONObject.NULL) {
                return if (paginated) catalog.values.sortedWith(
                    compareByDescending<CloudOriginal> { it.verifiedAtMs }.thenBy { it.uploadId },
                ) else catalog.values.toList()
            }
            if (!paginated || items.length() != limit || next !is String || next != lastId) {
                failBackup(CloudBackupResult.INTEGRITY_ERROR)
            }
            cursor = next
        }
        failBackup(CloudBackupResult.FAILED)
    }

    suspend fun getDownload(
        uid: String, token: String, item: CloudOriginal, namespace: CloudBackupNamespace,
    ): CloudDownloadTarget {
        val result = call("getBackupDownload", token, JSONObject().put("uploadId", item.uploadId))
        val returned = result.cloudOriginal(item.verifiedAtMs, namespace)
        // Folder memberships may change after catalog fetch; only immutable content is pinned here.
        if (returned.uploadId != item.uploadId || returned.sha256 != item.sha256 ||
            returned.sizeBytes != item.sizeBytes || returned.mimeType != item.mimeType ||
            returned.displayName != item.displayName
        ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        val generation = result.optString("generation")
        val target = validateBackupDownload(result.optString("downloadUrl"), item.uploadId, uid, generation, buckets, namespace)
            ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return CloudDownloadTarget(target, generation)
    }

    /**
     * Check generation and HTTP byte-range support without downloading the
     * original or persisting a bearer URL.
     */
    suspend fun verifyVideoRange(
        target: CloudDownloadTarget,
        item: CloudOriginal,
        checkAccount: () -> Unit,
    ) {
        exchange(target.url.toURL()) { connection, ensureActive ->
            connection.requestMethod = "GET"
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            checkAccount()
            if (!validCloudVideoRange(
                    status = connection.responseCode,
                    contentRange = connection.getHeaderField("Content-Range"),
                    contentLength = connection.getHeaderField("Content-Length"),
                    responseGeneration = connection.getHeaderField("x-goog-generation"),
                    contentEncoding = connection.getHeaderField("Content-Encoding"),
                    expectedGeneration = target.generation,
                    sizeBytes = item.sizeBytes,
                )
            ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
            connection.inputStream.use { stream ->
                ensureActive()
                checkAccount()
                if (stream.read() == -1 || stream.read() != -1) {
                    failBackup(CloudBackupResult.INTEGRITY_ERROR)
                }
            }
        }
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

    internal suspend fun call(
        route: String, token: String, data: JSONObject, maxResponseBytes: Int = MAX_RESPONSE_BYTES,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
    ): JSONObject {
        if (productionProtocol) data.put("cloudProtocolVersion", 1)
        val body = JSONObject().put("data", data).toString().toByteArray(Charsets.UTF_8)
        return exchange(URL("$endpoint/$route"), readTimeoutMillis = readTimeoutMillis) { connection, ensureActive ->
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
                "UNAUTHENTICATED" -> failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
                "PERMISSION_DENIED" -> throw CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)
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
        wifiNetwork: Network? = null,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
        block: (HttpsURLConnection, ensureActive: () -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = try {
                openCloudBackupConnection(url, wifiNetwork?.let { it::openConnection })
            } catch (_: IOException) {
                continuation.resumeWith(Result.failure(CloudBackupException(CloudBackupResult.NETWORK_ERROR)))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                connection.connectTimeout = 15_000
                connection.readTimeout = readTimeoutMillis
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

    private fun JSONObject.cloudOriginal(verifiedAtMs: Long, namespace: CloudBackupNamespace): CloudOriginal {
        val sha256 = optString("sha256")
        val uploadId = optString("uploadId")
        val size = optLong("sizeBytes", -1)
        val mimeType = optString("mimeType")
        val name = cloudOriginalBasename(optString("displayName"))
        val protocolLimit = if (namespace == CloudBackupNamespace.DEVELOPMENT) CLOUD_BACKUP_MAX_BYTES
            else CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES
        if (!SHA256.matches(sha256) || uploadId != "$sha256-$size" ||
            size !in 1..protocolLimit || !CLOUD_MEDIA_MIME_TYPE.matches(mimeType) || name.isBlank()
        ) failBackup(CloudBackupResult.INTEGRITY_ERROR)

        val folders = when (val raw = opt("sourceFolders")) {
            null -> emptyList()
            is JSONArray -> {
                if (raw.length() > 8) failBackup(CloudBackupResult.INTEGRITY_ERROR)
                List(raw.length()) { i ->
                    val item = raw.opt(i)
                    if (item !is String || normalizeCloudFolderPath(item) != item) {
                        failBackup(CloudBackupResult.INTEGRITY_ERROR)
                    }
                    item
                }.also { if (it.distinct().size != it.size) failBackup(CloudBackupResult.INTEGRITY_ERROR) }
            }
            else -> failBackup(CloudBackupResult.INTEGRITY_ERROR)
        }
        fun optionalPositiveLong(key: String, maximum: Long): Long? {
            if (!has(key)) return null
            val value = opt(key)
            if (value !is Number) failBackup(CloudBackupResult.INTEGRITY_ERROR)
            val number = value.toLong()
            if (number !in 1..maximum || number.toDouble() != value.toDouble()) {
                failBackup(CloudBackupResult.INTEGRITY_ERROR)
            }
            return number
        }
        return CloudOriginal(
            uploadId, name, mimeType, size, sha256, verifiedAtMs,
            sourceFolders = folders,
            capturedAtMs = optionalPositiveLong("capturedAtMs", 9_007_199_254_740_991L),
            width = optionalPositiveLong("width", 100_000L)?.toInt(),
            height = optionalPositiveLong("height", 100_000L)?.toInt(),
            durationMs = optionalPositiveLong("durationMs", 9_007_199_254_740_991L),
        )
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_DEV_CATALOG_ITEMS = 250
        const val PRODUCTION_CATALOG_PAGE_SIZE = 100
        const val MAX_PRODUCTION_CATALOG_PAGES = 200
        const val MAX_CATALOG_RESPONSE_BYTES = 512 * 1024
        const val MAX_RESPONSE_BYTES = 32 * 1024
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val PRODUCTION_FINISH_READ_TIMEOUT_MS = 550_000
    }
}
