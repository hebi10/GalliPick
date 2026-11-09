package com.hebi.gallipick.domain

import org.junit.Assert.*
import org.junit.Test

class PhotoReviewInboxPolicyTest {
    private val group = SimilarPhotoGroup("similar:one", listOf(
        photo("a"), photo("b"), photo("c"),
    ))

    @Test fun keepsOnlyCurrentAccessibleFingerprintAndVersion() {
        val original = mark(group, "a", PhotoReviewDecision.KEEP)
        val modified = original.copy(fingerprint = "other")
        val missing = original.copy(uri = "inaccessible")
        val otherVolume = original.copy(volumeVersion = "old")
        val result = PhotoReviewInboxPolicy.valid(
            listOf(group), listOf(modified, missing, otherVolume, original), emptySet())
        assertEquals(listOf(original), result)
        assertTrue(PhotoReviewInboxPolicy.valid(emptyList(), listOf(original), emptySet()).isEmpty())
    }

    @Test fun cannotMarkLastPhotoCandidateOrProtectedPhotoCandidate() {
        val selected = listOf(mark(group, "a", PhotoReviewDecision.CANDIDATE),
            mark(group, "b", PhotoReviewDecision.CANDIDATE))
        assertFalse(PhotoReviewInboxPolicy.maySet(
            group, selected, "c", PhotoReviewDecision.CANDIDATE, emptySet()))
        assertTrue(PhotoReviewInboxPolicy.maySet(
            group, selected, "c", PhotoReviewDecision.KEEP, emptySet()))
        assertTrue(PhotoReviewInboxPolicy.maySet(
            group, selected, "c", PhotoReviewDecision.DEFER, emptySet()))
        val folder = setOf("DCIM/")
        assertFalse(PhotoReviewInboxPolicy.maySet(
            group, emptyList(), "a", PhotoReviewDecision.CANDIDATE, folder))
    }

    @Test fun pendingProgressAndResumeOnlyCountValidDecisions() {
        val notes = listOf(
            mark(group, "a", PhotoReviewDecision.KEEP),
            mark(group, "b", PhotoReviewDecision.CANDIDATE),
            mark(group, "c", PhotoReviewDecision.DEFER),
        )
        val summary = PhotoReviewInboxPolicy.summaries(listOf(group), notes, emptySet()).single()
        assertEquals(2, summary.decidedCount)
        assertEquals(1, summary.deferredCount)
        assertEquals(1, summary.candidateCount)
        assertEquals(1, summary.remainingCount)
        assertTrue(PhotoReviewInboxPolicy.pending(listOf(group), notes, emptySet()))
        val finished = notes.map {
            if (it.decision == PhotoReviewDecision.DEFER) it.copy(decision = PhotoReviewDecision.KEEP)
            else it
        }
        assertTrue(PhotoReviewInboxPolicy.summaries(listOf(group), finished, emptySet()).single().completed)
        assertFalse(PhotoReviewInboxPolicy.pending(listOf(group), finished, emptySet()))
    }

    @Test fun savedCandidateIsNeverAnAutomaticMediaSelection() {
        val onlyNote = mark(group, "a", PhotoReviewDecision.CANDIDATE)
        val current = PhotoReviewInboxPolicy.valid(listOf(group), listOf(onlyNote), emptySet())
        assertEquals(1, current.size)
        assertEquals(PhotoReviewDecision.CANDIDATE, current.single().decision)
        assertEquals(3, SimilarPhotoReviewPolicy.retained(group, emptySet()).size)
    }

    private fun mark(group: SimilarPhotoGroup, id: String, choice: PhotoReviewDecision): PhotoReviewEntry {
        val item = group.photos.first { it.item.uri == id }.item
        return PhotoReviewEntry(group.id, id, item.fingerprint, item.volumeVersion, choice, 1000L)
    }

    private fun photo(id: String) = SimilarPhoto(
        MediaItem(id, id.hashCode().toLong(), "primary", "${id}.jpg", MediaKind.PHOTO,
            "image/jpeg", "DCIM/", 1000, 3000, 2000, 0, 1, 1, 1),
        "0000000000000000",
    )
}
