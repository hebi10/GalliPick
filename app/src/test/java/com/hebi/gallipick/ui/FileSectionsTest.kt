package com.hebi.gallipick.ui

import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.GalleryEntry
import com.hebi.gallipick.data.cloud.GalleryEntryKey
import com.hebi.gallipick.domain.DateField
import com.hebi.gallipick.domain.FileGroupMode
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.SortDirection
import com.hebi.gallipick.domain.SortField
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSectionsTest {
    private val recent = media(1, "DCIM/Camera/", 1_760_000_000_000L, 2L * 1024 * 1024)
    private val earlier = media(2, "Pictures/", 1_730_000_000_000L, 700L * 1024 * 1024)

    @Test fun manualGridCountShowsItsSafeResolvedColumnCount() {
        assertEquals(6, resolvedFileGridColumns(6, 612.dp, 1f, false))
        assertEquals(3, resolvedFileGridColumns(6, 336.dp, 1f, false))
        assertEquals(2, resolvedFileGridColumns(6, 200.dp, 1f, false))
        assertEquals(2, resolvedFileGridColumns(6, 336.dp, 2f, false))
        assertEquals(3, resolvedFileGridColumns(3, 600.dp, 1f, false))
    }

    @Test fun automaticallyGroupsByCaptureDateAndStartsWithNewest() {
        val groups = FileSections.local(listOf(earlier, recent), MediaFilter())
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.isDate })
        assertEquals(recent.uri, groups[0].items.single().uri)
        assertEquals(earlier.uri, groups[1].items.single().uri)
    }

    @Test fun sortingBySizeGroupsIntoSizeBandsWithoutDroppingPhotos() {
        val groups = FileSections.local(listOf(recent, earlier), MediaFilter(sortField = SortField.SIZE))
        assertEquals(listOf("100MB~1GB", "10MB 미만"), groups.map { it.title })
        assertEquals(setOf(recent.uri, earlier.uri), groups.flatMap { it.items }.map { it.uri }.toSet())
    }

    @Test fun explicitModesRespectFolderAndNoneAndLegacyDatePreference() {
        val inputs = listOf(recent, earlier)
        assertEquals(listOf("DCIM/Camera", "Pictures"), FileSections.local(inputs,
            MediaFilter(groupMode = FileGroupMode.FOLDER)).map { it.title })
        assertTrue(FileSections.local(inputs, MediaFilter(groupMode = FileGroupMode.NONE)).isEmpty())
        assertTrue(FileSections.local(inputs, MediaFilter(sortField = SortField.SIZE,
            groupByDate = true)).all { it.isDate })
    }

    @Test fun cloudWithoutCaptureDateStaysUnderUnknownInsteadOfUsingUploadDate() {
        val original = CloudOriginal(
            "cloud-id", "cloud.jpg", "image/jpeg", 1000L, "f".repeat(64),
            verifiedAtMs = 1_760_000_000_000L,
        )
        val entry = GalleryEntry(GalleryEntryKey.Cloud("account", original.uploadId), cloud = original)
        val result = FileSections.unified(listOf(entry), MediaFilter(dateField = DateField.CAPTURED))
        assertEquals("정보 없음", result.single().title)
        assertEquals(original.uploadId, result.single().items.single().cloud?.uploadId)
    }

    @Test fun existingKindsRemainSeparatedWhenRequested() {
        val video = earlier.copy(kind = MediaKind.VIDEO, mimeType = "video/mp4")
        val result = FileSections.local(listOf(video, recent),
            MediaFilter(groupMode = FileGroupMode.KIND, sortDirection = SortDirection.DESCENDING))
        assertEquals(listOf("사진", "동영상"), result.map { it.title })
        assertFalse(result.any { it.items.isEmpty() })
    }

    private fun media(id: Long, folder: String, captured: Long, size: Long) = MediaItem(
        uri = "content://fixture/$id", id = id, volume = "external_primary",
        displayName = "photo-$id.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = folder, sizeBytes = size, width = 500, height = 500, durationMs = 0,
        dateTakenMs = captured, dateAddedMs = captured, dateModifiedMs = captured,
    )
}
