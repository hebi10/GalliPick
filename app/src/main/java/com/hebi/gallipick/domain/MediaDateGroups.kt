package com.hebi.gallipick.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaDateGroup(val date: String, val items: List<MediaItem>)

object MediaDateGroups {
    fun group(items: List<MediaItem>, field: DateField, ascending: Boolean): List<MediaDateGroup> {
        val formatter = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        val grouped = items.groupBy { item ->
            val date = MediaQuery.dateOf(item, field)
            if (date > 0L) formatter.format(Date(date)) else "정보 없음"
        }
        val knownDays = grouped.keys.filter { it != "정보 없음" }.sorted()
        val orderedDays = if (ascending) knownDays else knownDays.reversed()
        return (orderedDays + listOfNotNull("정보 없음".takeIf { it in grouped }))
            .map { date -> MediaDateGroup(date, grouped.getValue(date)) }
    }
}
