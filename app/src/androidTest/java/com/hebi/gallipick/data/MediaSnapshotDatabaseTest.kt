package com.hebi.gallipick.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSnapshotDatabaseTest {
    private lateinit var database: MediaDatabase
    private val dao get() = database.mediaDao()

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, MediaDatabase::class.java,
        ).build()
    }

    @After fun closeDatabase() { database.close() }

    @Test fun epochChangeInvalidatesOnlyThatVolumesHashesEvenWhenFingerprintIsUnchanged() = runBlocking {
        val rows = listOf(media("a", "primary"), media("b", "sd"))
        dao.replaceAccessible(rows, mapOf("primary" to "one", "sd" to "one"))
        rows.forEach { dao.putHash(CachedHash(it.uri, it.fingerprint, HASH, "one")) }
        dao.replaceAccessible(rows, mapOf("primary" to "two", "sd" to "one"))
        assertEquals(listOf("b"), dao.allHashes().map { it.uri })
        assertEquals(mapOf("primary" to "two", "sd" to "one"), dao.allVolumeVersions().associate { it.volume to it.version })
    }

    @Test fun photoHashIsSeparateAndInvalidatedOnlyForChangedEpoch() = runBlocking {
        val rows = listOf(media("a", "primary"), media("b", "sd"))
        dao.replaceAccessible(rows, mapOf("primary" to "one", "sd" to "one"))
        rows.forEach {
            dao.putHash(CachedHash(it.uri, it.fingerprint, HASH, "one"))
            dao.putPhotoHash(CachedPhotoHash(it.uri, it.fingerprint, "one", "1234567890abcdef"))
        }
        dao.setPhotoAnalysisSession(PhotoAnalysisSession(token = "photo-test", phase = "COMPLETE", total = 2, completed = 2))
        dao.replaceAccessible(rows, mapOf("primary" to "two", "sd" to "one"))
        assertEquals(listOf("b"), dao.allPhotoHashes().map { it.uri })
        assertEquals(listOf("b"), dao.allHashes().map { it.uri })
        assertEquals("photo-test", dao.photoAnalysisSession()?.token)
        assertEquals(2, dao.photoAnalysisSession()?.completed)
    }

    @Test fun reviewChoicesPersistAcrossCatalogRefreshAndClearSeparatelyFromProtection() = runBlocking {
        val item = media("review", "primary")
        dao.replaceAccessible(listOf(item), mapOf("primary" to "one"))
        dao.protect(ProtectedMedia(item.uri))
        val saved = CachedReviewChoice(
            "similar:test", item.uri, item.fingerprint, "one", "DEFER", 1000L)
        dao.putReviewChoice(saved)
        dao.replaceAccessible(listOf(item), mapOf("primary" to "two"))
        assertEquals(listOf(saved), dao.allReviewChoices())
        assertEquals(1, dao.clearReviewChoices())
        assertTrue(dao.allReviewChoices().isEmpty())
        assertEquals(listOf(item), dao.allMedia())
        assertEquals(listOf(item.uri), dao.allProtection().map { it.uri })
    }

    @Test fun sharpnessAndExposureArePersistedAndInvalidatedWithPhotoFingerprint() = runBlocking {
        val item = media("sharpness", "primary")
        dao.replaceAccessible(listOf(item), mapOf("primary" to "one"))
        dao.putPhotoHash(CachedPhotoHash(item.uri, item.fingerprint, "one", "0123456789abcdef", 7.25, 0.82))
        assertEquals(7.25, dao.allPhotoHashes().single().sharpnessScore!!, 0.001)
        assertEquals(0.82, dao.allPhotoHashes().single().exposureScore!!, 0.001)
        dao.replaceAccessible(listOf(item), mapOf("primary" to "two"))
        assertTrue(dao.allPhotoHashes().isEmpty())
    }

    @Test fun photoHashCacheNeverAltersProtectionOrExactDuplicateCache() = runBlocking {
        val row = media("protected", "primary")
        dao.replaceAccessible(listOf(row), mapOf("primary" to "one"))
        dao.protect(ProtectedMedia(row.uri))
        dao.putHash(CachedHash(row.uri, row.fingerprint, HASH, "one"))
        dao.putPhotoHash(CachedPhotoHash(row.uri, row.fingerprint, "one", "abcdef0123456789"))
        dao.clearPhotoHashes()
        assertTrue(dao.allPhotoHashes().isEmpty())
        assertEquals(1, dao.allHashes().size)
        assertEquals(listOf(row.uri), dao.allProtection().map { it.uri })
    }

    @Test fun failedReplacementRollsBackRowsEpochsAndCheckpoints() = runBlocking {
        val original = media("a", "primary")
        dao.replaceAccessible(listOf(original), mapOf("primary" to "one"))
        val hash = CachedHash(original.uri, original.fingerprint, HASH, "one")
        dao.putHash(hash)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_epoch BEFORE INSERT ON media_volume_version
            WHEN NEW.version = 'broken' BEGIN SELECT RAISE(ABORT, 'snapshot rejected'); END
        """.trimIndent())
        val failure = runCatching {
            dao.replaceAccessible(listOf(media("b", "primary")), mapOf("primary" to "broken"))
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(listOf(original), dao.allMedia())
        assertEquals(listOf(hash), dao.allHashes())
        assertEquals(listOf(MediaVolumeVersion("primary", "one")), dao.allVolumeVersions())
    }

    @Test fun historyIsIdempotentSortedAndNeverChangesMediaOrProtection() = runBlocking {
        val media = media("protected", "primary")
        dao.replaceAccessible(listOf(media), mapOf("primary" to "one"))
        dao.protect(ProtectedMedia(media.uri))
        dao.putHash(CachedHash(media.uri, media.fingerprint, HASH, "one"))
        val first = CachedCleanupHistory("same-id", 1_000L, "MOVE_TO_TRASH", 2, 1, 4096)
        val second = CachedCleanupHistory("restore-id", 2_000L, "RESTORE", 1, 1, 4096)
        assertTrue(dao.insertCleanupHistory(first) >= 0)
        assertEquals(-1L, dao.insertCleanupHistory(first))
        dao.insertCleanupHistory(second)

        assertEquals(listOf("restore-id", "same-id"), dao.allCleanupHistory().map { it.id })
        assertEquals(1, dao.deleteCleanupHistory("same-id"))
        assertEquals(listOf("restore-id"), dao.allCleanupHistory().map { it.id })
        assertEquals(0, dao.deleteCleanupHistory("missing-id"))
        assertEquals(1, dao.clearCleanupHistory())
        assertTrue(dao.allCleanupHistory().isEmpty())
        assertEquals(1, dao.allMedia().size)
        assertEquals(listOf(media.uri), dao.allProtection().map { it.uri })
        assertEquals(1, dao.allHashes().size)
    }

    @Test fun historySurvivesMediaCacheRefreshAndDoesNotRequirePhotoAccess() = runBlocking {
        dao.insertCleanupHistory(CachedCleanupHistory("old", 1_000L, "MOVE_TO_TRASH", 2, 2, 2048))
        dao.replaceAccessible(emptyList(), emptyMap())
        assertEquals(1, dao.allCleanupHistory().size)
        assertEquals("old", dao.allCleanupHistory().single().entry().id)
    }

    @Test fun migratedHashesWithoutAKnownEpochAreInvalidated() = runBlocking {
        val row = media("a", "primary")
        dao.insertMedia(listOf(row))
        dao.putHash(CachedHash(row.uri, row.fingerprint, HASH))
        dao.replaceAccessible(listOf(row), mapOf("primary" to "current"))
        assertTrue(dao.allHashes().isEmpty())
    }

    private fun media(uri: String, volume: String) = CachedMedia.from(MediaItem(
        uri, 1, volume, "$uri.jpg", MediaKind.PHOTO, "image/jpeg", "Pictures/", 12,
        2, 3, 0, 1_000, 1_000, 1_000,
    ))

    private companion object { const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" }
}
