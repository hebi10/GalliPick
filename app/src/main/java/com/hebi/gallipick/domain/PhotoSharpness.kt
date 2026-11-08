package com.hebi.gallipick.domain

import kotlin.math.max

/**
 * Conservative, local-only heuristic. A low sampled Laplacian variance is NOT proof of
 * an unusable photo; deliberate shallow depth of field and smooth scenes can also qualify.
 */
object PhotoSharpness {
    const val SIDE = 128
    const val MIN_ORIGINAL_SIDE = 640
    const val LOW_SCORE_THRESHOLD = 20.0
    const val UNKNOWN_SCORE = -1.0

    fun score(luminance: IntArray): Double? {
        require(luminance.size == SIDE * SIDE)
        val mean = luminance.sumOf { it.toDouble() } / luminance.size
        val variance = luminance.sumOf { (it - mean) * (it - mean) } / luminance.size
        val spread = (luminance.maxOrNull() ?: 0) - (luminance.minOrNull() ?: 0)
        if (variance < 225.0 || spread < 45) return null

        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until SIDE - 1) {
            for (x in 1 until SIDE - 1) {
                val index = y * SIDE + x
                val laplacian = 4 * luminance[index] -
                    luminance[index - 1] - luminance[index + 1] -
                    luminance[index - SIDE] - luminance[index + SIDE]
                sum += laplacian
                sumSquares += laplacian.toDouble() * laplacian
                count++
            }
        }
        return max(0.0, sumSquares / count - (sum / count) * (sum / count))
    }

    fun isReviewCandidate(score: Double?): Boolean =
        score != null && score.isFinite() && score >= 0.0 && score <= LOW_SCORE_THRESHOLD
}
