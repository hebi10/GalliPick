package com.hebi.gallipick.data

import com.hebi.gallipick.domain.MediaKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaSnapshotLoaderTest {
    @Test fun categoryFailureCannotBecomeAnEmptySuccessfulSnapshot() = runBlocking {
        val failure = IOException("provider unavailable")
        val loader = MediaSnapshotLoader({ setOf("primary") }, { "epoch-1" }) { _, kind ->
            if (kind == MediaKind.VIDEO) throw failure
            emptyList()
        }
        val error = runCatching { loader.load() }.exceptionOrNull()
        assertTrue(error is CatalogReadException)
        assertSame(failure, error?.cause)
    }

    @Test fun enumerationFailureIsReportedInsteadOfEmptySuccess() = runBlocking {
        val failure = SecurityException("grant changed")
        val loader = MediaSnapshotLoader({ throw failure }, { "unused" }) { _, _ -> emptyList() }
        assertSame(failure, runCatching { loader.load() }.exceptionOrNull()?.cause)
    }

    @Test fun stableVolumeVersionsAreIncludedInTheSnapshot() = runBlocking {
        val loader = MediaSnapshotLoader({ setOf("sd", "primary") }, { "version-$it" }) { _, _ -> emptyList() }
        val result = loader.load()
        assertTrue(result.items.isEmpty())
        assertEquals(mapOf("primary" to "version-primary", "sd" to "version-sd"), result.volumeVersions)
    }

    @Test fun epochChangeDuringTraversalRejectsTheEntireSnapshot() = runBlocking {
        var versionReads = 0
        val loader = MediaSnapshotLoader({ setOf("primary") }, { "epoch-${versionReads++}" }) { _, _ -> emptyList() }
        assertTrue(runCatching { loader.load() }.exceptionOrNull() is CatalogReadException)
    }

    @Test fun cancellationIsNeverWrappedAsAReadFailure() = runBlocking {
        val cancellation = CancellationException("stopped")
        val loader = MediaSnapshotLoader({ setOf("primary") }, { "epoch" }) { _, _ -> throw cancellation }
        assertSame(cancellation, runCatching { loader.load() }.exceptionOrNull())
    }

    @Test fun onlyKnownMatchingEpochsCanKeepCachedDigests() {
        assertEquals(setOf("sd", "new"), MediaCachePolicy.invalidatedVolumes(
            mapOf("primary" to "stable", "sd" to "old", "removed" to "gone"),
            mapOf("primary" to "stable", "sd" to "reset", "new" to "first"),
        ))
    }
}
