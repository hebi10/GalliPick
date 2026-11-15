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

        val target = CloudSpacePolicy.recommend(files, emptyList(), CloudSpaceTarget.FIVE_GB, 200 * DAY)
        assertEquals(listOf(first, second), target.candidates.map { it.file })
        assertEquals(7_000_000_000L, target.potentialBytes)
        assertEquals(0L, target.verifiedBackupBytes)
        val maximum = CloudSpacePolicy.recommend(files, emptyList(), CloudSpaceTarget.MAXIMUM, 200 * DAY)
        assertEquals(9_000_000_000L, maximum.potentialBytes)
    }

    @Test
    fun listsVerifiedOriginalsBeforeOldVideosAndOtherOldMedia() {
        val oldPhoto = media(1, bytes = 500)
        val oldVideo = media(2, name = "older.mp4", mimeType = "video/mp4", bytes = 400).copy(kind = MediaKind.VIDEO)
        val smallerOldVideo = oldVideo.copy(uri = "video-3", id = 3, sizeBytes = 300)
        val recent = media(4, bytes = 200).copy(dateTakenMs = 199 * DAY)
        val verified = media(5, bytes = 100).copy(dateTakenMs = 198 * DAY)
        val files = listOf(recent, oldPhoto, smallerOldVideo, oldVideo, verified)

        val result = CloudSpacePolicy.recommend(files, listOf(verified), CloudSpaceTarget.MAXIMUM, 200 * DAY)

        assertEquals(listOf(verified, oldVideo, smallerOldVideo, oldPhoto), result.candidates.map { it.file })
        assertEquals(listOf(true, false, false, false), result.candidates.map { it.verifiedBackup })
        assertEquals(1_300L, result.potentialBytes)
        assertEquals(100L, result.verifiedBackupBytes)
    }

    @Test
    fun aChangedOldFileIsOnlyAPotentialCandidateAfterVerification() {
        val original = media(1, bytes = 200)
        val changed = original.copy(generationModified = 3)

        val result = CloudSpacePolicy.recommend(listOf(changed), listOf(original), CloudSpaceTarget.MAXIMUM, 200 * DAY)

        assertEquals(listOf(changed), result.candidates.map { it.file })
        assertFalse(result.candidates.single().verifiedBackup)
        assertEquals(200L, result.potentialBytes)
        assertEquals(0L, result.verifiedBackupBytes)
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
