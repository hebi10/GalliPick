package com.hebi.gallipick.data.cloud

import android.net.Network
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
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

internal fun readCloudBackupCallableResult(
    status: Int,
    maxResponseBytes: Int,
    openResponse: () -> InputStream?,
    ensureActive: () -> Unit,
): JSONObject {
    if (status == HttpURLConnection.HTTP_UNAUTHORIZED) failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
    if (status == HttpURLConnection.HTTP_FORBIDDEN) {
        throw CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)
    }
    val response = openResponse()?.use { stream ->
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
            if (status == HttpURLConnection.HTTP_OK) failBackup(CloudBackupResult.FAILED) else null
        }
    }
    when (response?.optJSONObject("error")?.optString("status")) {
        "UNAUTHENTICATED" -> failBackup(CloudBackupResult.TEST_ACCESS_REQUIRED)
        "PERMISSION_DENIED" -> throw CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)
        "RESOURCE_EXHAUSTED" -> failBackup(CloudBackupResult.QUOTA_REACHED)
        "UNAVAILABLE" -> throw cloudBackupNetworkError(CloudBackupNetworkFailure.SERVICE)
        "DEADLINE_EXCEEDED" -> throw cloudBackupNetworkError(CloudBackupNetworkFailure.TIMEOUT)
        null -> Unit
        else -> if (status == HttpURLConnection.HTTP_OK) failBackup(CloudBackupResult.FAILED)
    }
    when (status) {
        HttpURLConnection.HTTP_OK -> Unit
        HttpURLConnection.HTTP_CLIENT_TIMEOUT, HttpURLConnection.HTTP_GATEWAY_TIMEOUT ->
            throw cloudBackupNetworkError(CloudBackupNetworkFailure.TIMEOUT)
        429, in 500..599 -> throw cloudBackupNetworkError(CloudBackupNetworkFailure.SERVICE)
        else -> failBackup(CloudBackupResult.FAILED)
    }
    return response?.optJSONObject("result") ?: failBackup(CloudBackupResult.FAILED)
}

