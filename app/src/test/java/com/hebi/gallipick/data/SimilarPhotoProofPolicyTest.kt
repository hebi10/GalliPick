package com.hebi.gallipick.data

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.SimilarPhoto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarPhotoProofPolicyTest {
    private val reviewed = MediaItem(
        uri = "content://media/external/images/media/7",
        id = 7L,
        volume = "external_primary",
        displayName = "original.jpg",
        kind = MediaKind.PHOTO,
        mimeType = "image/jpeg",
        relativePath = "DCIM/Camera/",
        sizeBytes = 4096L,
        width = 100,
        height = 80,
        durationMs = 0,
        dateTakenMs = 1000L,
        dateAddedMs = 1000L,
        dateModifiedMs = 1000L,
        generationModified = 12L,
        volumeVersion = "volume-epoch-1",
    )
    private val phash = "0123456789abcdef"
    private val photo = SimilarPhoto(reviewed, phash)
    private val proof = CachedPhotoHash(
        reviewed.uri, reviewed.fingerprint, reviewed.volumeVersion, phash,
    )

    @Test fun unchangedReviewedOriginalAcceptsTheExactCachedSignature() {
        assertTrue(cachedSimilarProofMatches(photo, proof))
    }

    @Test fun missingOrFailedCacheCannotProveSurvival() {
        assertFalse(cachedSimilarProofMatches(photo, null))
        assertFalse(cachedSimilarProofMatches(photo, proof.copy(phash = null)))
        assertFalse(cachedSimilarProofMatches(photo, proof.copy(phash = "fedcba9876543210")))
    }

    @Test fun staleUriFingerprintAndVolumeEpochAreAlwaysRejected() {
        assertFalse(cachedSimilarProofMatches(photo, proof.copy(uri = "content://other")))
        assertFalse(cachedSimilarProofMatches(photo, proof.copy(fingerprint = "outdated")))
        assertFalse(cachedSimilarProofMatches(photo, proof.copy(volumeVersion = "volume-epoch-2")))
        assertFalse(cachedSimilarProofMatches(photo.copy(item = reviewed.copy(sizeBytes = 4097L)), proof))
        assertFalse(cachedSimilarProofMatches(photo.copy(item = reviewed.copy(generationModified = 13L)), proof))
        assertFalse(cachedSimilarProofMatches(photo.copy(item = reviewed.copy(volumeVersion = "volume-epoch-2")), proof))
        assertFalse(cachedSimilarProofMatches(photo.copy(item = reviewed.copy(uri = "content://reused")), proof))
    }
}
