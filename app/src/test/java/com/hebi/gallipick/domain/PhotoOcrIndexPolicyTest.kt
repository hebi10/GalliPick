package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoOcrIndexPolicyTest {
    @Test fun koreanSearchMatchesNormalizedText() {
        val item = photo("a")
        val entry = record(item, "2026년 여행 영수증   카페 결제")
        assertEquals(listOf(item), PhotoOcrIndexPolicy.searchable(
            listOf(item), listOf(entry), " 여행   카페 "))
        assertTrue(PhotoOcrIndexPolicy.snippet(entry.recognizedText, "카페").contains("카페"))
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item), listOf(entry), "호텔").isEmpty())
    }

    @Test fun refusesStaleFingerprintVolumeMissingPermissionAndFailedResults() {
        val item = photo("a")
        val valid = record(item, "주소 서울")
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item), listOf(valid), "서울").isNotEmpty())
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item.copy(sizeBytes = 200)),
            listOf(valid), "서울").isEmpty())
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item.copy(volumeVersion = "other")),
            listOf(valid), "서울").isEmpty())
        assertTrue(PhotoOcrIndexPolicy.searchable(emptyList(), listOf(valid), "서울").isEmpty())
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item),
            listOf(valid.copy(status = OcrResultStatus.FAILED)), "서울").isEmpty())
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item),
            listOf(valid.copy(status = OcrResultStatus.NO_TEXT)), "서울").isEmpty())
    }

    @Test fun blankSearchDoesNotExposeEntireMediaCatalog() {
        val item = photo("a")
        assertTrue(PhotoOcrIndexPolicy.searchable(listOf(item), listOf(record(item, "개인정보")), "").isEmpty())
    }

    private fun photo(uri: String) = MediaItem(
        uri, 1, "primary", uri + ".jpg", MediaKind.PHOTO, "image/jpeg", "Pictures/",
        1000, 1200, 800, 0, 1, 1, 1, volumeVersion = "one",
    )
    private fun record(item: MediaItem, text: String) =
        PhotoOcrEntry(item.uri, item.fingerprint, item.volumeVersion, text, OcrResultStatus.SUCCESS)
}
