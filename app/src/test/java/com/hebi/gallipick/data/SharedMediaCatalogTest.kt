package com.hebi.gallipick.data

import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMediaCatalogTest {
    @Test fun hashBurstsEmitTheLatestCheckpointWithoutDelayingWrites() = runTest {
        val checkpoints = MutableStateFlow(0)
        val observed = mutableListOf<Int>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            checkpoints.coalesceUpdates(250).collect { observed += it }
        }
        runCurrent()
        checkpoints.value = 1
        runCurrent()
        checkpoints.value = 2
        runCurrent()
        assertEquals(2, checkpoints.value)
        assertEquals(listOf(0), observed)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf(0, 2), observed)
    }

    @Test fun mediaTrashAndDuplicateCollectorsShareOneRoomCatalogObservation() = runTest {
        var observations = 0
        val rows = MutableStateFlow(listOf(media("a"), media("b")))
        val catalog = SharedMediaCatalog(
            rows = rows.onStart { observations++ },
            protection = MutableStateFlow(emptyList()), folders = MutableStateFlow(emptyList()),
            epochs = MutableStateFlow(listOf(MediaVolumeVersion("primary", "one"))),
            hashes = MutableStateFlow(emptyList()), scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { catalog.media.collect() }
        backgroundScope.launch { catalog.trashed.collect() }
        backgroundScope.launch { catalog.duplicates.collect() }
        runCurrent()
        assertEquals(1, observations)
    }

    @Test fun readFailureHidesEveryCatalogButRetainsTheCheckpointSourceForRetry() = runTest {
        val rows = MutableStateFlow(listOf(media("a"), media("b")))
        val hashes = MutableStateFlow(rows.value.map { CachedHash(it.uri, it.fingerprint, HASH, "one") })
        val catalog = SharedMediaCatalog(rows, MutableStateFlow(emptyList()), MutableStateFlow(emptyList()),
            MutableStateFlow(listOf(MediaVolumeVersion("primary", "one"))), hashes,
            backgroundScope, StandardTestDispatcher(testScheduler))
        var visible = emptyList<MediaItem>()
        var duplicates = 0
        backgroundScope.launch { catalog.media.collect { visible = it } }
        backgroundScope.launch { catalog.duplicates.collect { duplicates = it.size } }
        runCurrent()
        catalog.markReady()
        runCurrent()
        assertEquals(2, visible.size)
        assertEquals(1, duplicates)
        catalog.markFailed("retry")
        runCurrent()
        assertTrue(visible.isEmpty())
        assertEquals(0, duplicates)
        assertEquals(2, hashes.value.size)
        assertFalse(catalog.ready.value)
        assertEquals("retry", catalog.error.value)
        catalog.markRefreshing()
        catalog.markReady()
        runCurrent()
        assertEquals(2, visible.size)
        assertNull(catalog.error.value)
    }

    @Test fun staleEpochHashesCannotReappearWhileHashUpdatesAreCoalesced() = runTest {
        val rows = MutableStateFlow(listOf(media("a"), media("b")))
        val hashes = MutableStateFlow(rows.value.map { CachedHash(it.uri, it.fingerprint, HASH, "one") })
        val epochs = MutableStateFlow(listOf(MediaVolumeVersion("primary", "one")))
        val catalog = SharedMediaCatalog(rows, MutableStateFlow(emptyList()), MutableStateFlow(emptyList()),
            epochs, hashes, backgroundScope, StandardTestDispatcher(testScheduler))
        var groups = 0
        backgroundScope.launch { catalog.duplicates.collect { groups = it.size } }
        runCurrent()
        catalog.markReady()
        runCurrent()
        assertEquals(1, groups)
        catalog.markRefreshing()
        epochs.value = listOf(MediaVolumeVersion("primary", "two"))
        hashes.value = emptyList()
        runCurrent()
        catalog.markReady()
        runCurrent()
        assertEquals(0, groups)
    }

    @Test fun verifiedSnapshotWaitsForAReadyMatchingCatalogAfterEpochAndContentChange() = runTest {
        val rows = MutableStateFlow(listOf(media("a"), media("b")))
        val hashes = MutableStateFlow(rows.value.map { CachedHash(it.uri, it.fingerprint, HASH, "one") })
        val epochs = MutableStateFlow(listOf(MediaVolumeVersion("primary", "one")))
        val catalog = SharedMediaCatalog(rows, MutableStateFlow(emptyList()), MutableStateFlow(emptyList()),
            epochs, hashes, backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { catalog.duplicates.collect() }
        runCurrent()
        catalog.markReady()
        val original = catalog.snapshot()
        assertEquals(1, original.duplicates.size)
        catalog.markRefreshing()
        val refreshed = backgroundScope.async { catalog.snapshot() }
        runCurrent()
        assertFalse(refreshed.isCompleted)
        rows.value = listOf(media("c"), media("d").copy(isTrashed = true))
        epochs.value = listOf(MediaVolumeVersion("primary", "two"))
        hashes.value = emptyList()
        runCurrent()
        catalog.awaitSnapshot(rows.value, mapOf("primary" to "two"))
        assertFalse(refreshed.isCompleted)
        catalog.markReady()
        runCurrent()
        val result = refreshed.await()
        assertEquals(listOf("c"), result.items.map { it.uri })
        assertEquals(listOf("d"), result.trashed.map { it.uri })
        assertEquals("two", result.items.single().volumeVersion)
        assertTrue(result.duplicates.isEmpty())
    }

    private fun media(uri: String) = CachedMedia.from(MediaItem(
        uri, 1, "primary", "$uri.jpg", MediaKind.PHOTO, "image/jpeg", "Pictures/", 12,
        2, 3, 0, 1_000, 1_000, 1_000,
    ))

    private companion object { const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" }
}
