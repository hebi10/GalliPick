package com.hebi.gallipick.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpacePolicyTest {
    @Test
    fun excludesProtectedFilesSelectedKeepersAndFilesWithCompanions() {
        val portrait = media(1, name = "portrait.jpg")
        val raw = media(2, name = "portrait.DNG", mimeType = "image/x-adobe-dng")
        val favorite = media(3).copy(isFavorite = true)
        val protectedFile = media(4).copy(isProtected = true)
        val inProtectedFolder = media(5).copy(relativePath = "DCIM/Keep/2020/")
        val trashed = media(6).copy(isTrashed = true)
        val keeper = media(7)
        val eligible = media(8)

        val result = CloudSpacePolicy.eligible(
            listOf(portrait, raw, favorite, protectedFile, inProtectedFolder, trashed, keeper, eligible, eligible),
            protectedFolders = setOf("DCIM/Keep"), keepUris = setOf(keeper.uri),
        )

        assertEquals(listOf(eligible), result)
    }

    @Test
    fun includesOnlyCloudSizesUnderScanLimitsAndPrefersOlderLargeVideos() {
        val photo = media(1, name = "photo.jpg", bytes = 50)
        val video = media(2, name = "large.mp4", mimeType = "video/mp4", bytes = 60).copy(kind = MediaKind.VIDEO)
        val tooBig = media(3, bytes = 500)
        val noBackupSize = media(4, bytes = 70)

        val candidates = CloudSpacePolicy.scanCandidates(
            listOf(photo, tooBig, noBackupSize, video), cloudSizes = setOf(50, 60, 500),
            nowMs = 200 * DAY, maxBytes = 115, maxFiles = 2,
        )

        assertEquals(listOf(video, photo), candidates)
    }

    @Test
    fun selectsWholeFilesUpToTheRequestedTargetAndDoesNotCountRepeatedUris() {
        val first = media(1, bytes = 4_000_000_000L)
        val second = media(2, bytes = 3_000_000_000L)
        val third = media(3, bytes = 2_000_000_000L)
        val files = listOf(first, first, second, third)

        val target = CloudSpacePolicy.recommend(files, CloudSpaceTarget.FIVE_GB)
        assertEquals(listOf(first, second), target.files)
        assertEquals(7_000_000_000L, target.bytes)
        assertEquals(9_000_000_000L, CloudSpacePolicy.recommend(files, CloudSpaceTarget.MAXIMUM).bytes)
    }

    @Test
    fun aChangedFingerprintOrVolumeCannotReuseAnEarlierByteMatch() {
        val original = media(1)
        assertTrue(CloudSpacePolicy.sameOriginal(original, original.copy()))
        assertFalse(CloudSpacePolicy.sameOriginal(original, original.copy(generationModified = 3)))
        assertFalse(CloudSpacePolicy.sameOriginal(original, original.copy(volumeVersion = "another-volume")))
        assertFalse(CloudSpacePolicy.sameOriginal(original, original.copy(isFavorite = true)))
        assertFalse(CloudSpacePolicy.sameOriginal(original, original.copy(relativePath = "DCIM/Private/")))
    }

    private fun media(id: Long, name: String = "item-$id.jpg", mimeType: String = "image/jpeg", bytes: Long = 42) =
        MediaItem("content://media/external_primary/images/media/$id", id, "external_primary", name, MediaKind.PHOTO,
            mimeType, "DCIM/Camera/", bytes, 0, 0, 0, DAY, DAY, DAY,
            generationModified = 2, volumeVersion = "current")

    private companion object { const val DAY = 86_400_000L }
}
