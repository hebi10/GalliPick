package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderStoragePolicyTest {
    @Test fun countsEachVisibleUriOnceAndLeavesSubfoldersSeparate() {
        val entries = listOf(
            photo("a", "DCIM/Camera/", 100),
            photo("b", "DCIM/Camera/", 50, favorite = true),
            photo("a", "DCIM/Camera/", 100),
            photo("c", "DCIM/Camera/Trips/", 80, protected = true),
            photo("d", "Download/", 20, trashed = true),
        )
        val result = FolderStoragePolicy.summarize(entries)
        assertEquals(3, result.totalCount)
        assertEquals(230L, result.totalBytes)
        assertEquals(listOf("DCIM/Camera/", "DCIM/Camera/Trips/"), result.folders.map { it.path })
        assertEquals(2, result.folders[0].count)
        assertEquals(150L, result.folders[0].sizeBytes)
        assertEquals(1, result.folders[1].count)
        assertEquals(80L, result.folders[1].sizeBytes)
        assertEquals(150f / 230f, FolderStoragePolicy.fraction(result.folders[0], result.totalBytes), 0.0001f)
    }

    @Test fun unknownPathIsCountedWithoutPretendingToBeNavigable() {
        val result = FolderStoragePolicy.summarize(listOf(photo("a", "", 12)))
        assertEquals(12L, result.totalBytes)
        assertEquals("폴더 정보 없음", result.folders.single().displayName)
        assertFalse(result.folders.single().canBrowse)
    }

    @Test fun emptyAndZeroSizedPhotosDoNotProduceInvalidPercentages() {
        val empty = FolderStoragePolicy.summarize(emptyList())
        assertEquals(0L, empty.totalBytes)
        assertTrue(empty.folders.isEmpty())
        val zero = FolderStoragePolicy.summarize(listOf(photo("zero", "Pictures/", 0)))
        assertEquals(0f, FolderStoragePolicy.fraction(zero.folders.single(), zero.totalBytes), 0f)
    }

    @Test fun sumSaturatesWithoutOverflow() {
        val summary = FolderStoragePolicy.summarize(
            listOf(photo("a", "Pictures/", Long.MAX_VALUE), photo("b", "Download/", 42))
        )
        assertEquals(Long.MAX_VALUE, summary.totalBytes)
        assertEquals(Long.MAX_VALUE, summary.folders.first().sizeBytes)
        assertTrue(FolderStoragePolicy.fraction(summary.folders.first(), summary.totalBytes) in 0f..1f)
    }

    @Test fun protectedAndFavoriteFilesContributeToUsageButDoNotGetMarkedForDeletion() {
        val summary = FolderStoragePolicy.summarize(listOf(
            photo("favorite", "Download/", 2, favorite = true),
            photo("protected", "Download/", 3, protected = true),
        ))
        assertEquals(5L, summary.totalBytes)
        assertEquals(2, summary.folders.single().count)
    }

    private fun photo(
        id: String, path: String, bytes: Long,
        favorite: Boolean = false, protected: Boolean = false, trashed: Boolean = false,
    ) = MediaItem(
        uri = "content://media/external/images/media/$id", id = id.hashCode().toLong(),
        volume = "external_primary", displayName = "$id.jpg", kind = MediaKind.PHOTO,
        mimeType = "image/jpeg", relativePath = path, sizeBytes = bytes,
        width = 100, height = 100, durationMs = 0,
        dateTakenMs = 1L, dateAddedMs = 1L, dateModifiedMs = 1L,
        isFavorite = favorite, isProtected = protected, isTrashed = trashed,
    )
}
