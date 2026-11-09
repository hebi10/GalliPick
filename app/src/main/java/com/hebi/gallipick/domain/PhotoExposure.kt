package com.hebi.gallipick.domain

import kotlin.math.abs

/**
 * Local, sampled exposure *hint*, not a photographic correctness score.
 * Bright/dark intentional scenes can legitimately receive a lower value.
 */
object PhotoExposure {
    const val UNKNOWN_SCORE = -1.0

    fun score(luminance: IntArray): Double? {
        require(luminance.size == PhotoSharpness.SIDE * PhotoSharpness.SIDE)
        val min = luminance.minOrNull() ?: return null
        val max = luminance.maxOrNull() ?: return null
        if (max - min < 35) return null

        val mean = luminance.sumOf { it.toDouble() } / luminance.size
        val clipped = luminance.count { it <= 8 || it >= 247 }.toDouble() / luminance.size
        val balance = 1.0 - abs(mean - 127.5) / 127.5
        return (0.4 * balance + 0.6 * (1.0 - clipped * 2.0).coerceIn(0.0, 1.0))
            .coerceIn(0.0, 1.0)
    }
}
