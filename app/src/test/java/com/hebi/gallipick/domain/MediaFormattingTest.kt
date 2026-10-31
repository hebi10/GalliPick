package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormattingTest {
    @Test
    fun bytesUseReadableBinaryUnitsWithoutNarrowingToInt() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-1))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("5.0 GB", formatBytes(5L * 1024 * 1024 * 1024))
    }

    @Test
    fun durationUsesMinutesSecondsAndHoursWithZeroPadding() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:00", formatDuration(-1))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }
}
