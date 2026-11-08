package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class MediaDateGroupsTest {
    @Test
    fun groupsDownloadedMediaByDeviceAddedDayAndKeepsInputOrderWithinEachDay() {
        val first = day(2025, Calendar.FEBRUARY, 1)
        val second = day(2025, Calendar.FEBRUARY, 3)
        val items = listOf(
            media("later-a").copy(dateTakenMs = first, dateAddedMs = second),
            media("early").copy(dateTakenMs = second, dateAddedMs = first),
            media("later-b").copy(dateTakenMs = first, dateAddedMs = second + 60_000),
            media("unknown").copy(dateTakenMs = 0, dateAddedMs = 0),
        )

        val ascending = MediaDateGroups.group(items, DateField.ADDED, ascending = true)
        assertEquals(listOf("2025.02.01", "2025.02.03", "정보 없음"), ascending.map { it.date })
        assertEquals(listOf("later-a", "later-b"), ascending[1].items.map { it.uri })

        val descending = MediaDateGroups.group(items, DateField.ADDED, ascending = false)
        assertEquals(listOf("2025.02.03", "2025.02.01", "정보 없음"), descending.map { it.date })
    }

    private fun day(year: Int, month: Int, date: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, month, date, 12, 0, 0)
    }.timeInMillis
}
