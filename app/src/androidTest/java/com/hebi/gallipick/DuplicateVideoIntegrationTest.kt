package com.hebi.gallipick

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.ScanPhase
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicateVideoIntegrationTest {
    @Test fun identicalVideosWithDifferentNamesAppearInOneGroup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GalliPickApplication
        val repository = app.repository
        val bytes = instrumentation.context.assets.open("preview-baseline.mp4").use { it.readBytes() }
        val nonce = System.nanoTime()
        val names = listOf("gallipick-video-$nonce-original.mp4", "gallipick-video-$nonce-copy.mp4")
        val samples = mutableListOf<Stage7aFixtureMedia.Sample>()

        try {
            names.forEach { name ->
                samples += Stage7aFixtureMedia.insert(
                    app, name, "video/mp4", bytes, System.currentTimeMillis(), "Movies/GalliPickTests/",
                )
            }
            val uris = samples.map { it.uri.toString() }.toSet()
            repository.refresh()
            repository.startScan()
            val pair = withTimeout(45_000) {
                repository.duplicates.map { groups ->
                    groups.firstNotNullOfOrNull { group ->
                        group.items.filter { it.uri in uris }.takeIf { it.size == uris.size }
                    }
                }.filterNotNull().first()
            }
            assertEquals(names.toSet(), pair.map { it.displayName }.toSet())
            assertTrue(pair.all { it.kind == MediaKind.VIDEO })
            val completed = withTimeout(15_000) {
                repository.scan.first { it.phase == ScanPhase.COMPLETE || it.phase == ScanPhase.FAILED }
            }
            assertEquals(completed.message, ScanPhase.COMPLETE, completed.phase)
        } finally {
            samples.forEach { app.contentResolver.delete(it.uri, null, null) }
            repository.refresh()
        }
    }
}
