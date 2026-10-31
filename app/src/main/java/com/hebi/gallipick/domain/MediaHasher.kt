package com.hebi.gallipick.domain

import java.io.InputStream
import java.security.MessageDigest

object MediaHasher {
    /** The caller owns [stream]. Cancellation and read errors propagate unchanged. */
    fun sha256(stream: InputStream, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            checkCancelled()
            val count = stream.read(buffer)
            if (count == -1) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in digest.digest()) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
