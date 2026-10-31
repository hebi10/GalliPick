package com.hebi.gallipick.domain

import java.util.Locale

fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    if (safeBytes < 1024) return "$safeBytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
    var amount = safeBytes.toDouble() / 1024
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", amount, units[unit])
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1000
    val seconds = totalSeconds % 60
    val totalMinutes = totalSeconds / 60
    return if (totalMinutes < 60) {
        String.format(Locale.ROOT, "%d:%02d", totalMinutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d:%02d", totalMinutes / 60, totalMinutes % 60, seconds)
    }
}
