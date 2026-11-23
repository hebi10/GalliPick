package com.hebi.gallipick.data.cloud

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.withLock

/** Keeps the Storage URL out of the Android media framework and hands it only verified ranges. */
internal class CloudVideoRangeSource(
    private val stream: CloudVideoStreamCapability,
    val sizeBytes: Long,
    private val openConnection: () -> HttpsURLConnection = {
        openCloudBackupConnection(stream.signedUrl.toURL(), null)
    },
) : Closeable {
    private val readLock = ReentrantLock()
    @Volatile private var closed = false
    @Volatile private var connection: HttpsURLConnection? = null
    private var input: InputStream? = null
    private var nextPosition = -1L

    fun readAt(position: Long, bytes: ByteArray, offset: Int, size: Int): Int = readLock.withLock {
        try {
            checkReadable()
            if (position < 0 || offset !in 0..bytes.size || size !in 0..(bytes.size - offset)) throw readFailure()
            if (size == 0) return@withLock 0
            if (position >= sizeBytes) return@withLock -1
            if (nextPosition != position || input == null) openRange(position)
            val count = checkNotNull(input).read(bytes, offset, minOf(size.toLong(), sizeBytes - position).toInt())
            checkReadable()
            if (count <= 0) throw readFailure()
            nextPosition = position + count
            count
        } catch (_: IOException) {
            releaseRange()
            throw readFailure()
        } catch (_: RuntimeException) {
            releaseRange()
            throw readFailure()
        } finally {
            if (closed) releaseRange()
        }
    }

    private fun openRange(position: Long) {
        releaseRange()
        val next = openConnection()
        connection = next
        checkReadable()
        next.connectTimeout = 15_000
        next.readTimeout = 15_000
        next.instanceFollowRedirects = false
        next.useCaches = false
        next.requestMethod = "GET"
        val last = sizeBytes - 1
        next.setRequestProperty("Range", "bytes=$position-$last")
        next.setRequestProperty("Accept-Encoding", "identity")
        if (!validCloudVideoRange(
                next.responseCode, next.getHeaderField("Content-Range"), next.getHeaderField("Content-Length"),
                next.getHeaderField("x-goog-generation"), next.getHeaderField("Content-Encoding"),
                stream.generation, sizeBytes, from = position, through = last,
            )
        ) throw readFailure()
        input = next.inputStream
        checkReadable()
    }

    private fun checkReadable() {
        if (closed || !stream.mayRead()) throw readFailure()
    }

    private fun releaseRange() {
        runCatching { connection?.disconnect() }
        connection = null
        runCatching { input?.close() }
        input = null
    }

    override fun close() {
        closed = true
        // Interrupt an in-flight read without waiting for network I/O on the main thread.
        runCatching { connection?.disconnect() }
        if (readLock.tryLock()) {
            try {
                releaseRange()
            } finally {
                readLock.unlock()
            }
        }
    }

    private fun readFailure() = IOException("Could not read the cloud video.")
}
