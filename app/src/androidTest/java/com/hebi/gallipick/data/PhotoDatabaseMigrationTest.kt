package com.hebi.gallipick.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDatabaseMigrationTest {
    @Test fun upgradeV2ToV3KeepsExactDuplicateProofAndProtectedState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "photo-v2-v3-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("""CREATE TABLE media_cache (
                    uri TEXT NOT NULL PRIMARY KEY, id INTEGER NOT NULL, volume TEXT NOT NULL,
                    displayName TEXT NOT NULL, kind TEXT NOT NULL, mimeType TEXT NOT NULL,
                    relativePath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, width INTEGER NOT NULL,
                    height INTEGER NOT NULL, durationMs INTEGER NOT NULL, dateTakenMs INTEGER NOT NULL,
                    dateAddedMs INTEGER NOT NULL, dateModifiedMs INTEGER NOT NULL,
                    generationModified INTEGER NOT NULL, isFavorite INTEGER NOT NULL,
                    isTrashed INTEGER NOT NULL, fingerprint TEXT NOT NULL
                )""".trimIndent())
                db.execSQL("""CREATE TABLE media_hash (
                    uri TEXT NOT NULL, fingerprint TEXT NOT NULL, sha256 TEXT,
                    volumeVersion TEXT NOT NULL DEFAULT '', PRIMARY KEY(uri, fingerprint)
                )""".trimIndent())
                db.execSQL("CREATE TABLE protected_media (uri TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE protected_folder (path TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("""CREATE TABLE scan_session (
                    id INTEGER NOT NULL PRIMARY KEY, token TEXT NOT NULL, phase TEXT NOT NULL,
                    completed INTEGER NOT NULL, total INTEGER NOT NULL, skipped INTEGER NOT NULL,
                    message TEXT NOT NULL, currentUri TEXT NOT NULL,
                    currentAttempts INTEGER NOT NULL
                )""".trimIndent())
                db.execSQL("CREATE TABLE media_volume_version (volume TEXT NOT NULL PRIMARY KEY, version TEXT NOT NULL)")
                db.execSQL("INSERT INTO protected_media (uri) VALUES ('content://test/1')")
                db.execSQL("INSERT INTO protected_folder (path) VALUES ('Pictures/Keep/')")
                db.execSQL("""INSERT INTO media_hash (uri, fingerprint, sha256, volumeVersion)
                    VALUES ('content://test/1','10:1:1','0123456789abcdef', 'v1')""")
                db.execSQL("""INSERT INTO scan_session (id, token, phase, completed, total, skipped,
                    message, currentUri, currentAttempts)
                    VALUES (1,'exact-test','PAUSED',2,3,0,'paused','',0)""")
                db.version = 2
            }
            val upgraded = Room.databaseBuilder(context, MediaDatabase::class.java, name)
                .addMigrations(MediaDatabase.migration2To3).build()
            try {
                val dao = upgraded.mediaDao()
                assertEquals(1, dao.allProtection().size)
                assertEquals("Pictures/Keep/", dao.allFolders().single().path)
                assertEquals("0123456789abcdef", dao.allHashes().single().sha256)
                assertEquals("exact-test", dao.session()?.token)
                assertTrue(dao.allPhotoHashes().isEmpty())
                dao.putPhotoHash(CachedPhotoHash("content://test/1", "10:1:1", "v1", "f0f0f0f0f0f0f0f0"))
                assertEquals("f0f0f0f0f0f0f0f0", dao.allPhotoHashes().single().perceptualHash)
            } finally {
                upgraded.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
