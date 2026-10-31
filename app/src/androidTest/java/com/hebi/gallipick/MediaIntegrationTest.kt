package com.hebi.gallipick

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.data.MediaRepository
import com.hebi.gallipick.domain.ScanPhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class MediaIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as GalliPickApplication
    private val repository: MediaRepository get() = app.repository
    private val created = mutableListOf<Uri>()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun createMedia() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_MEDIA_LOCATION)
        repository.clearAnalysisCache()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        repeat(2) { index ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "gallipick-test-${System.nanoTime()}-$index.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GalliPickTests/")
                put(MediaStore.MediaColumns.WIDTH, 8)
                put(MediaStore.MediaColumns.HEIGHT, 8)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
            created += uri
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
        withTimeout(10_000) {
            while (true) {
                repository.refresh()
                val ready = repository.media.first().filter { item -> created.any { it.toString() == item.uri } }
                if (ready.size == 2 && ready.all { it.sizeBytes == bytes.size.toLong() }) break
                delay(100)
            }
        }
        repository.refresh()
    }

    @After fun removeMedia() = runBlocking {
        repository.clearAnalysisCache()
        instrumentation.runOnMainSync { stores.forEach { it.clear() } }
        repository.setFolderProtected("Pictures/GalliPickTests/", false)
        created.forEach {
            repository.setProtected(it.toString(), false)
            app.contentResolver.delete(it, null, null)
        }
        repository.refresh()
    }

    @Test fun exactScanAndLastCopyValidation() = runBlocking {
        repository.startScan()
        val scan = withTimeout(45_000) { repository.scan.first { it.phase == ScanPhase.COMPLETE || it.phase == ScanPhase.FAILED } }
        assertEquals(scan.message, ScanPhase.COMPLETE, scan.phase)
        val group = repository.duplicates.first().first { group -> created.all { uri -> group.items.any { it.uri == uri.toString() } } }
        val selected = group.items.first { it.uri == created[0].toString() }
        val keeper = group.items.first { it.uri == created[1].toString() }
        assertTrue(repository.hasSurvivingCopy(group, setOf(selected.uri)))
        app.contentResolver.delete(Uri.parse(keeper.uri), null, null)
        assertFalse(repository.hasSurvivingCopy(group, group.items.filterNot { it.uri == keeper.uri }.map { it.uri }.toSet()))
    }

    @Test fun protectionSurvivesRefreshAndCacheClear() = runBlocking {
        val item = repository.media.first().first { it.uri == created[0].toString() }
        repository.setProtected(item.uri, true)
        repository.clearAnalysisCache()
        repository.refresh()
        assertTrue(repository.media.first().first { it.uri == item.uri }.isProtected)
        assertTrue(repository.revalidate(listOf(item)).isEmpty())
        repository.setProtected(item.uri, false)
        repository.setFolderProtected(item.relativePath, true)
        assertTrue(repository.revalidate(listOf(item)).isEmpty())
        repository.setFolderProtected(item.relativePath, false)
        assertEquals(1, repository.revalidate(listOf(item)).size)
    }

    @Test fun permissionRevalidationHidesPersistedCatalogEvenForSameAccessMode() = runBlocking {
        lateinit var vm: AppViewModel
        instrumentation.runOnMainSync {
            val store = ViewModelStore().also { stores += it }
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppViewModel::class.java]
            assertTrue(vm.ui.value.items.isEmpty())
            vm.setAccess(MediaAccess.PARTIAL)
            vm.refresh()
        }
        withTimeout(15_000) { vm.ui.first { it.items.any { item -> item.uri == created[0].toString() } } }
        instrumentation.runOnMainSync { vm.setAccess(MediaAccess.PARTIAL) }
        assertTrue(vm.ui.value.items.isEmpty())
        assertTrue(vm.ui.value.filtered.isEmpty())
        assertTrue(vm.ui.value.duplicates.isEmpty())
        assertTrue(vm.ui.value.trashed.isEmpty())
    }
}
