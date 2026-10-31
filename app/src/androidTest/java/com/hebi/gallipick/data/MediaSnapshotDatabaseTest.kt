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
