package com.hebi.gallipick.domain

enum class PhotoAnalysisPhase { IDLE, QUEUED, RUNNING, PAUSED, COMPLETE, FAILED }

data class PhotoAnalysisState(
    val phase: PhotoAnalysisPhase = PhotoAnalysisPhase.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
)

object PerceptualHash {
    private const val SIDE = 32
    private const val BLOCK = 8
    private val coefficients = Array(SIDE) { frequency ->
        DoubleArray(SIDE) { position ->
            kotlin.math.cos((2 * position + 1) * frequency * Math.PI / (2 * SIDE))
        }
    }

    /** Hash pixels in row-major order (32x32), keeping 8x8 low-frequency DCT coefficients. */
    fun fromLuminance(pixels: IntArray): String {
        require(pixels.size == SIDE * SIDE)
        val coefficients64 = DoubleArray(BLOCK * BLOCK)
        for (v in 0 until BLOCK) {
            for (u in 0 until BLOCK) {
                var sum = 0.0
                for (y in 0 until SIDE) {
                    var row = 0.0
                    for (x in 0 until SIDE) row += pixels[y * SIDE + x].toDouble() * coefficients[u][x]
                    sum += row * coefficients[v][y]
                }
                coefficients64[v * BLOCK + u] = sum
            }
        }
        // The DC (brightness) coefficient does not describe visual structure.
        val ac = coefficients64.drop(1)
        val maxMagnitude = ac.maxOf { kotlin.math.abs(it) }
        // Integer image data should have exactly-zero insignificant coefficients.
        // Floating-point DCT accumulation creates noisy signs around zero; normalize them.
        val floor = maxMagnitude * 1e-8 + 1e-6
        val normalized = ac.map { if (kotlin.math.abs(it) <= floor) 0.0 else it }
        val sorted = normalized.sorted()
        val median = sorted[sorted.size / 2]
        var bits = 0UL
        for (i in normalized.indices) {
            if (normalized[i] > median) bits = bits or (1UL shl i)
        }
        return bits.toString(16).padStart(16, '0')
    }

    fun distance(first: String, second: String): Int {
        require(first.length == 16 && second.length == 16 &&
            first.all { it in "0123456789abcdef" } && second.all { it in "0123456789abcdef" })
        return java.lang.Long.bitCount(first.toULong(16).toLong() xor second.toULong(16).toLong())
    }
}
