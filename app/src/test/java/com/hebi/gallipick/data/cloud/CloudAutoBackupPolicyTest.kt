package com.hebi.gallipick.data.cloud

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAutoBackupPolicyTest {
    @Test
    fun onlyTheExactlyOptedInFolderAndItsVolumeCanBeSelected() {
        val folder = CloudAutoBackupFolder("external_primary", "DCIM/Camera/")
        val allowed = item(1)
        val candidates = listOf(
            item(2).copy(relativePath = "DCIM/Camera/Private/"),
            item(3).copy(volume = "sd-card"),
            item(4).copy(relativePath = "DCIM/camera/"),
            item(5).copy(isTrashed = true),
            item(6).copy(volumeVersion = ""),
            item(7).copy(sizeBytes = 64L * 1024 * 1024 + 1),
            item(8).copy(mimeType = "image/*"),
            allowed,
        )

        assertEquals(listOf(allowed), cloudAutoBackupCandidates(folder, candidates, emptySet()))
    }

    @Test
    fun previouslyHandledVersionsAreSkippedButChangedOriginalsAreEligible() {
        val folder = CloudAutoBackupFolder("external_primary", "DCIM/Camera/")
        val handled = item(1)
        val changed = handled.copy(generationModified = 99)
        val previousVolume = handled.copy(volumeVersion = "previous")
        val originalVersionId = cloudAutoBackupVersion(handled)

        assertNotEquals(originalVersionId, cloudAutoBackupVersion(changed))
        assertNotEquals(originalVersionId, cloudAutoBackupVersion(previousVolume))
        assertEquals(emptyList<MediaItem>(), cloudAutoBackupCandidates(folder, listOf(handled), setOf(originalVersionId)))
        assertEquals(listOf(changed), cloudAutoBackupCandidates(folder, listOf(changed), setOf(originalVersionId)))
    }

    @Test
    fun eachRunPicksAtMostTwoDistinctMostRecentUnprocessedOriginals() {
        val folder = CloudAutoBackupFolder("external_primary", "DCIM/Camera/")
        val newest = item(8)
        val candidates = listOf(item(2), newest, item(5), newest, item(9))

        assertEquals(
            listOf(newest, item(5)),
            cloudAutoBackupCandidates(folder, candidates, setOf(cloudAutoBackupVersion(item(9)))),
        )
    }

    @Test
    fun anotherAccountOrOldWorkCannotUseConsent() {
        val consent = CloudAutoBackupConsent(autoBackupOwnerKey("owner-one"), "current-lease",
            CloudAutoBackupFolder("external_primary", "DCIM/Camera/"))

        assertTrue(consent.permits("owner-one", "current-lease"))
        assertFalse(consent.permits("owner-two", "current-lease"))
        assertFalse(consent.permits(null, "current-lease"))
        assertFalse(consent.permits("owner-one", "obsolete-lease"))
    }

    @Test
    fun aFileMovedOutOfTheOptedInFolderCannotBeTransferredWithAnUnchangedFingerprint() {
        val original = item(1)
        val folder = CloudAutoBackupFolder("external_primary", "DCIM/Camera/")
        val moved = original.copy(relativePath = "DCIM/Private/")

        assertEquals(original.fingerprint, moved.fingerprint)
        assertTrue(matchesCloudBackupOriginal(original, original, folder))
        assertFalse(matchesCloudBackupOriginal(original, moved, folder))
        assertFalse(matchesCloudBackupOriginal(original, moved))
    }

    private fun item(id: Long) = MediaItem(
        uri = "content://media/external_primary/images/media/$id", id = id,
        volume = "external_primary", displayName = "$id.jpg", kind = MediaKind.PHOTO,
        mimeType = "image/jpeg", relativePath = "DCIM/Camera/", sizeBytes = 200,
        width = 100, height = 100, durationMs = 0, dateTakenMs = id, dateAddedMs = id,
        dateModifiedMs = id, generationModified = id, volumeVersion = "current",
    )
}
