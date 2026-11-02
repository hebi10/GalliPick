package com.hebi.gallipick.ui

import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField

internal data class SortChoice(val field: SortField, val direction: SortDirection, val label: String)

internal val SortChoices = listOf(
    SortChoice(SortField.SIZE, SortDirection.DESCENDING, "용량 큰 순"),
    SortChoice(SortField.SIZE, SortDirection.ASCENDING, "용량 작은 순"),
    SortChoice(SortField.DURATION, SortDirection.DESCENDING, "영상 긴 순"),
    SortChoice(SortField.DURATION, SortDirection.ASCENDING, "영상 짧은 순"),
    SortChoice(SortField.CAPTURED, SortDirection.DESCENDING, "최신 촬영일 순"),
    SortChoice(SortField.CAPTURED, SortDirection.ASCENDING, "오래된 촬영일 순"),
    SortChoice(SortField.ADDED, SortDirection.DESCENDING, "최신 저장일 순"),
    SortChoice(SortField.ADDED, SortDirection.ASCENDING, "오래된 저장일 순"),
    SortChoice(SortField.MODIFIED, SortDirection.DESCENDING, "최신 수정일 순"),
    SortChoice(SortField.MODIFIED, SortDirection.ASCENDING, "오래된 수정일 순"),
    SortChoice(SortField.NAME, SortDirection.ASCENDING, "이름 오름차순"),
    SortChoice(SortField.NAME, SortDirection.DESCENDING, "이름 내림차순"),
    SortChoice(SortField.PIXELS, SortDirection.DESCENDING, "해상도 높은 순"),
    SortChoice(SortField.PIXELS, SortDirection.ASCENDING, "해상도 낮은 순"),
)

internal fun sortChoiceLabel(filter: MediaFilter): String = SortChoices.first { it.field == filter.sortField && it.direction == filter.sortDirection }.label

internal fun shortSortLabel(filter: MediaFilter): String {
    val field = when (filter.sortField) {
        SortField.SIZE -> "용량"; SortField.DURATION -> "길이"; SortField.CAPTURED -> "촬영일"
        SortField.ADDED -> "저장일"; SortField.MODIFIED -> "수정일"; SortField.NAME -> "이름"; SortField.PIXELS -> "해상도"
    }
    return field + if (filter.sortDirection == SortDirection.ASCENDING) "↑" else "↓"
}