internal class CloudBackupTransport(
    private val endpoint: String,
    private val buckets: Set<String>,
    private val productionProtocol: Boolean = false,
    private val vaultSessionToken: (() -> String)? = null,
    private val appCheckToken: (suspend () -> String?)? = null,
) {
    val isPrivateVault = vaultSessionToken != null

    fun assertVaultSessionOpen() { vaultSessionToken?.invoke() }

    suspend fun begin(
        uid: String, token: String, hash: String, size: Long, mimeType: String, displayName: String,
        metadata: CloudUploadMetadata = CloudUploadMetadata(),
        namespace: CloudBackupNamespace = CloudBackupNamespace.DEVELOPMENT,
    ): CloudUploadTarget? {
        val data = uploadMetadata(hash, size, mimeType, displayName, metadata)
        val result = call("beginBackup", token, data)
        when (result.optString("state")) {
            "VERIFIED" -> return null
            "UPLOAD" -> Unit
            else -> failBackup(CloudBackupResult.FAILED)
        }
        val id = result.optString("uploadId")
        val headers = result.uploadHeaders()
        val url = validateBackupUpload(result.optString("uploadUrl"), id, uid, hash, size, mimeType, headers, buckets, namespace, isPrivateVault)
            ?: failBackup(CloudBackupResult.FAILED)
        return CloudUploadTarget(id, url, headers)
    }

    suspend fun beginResumable(
        token: String, hash: String, size: Long, mimeType: String, displayName: String, metadata: CloudUploadMetadata,
    ): Pair<CloudResumableUpload, CloudResumableState> {
        val reply = call("beginBackup", token, uploadMetadata(hash, size, mimeType, displayName, metadata),
            protocolVersion = CLOUD_RESUMABLE_PROTOCOL_VERSION)
        val count = reply.exactLong("partCount")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        val firstPart = if (reply.has("nextPartIndex")) {
            reply.exactLong("nextPartIndex")?.takeIf { it in 0..count.toLong() }?.toInt()
                ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        } else 0
        val upload = cloudResumableUpload(reply.optString("uploadId"), hash, size, reply.optString("transferId"),
            reply.exactLong("partSizeBytes") ?: 0L, count, firstPart) ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return upload to reply.resumableState(upload.id)
    }

    suspend fun resumablePart(
        uid: String, token: String, upload: CloudResumableUpload, part: CloudResumablePart, mimeType: String,
        namespace: CloudBackupNamespace,
    ): CloudUploadTarget? {
        val reply = call("getBackupPart", token, JSONObject().put("uploadId", upload.id).put("partIndex", part.index),
            protocolVersion = CLOUD_RESUMABLE_PROTOCOL_VERSION)
        if (reply.exactLong("offsetBytes") != part.offsetBytes || reply.exactLong("sizeBytes") != part.sizeBytes) {
            failBackup(CloudBackupResult.INTEGRITY_ERROR)
        }
        when (reply.optString("state")) {
            "UPLOADED" -> return null
            "UPLOAD" -> Unit
            else -> failBackup(CloudBackupResult.INTEGRITY_ERROR)
        }
        val headers = reply.uploadHeaders()
        val url = validateResumablePartUpload(reply.optString("uploadUrl"), uid, upload, part, mimeType,
            headers, buckets, namespace, isPrivateVault) ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return CloudUploadTarget(upload.id, url, headers)
    }

    suspend fun putResumablePart(
        target: CloudUploadTarget, part: CloudResumablePart, source: CloudResumableSource, checkAccount: () -> Unit,
    ) {
        exchange(target.url.toURL()) { connection, ensureActive ->
            connection.requestMethod = "PUT"
            target.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(part.sizeBytes)
            checkAccount()
            connection.outputStream.use { output ->
                source.copyPart(part, output) {
                    ensureActive()
                    checkAccount()
                }
            }
            checkAccount()
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_NO_CONTENT -> Unit
                // A disconnect can occur after GCS has stored the part. Re-query the server before retrying.
                HttpURLConnection.HTTP_PRECON_FAILED, HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN, HttpURLConnection.HTTP_CLIENT_TIMEOUT, 429, in 500..599 ->
                    failBackup(CloudBackupResult.NETWORK_ERROR)
                else -> failBackup(CloudBackupResult.FAILED)
            }
        }
    }

    suspend fun finishResumable(uploadId: String, token: String): CloudResumableState =
        call("finishBackup", token, JSONObject().put("uploadId", uploadId),
            protocolVersion = CLOUD_RESUMABLE_PROTOCOL_VERSION).resumableState(uploadId)

    suspend fun resumableStatus(uploadId: String, token: String): CloudResumableState =
        call("getBackupStatus", token, JSONObject().put("uploadId", uploadId),
            protocolVersion = CLOUD_RESUMABLE_PROTOCOL_VERSION).resumableState(uploadId)

    private fun uploadMetadata(
        hash: String, size: Long, mimeType: String, displayName: String, metadata: CloudUploadMetadata,
    ): JSONObject = JSONObject().put("sha256", hash).put("sizeBytes", size).put("mimeType", mimeType).apply {
        cloudOriginalBasename(displayName).takeIf { it.isNotEmpty() }?.let { put("displayName", it) }
        metadata.sourceFolder?.let { put("sourceFolder", it) }
        metadata.capturedAtMs?.let { put("capturedAtMs", it) }
        metadata.width?.let { put("width", it) }
        metadata.height?.let { put("height", it) }
        metadata.durationMs?.let { put("durationMs", it) }
    }

    private fun JSONObject.uploadHeaders(): Map<String, String> {
        val headers = optJSONObject("headers") ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return headers.keys().asSequence().associateWith { name ->
            headers.opt(name) as? String ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
        }
    }

    private fun JSONObject.exactLong(key: String): Long? {
        val value = opt(key) as? Number ?: return null
        return value.toLong().takeIf { it.toDouble() == value.toDouble() }
    }

    private fun JSONObject.resumableState(uploadId: String): CloudResumableState {
        if (has("uploadId") && optString("uploadId") != uploadId) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        return CloudResumableState.entries.find { it.name == optString("state") }
            ?: failBackup(CloudBackupResult.INTEGRITY_ERROR)
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

    suspend fun listOriginals(token: String, namespace: CloudBackupNamespace, resumable: Boolean = false): List<CloudOriginal> {
        val paginated = resumable || namespace != CloudBackupNamespace.DEVELOPMENT
        val protocolVersion = if (resumable) CLOUD_RESUMABLE_PROTOCOL_VERSION else 1
        val catalog = linkedMapOf<String, CloudOriginal>()
        var cursor: String? = null
        repeat(if (paginated) MAX_PRODUCTION_CATALOG_PAGES else 1) {
            val query = JSONObject()
            cursor?.let { query.put("cursor", it) }
            val page = call("listBackups", token, query, MAX_CATALOG_RESPONSE_BYTES, protocolVersion = protocolVersion)
            val items = page.optJSONArray("items") ?: failBackup(CloudBackupResult.FAILED)
            val limit = if (paginated) PRODUCTION_CATALOG_PAGE_SIZE else MAX_DEV_CATALOG_ITEMS
            if (items.length() > limit) failBackup(CloudBackupResult.FAILED)
            var lastId = cursor
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: failBackup(CloudBackupResult.FAILED)
                val verifiedAtMs = item.optLong("verifiedAtMs", -1)
                if (verifiedAtMs < 0) failBackup(CloudBackupResult.FAILED)
                val original = item.cloudOriginal(verifiedAtMs, namespace, protocolVersion)
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
        val result = call("getBackupDownload", token, JSONObject().put("uploadId", item.uploadId),
            protocolVersion = item.transferProtocolVersion)
        val returned = result.cloudOriginal(item.verifiedAtMs, namespace, item.transferProtocolVersion)
        // Folder memberships may change after catalog fetch; only immutable content is pinned here.
        if (returned.uploadId != item.uploadId || returned.sha256 != item.sha256 ||
            returned.sizeBytes != item.sizeBytes || returned.mimeType != item.mimeType ||
            returned.displayName != item.displayName
        ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        val generation = result.optString("generation")
        val target = validateBackupDownload(result.optString("downloadUrl"), item.uploadId, uid, generation, buckets,
            namespace, isPrivateVault, resumable = item.transferProtocolVersion == CLOUD_RESUMABLE_PROTOCOL_VERSION)
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
                copyAndVerifyCloudOriginal(source, output, item.sizeBytes, item.sha256,
                    resumable = item.transferProtocolVersion == CLOUD_RESUMABLE_PROTOCOL_VERSION) {
                    ensureActive()
                    checkAccount()
                }
            }
        }
    }

    internal suspend fun call(
        route: String, token: String, data: JSONObject, maxResponseBytes: Int = MAX_RESPONSE_BYTES,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
        protocolVersion: Int = 1,
        includeProtocol: Boolean = true,
    ): JSONObject {
        if (includeProtocol && (productionProtocol || protocolVersion == CLOUD_RESUMABLE_PROTOCOL_VERSION)) {
            data.put("cloudProtocolVersion", protocolVersion)
        }
        val destination = if (vaultSessionToken == null) route else {
            data.put("action", route).put("token", vaultSessionToken.invoke())
            "secretCloud"
        }
        val body = JSONObject().put("data", data).toString().toByteArray(Charsets.UTF_8)
        val attestation = appCheckToken?.invoke()?.takeIf { it.isNotBlank() }
        return exchange(URL("$endpoint/$destination"), readTimeoutMillis = readTimeoutMillis) { connection, ensureActive ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $token")
            attestation?.let { connection.setRequestProperty("X-Firebase-AppCheck", it) }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            readCloudBackupCallableResult(
                status,
                maxResponseBytes,
                openResponse = { if (status == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream },
                ensureActive = ensureActive,
            )
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
            } catch (error: IOException) {
                continuation.resumeWith(Result.failure(error.asCloudBackupNetworkError()))
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
            } catch (error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error.asCloudBackupNetworkError()))
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun JSONObject.cloudOriginal(verifiedAtMs: Long, namespace: CloudBackupNamespace, protocolVersion: Int): CloudOriginal {
        if ((protocolVersion == CLOUD_RESUMABLE_PROTOCOL_VERSION || has("transferVersion")) &&
            exactLong("transferVersion") != protocolVersion.toLong()
        ) failBackup(CloudBackupResult.INTEGRITY_ERROR)
        val sha256 = optString("sha256")
        val uploadId = optString("uploadId")
        val size = exactLong("sizeBytes") ?: -1L
        val mimeType = optString("mimeType")
        val name = cloudOriginalBasename(optString("displayName"))
        val protocolLimit = when {
            protocolVersion == CLOUD_RESUMABLE_PROTOCOL_VERSION -> CLOUD_RESUMABLE_ORIGINAL_MAX_BYTES
            namespace == CloudBackupNamespace.DEVELOPMENT -> CLOUD_BACKUP_MAX_BYTES
            else -> CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES
        }
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
            transferProtocolVersion = protocolVersion,
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

private fun IOException.asCloudBackupNetworkError() = cloudBackupNetworkError(
    if (this is SocketTimeoutException) CloudBackupNetworkFailure.TIMEOUT else CloudBackupNetworkFailure.CONNECTION,
)
