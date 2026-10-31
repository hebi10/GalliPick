package com.hebi.gallipick.data

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MediaCachePolicyTest {
    @Test fun folderRuleIncludesDescendantsButNotSimilarNames() {
        assertTrue(MediaCachePolicy.isProtected("a", "DCIM/Camera/Trips/", emptySet(), listOf("DCIM/Camera/")))
        assertFalse(MediaCachePolicy.isProtected("a", "DCIM/Camera2/", emptySet(), listOf("DCIM/Camera/")))
        assertFalse(MediaCachePolicy.isProtected("a", "dcim/Camera/", emptySet(), listOf("DCIM/Camera/")))
    }

    @Test fun rootFolderProtectsOnlyFilesInRoot() {
        assertTrue(MediaCachePolicy.isProtected("a", "", emptySet(), listOf("")))
        assertFalse(MediaCachePolicy.isProtected("a", "DCIM/", emptySet(), listOf("")))
    }

    @Test fun folderNormalizationHasOneTrailingSeparator() {
        assertEquals("DCIM/Camera/", MediaCachePolicy.normalizeFolder("/DCIM/Camera/"))
        assertEquals("", MediaCachePolicy.normalizeFolder("/"))
    }

    @Test fun itemProtectionIsIndependentOfFolderRules() {
        assertTrue(MediaCachePolicy.isProtected("protected-uri", "Pictures/", setOf("protected-uri"), emptyList()))
        assertFalse(MediaCachePolicy.isProtected("other-uri", "Pictures/", setOf("protected-uri"), emptyList()))
    }

    @Test fun changedOrReplacedMediaCannotPassVersionCheck() {
        val original = media("a")
        assertTrue(MediaCachePolicy.sameVersion(original, original.copy(isFavorite = true)))
        assertFalse(MediaCachePolicy.sameVersion(original, original.copy(dateModifiedMs = 2_000)))
        assertFalse(MediaCachePolicy.sameVersion(original, original.copy(generationModified = 2)))
        assertFalse(MediaCachePolicy.sameVersion(original, original.copy(volume = "other-volume")))
        assertFalse(MediaCachePolicy.sameVersion(original, original.copy(id = 7)))
        assertFalse(MediaCachePolicy.sameVersion(original, original.copy(volumeVersion = "reset")))
    }

    @Test fun volumeResetRejectsUnchangedMetadataBeforeActionValidation() = runBlocking {
        val original = media("a")
        val result = MediaCachePolicy.freshUnchanged(listOf(original),
            mapOf("external_primary" to "old"), { "reset" }, { original })
        assertTrue(result.isEmpty())
    }

    @Test fun volumeResetDuringActionValidationRejectsPreviouslyReadRows() = runBlocking {
        val original = media("a")
        var version = "old"
        val result = MediaCachePolicy.freshUnchanged(listOf(original),
            mapOf("external_primary" to "old"), { version }, {
                version = "reset"
                original
            })
        assertTrue(result.isEmpty())
    }

    @Test fun unchangedVolumeAndFingerprintCanPassActionValidation() = runBlocking {
        val original = media("a")
        val result = MediaCachePolicy.freshUnchanged(listOf(original),
            mapOf("external_primary" to "same"), { "same" }, { original })
        assertEquals(listOf(original), result)
    }

    @Test fun selectionEpochCannotBeReplacedByANewerSuccessfulCatalogEpoch() = runBlocking {
        val selected = media("a").copy(volumeVersion = "selected-epoch")
        val fresh = selected.copy(volumeVersion = "refreshed-epoch")
        val result = MediaCachePolicy.freshUnchanged(listOf(selected),
            mapOf("external_primary" to "refreshed-epoch"), { "refreshed-epoch" }, { fresh })
        assertTrue(result.isEmpty())
    }

    @Test fun staleFailedOrInvalidHashesCannotProveDuplicates() {
        val a = media("a")
        val b = media("b")
        assertTrue(MediaCachePolicy.duplicateGroups(listOf(a, b), mapOf(proof(a),
            proof(b, fingerprint = "outdated"))).isEmpty())
        assertTrue(MediaCachePolicy.duplicateGroups(listOf(a, b), mapOf(proof(a),
            proof(b, digest = null))).isEmpty())
        assertTrue(MediaCachePolicy.duplicateGroups(listOf(a, b), mapOf(
            proof(a, "z".repeat(64)), proof(b, "z".repeat(64)))).isEmpty())
    }

    @Test fun trashedAndDifferentSizeFilesDoNotJoinDuplicateGroup() {
        val a = media("a")
        val b = media("b").copy(isTrashed = true)
        val c = media("c").copy(sizeBytes = 8)
        val items = listOf(a, b, c)
        assertTrue(MediaCachePolicy.duplicateGroups(items,
            items.associate { proof(it) }).isEmpty())
    }

    @Test fun exactCurrentHashesFormAGroupInCaptureOrder() {
        val a = media("a").copy(dateTakenMs = 3_000)
        val b = media("b").copy(dateTakenMs = 1_000)
        val result = MediaCachePolicy.duplicateGroups(listOf(a, b),
            mapOf(proof(a), proof(b)))
        assertEquals(1, result.size)
        assertEquals(listOf("b", "a"), result.single().items.map { it.uri })
        assertEquals(HASH, result.single().hash)
    }

    private fun media(uri: String) = MediaItem(
        uri = uri, id = 1, volume = "external_primary", displayName = "$uri.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg", relativePath = "DCIM/Camera/",
        sizeBytes = 12, width = 2, height = 3, durationMs = 0,
        dateTakenMs = 1_000, dateAddedMs = 1_000, dateModifiedMs = 1_000,
    )

    private fun proof(item: MediaItem, digest: String? = HASH, fingerprint: String = item.fingerprint) =
        (item.uri to fingerprint) to digest

    private companion object { const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" }
}
