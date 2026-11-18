package com.hebi.gallipick.data

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogDiffTest {
    private fun media(uri: String, generation: Long = 1L): CachedMedia =
        CachedMedia.from(MediaItem(
            uri = uri, id = uri.length.toLong(), volume = "primary",
            displayName = "$uri.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
            relativePath = "DCIM/Camera/", sizeBytes = 2048L, width = 100, height = 80,
            durationMs = 0L, dateTakenMs = 1000L, dateAddedMs = 1000L,
            dateModifiedMs = 1000L, generationModified = generation,
        ))

    @Test fun unchangedCatalogNeedsNoMediaWrites() {
        val before = listOf(media("a"), media("b"))
        assertTrue(MediaCatalogDiff.between(before, before.reversed()).isEmpty)
    }

    @Test fun removedUriAndModifiedRowAreTrackedIndependently() {
        val a = media("a")
        val b = media("b")
        val c = media("c")
        val revised = b.copy(displayName = "renamed.jpg")
        val delta = MediaCatalogDiff.between(listOf(a, b, c), listOf(a, revised, media("new")))
        assertEquals(setOf("b", "new"), delta.changed.map { it.uri }.toSet())
        assertEquals(listOf("c"), delta.removedUris)
        assertTrue(delta.invalidatedProofUris.isEmpty())
    }

    @Test fun existingUriWithNewContentOrProviderIdentityLosesItsProofs() {
        val old = media("same")
        for (replacement in listOf(
            old.copy(fingerprint = "changed"),
            old.copy(id = old.id + 1),
            old.copy(volume = "sd"),
            old.copy(mimeType = "image/png"),
            old.copy(width = old.width + 1),
            old.copy(kind = MediaKind.VIDEO.name),
        )) {
            val delta = MediaCatalogDiff.between(listOf(old), listOf(replacement))
            assertEquals(listOf(old.uri), delta.invalidatedProofUris)
        }
    }

    @Test fun duplicateUriInputKeepsLastRowAsRoomReplaceWould() {
        val original = media("same")
        val replacement = original.copy(displayName = "last.jpg")
        val delta = MediaCatalogDiff.between(emptyList(), listOf(original, replacement))
        assertEquals(listOf(replacement), delta.changed)
        assertFalse(delta.isEmpty)
    }

    @Test fun veryLargeUnchangedSnapshotDoesNotScheduleAnyUpdates() {
        val snapshot = (0 until 50_000).map { media("item-$it") }
        val diff = MediaCatalogDiff.between(snapshot, snapshot)
        assertTrue(diff.isEmpty)
    }
}
