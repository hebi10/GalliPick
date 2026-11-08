package com.hebi.gallipick.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 64-bit perceptual hash of an orientation-corrected 32 x 32 ARGB thumbnail.
 * This is a similarity signal, NOT evidence that two files are identical.
 */
object PhotoPerceptualHash {
    const val SIDE = 32
    private const val LOW = 8
    private val cosine = Array(LOW) { frequency ->
        DoubleArray(SIDE) { sample -> cos(((2 * sample + 1) * frequency * PI) / (2 * SIDE)) }
    }
    private val factors = DoubleArray(LOW) { frequency ->
        sqrt(if (frequency == 0) 1.0 / SIDE else 2.0 / SIDE)
    }

    fun fromArgb(argb: IntArray): String {
        require(argb.size == SIDE * SIDE) { "A 32x32 thumbnail is required" }
        val grayscale = DoubleArray(argb.size) { index ->
            val pixel = argb[index]
            val red = (pixel ushr 16) and 0xff
            val green = (pixel ushr 8) and 0xff
            val blue = pixel and 0xff
            val alpha = (pixel ushr 24) and 0xff
            val luminance = red * 0.299 + green * 0.587 + blue * 0.114
            (luminance * alpha + 255.0 * (255 - alpha)) / 255.0
        }
        val rows = Array(LOW) { frequency ->
            DoubleArray(SIDE) { y ->
                var sum = 0.0
                for (x in 0 until SIDE) sum += grayscale[y * SIDE + x] * cosine[frequency][x]
                sum * factors[frequency]
            }
        }
        val low = DoubleArray(LOW * LOW)
        for (v in 0 until LOW) for (u in 0 until LOW) {
            var sum = 0.0
            for (y in 0 until SIDE) sum += rows[u][y] * cosine[v][y]
            low[v * LOW + u] = sum * factors[v]
        }
        val values = low.slice(1 until low.size).sorted()
        val median = values[values.size / 2]
        var hash = 0L
        for (index in low.indices) {
            if (index != 0 && low[index] > median) hash = hash or (1L shl index)
        }
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    fun distance(first: String, second: String): Int {
        require(first.length == 16 && second.length == 16) { "Expected 16 hexadecimal characters" }
        val a = java.lang.Long.parseUnsignedLong(first, 16)
        val b = java.lang.Long.parseUnsignedLong(second, 16)
        return java.lang.Long.bitCount(a xor b)
    }
}
