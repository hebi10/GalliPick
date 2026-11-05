package com.hebi.gallipick

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.domain.MediaFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashReviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun changingSelectionClosesTheReviewBeforeAnySystemRequest() = runBlocking {
        withReviewedFile { vm, _ ->
            compose.runOnIdle { vm.clearSelection() }
            compose.onNodeWithText("휴지통 이동 요청").assertDoesNotExist()
            compose.onNodeWithText("선택이 변경되어 확인창을 닫았습니다. 이동할 파일을 다시 확인해 주세요.").assertIsDisplayed()
        }
    }

    @Test fun changedFileIsRejectedAgainAfterTheReviewBeforeTheSystemRequest() = runBlocking {
        withReviewedFile { vm, uri ->
            val messages = CopyOnWriteArrayList<String>()
            val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.ui.collect { it.message?.let(messages::add) } }
            val pauses = AtomicInteger()
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) pauses.incrementAndGet()
            }
            compose.runOnUiThread { compose.activity.lifecycle.addObserver(lifecycleObserver) }
            try {
                val changedName = "changed-after-review-${System.nanoTime()}.png"
                compose.activity.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, changedName)
                }, null, null)
                compose.onNodeWithText("휴지통 이동 요청").performClick()
                compose.waitUntil(15_000) { messages.any { it.contains("확인 중 파일 상태가 변경되었습니다") } }
                withTimeout(15_000) { vm.ui.first { state ->
                    !state.loading && state.items.any { it.uri == uri.toString() && it.displayName == changedName }
                } }
                compose.onNodeWithText("휴지통 이동 요청").assertDoesNotExist()
                saveReviewDiagnostics(pauses.get())
                compose.onNodeWithText("확인 중 파일 상태가 변경되었습니다. 새로고침 후 다시 선택해 주세요.").assertIsDisplayed()
                assertFalse(vm.repository.reader.current(uri.toString())!!.isTrashed)
                // Accessibility may still expose the dismissed platform Dialog's
                // root. Verify our activity never yielded to a system request instead.
                compose.runOnIdle {
                    assertTrue(vm.ui.value.selected.isEmpty())
                    assertEquals(0, pauses.get())
                    assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
                }
                val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?.let(::File) ?: File(compose.activity.filesDir, "test-screenshots")
                directory.mkdirs()
                File(directory, "review-edited-file-rejected.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                compose.runOnUiThread { compose.activity.lifecycle.removeObserver(lifecycleObserver) }
                job.cancel()
            }
        }
    }

    private fun saveReviewDiagnostics(pauses: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(compose.activity.filesDir, "test-screenshots")
        directory.mkdirs()
        val automation = instrumentation.uiAutomation
        val root = automation.rootInActiveWindow
        File(directory, "review-rejection-diagnostics.txt").writeText(
            "pauses=$pauses\nlifecycle=${compose.activity.lifecycle.currentState}\n" +
                "windowPackage=${root?.packageName}\nwindowClass=${root?.className}\nwindowText=${root?.text}\n" +
                compose.onRoot(useUnmergedTree = true).printToString(),
        )
        automation.takeScreenshot()?.let { bitmap ->
            File(directory, "review-rejection-system-screen.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private suspend fun withReviewedFile(check: suspend (AppViewModel, Uri) -> Unit) {
        val app = compose.activity.application as GalliPickApplication
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "review-selection-${System.nanoTime()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GalliPickReviewTests/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            app.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            lateinit var vm: AppViewModel
            compose.runOnUiThread {
                vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
                vm.setAccess(MediaAccess.PARTIAL)
                vm.setFilter(MediaFilter(query = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)))
                vm.refresh()
            }
            withTimeout(15_000) { vm.ui.first { !it.loading && it.filtered.any { item -> item.uri == uri.toString() } } }
            compose.onAllNodesWithText("파일").onFirst().performClick()
            compose.runOnIdle { vm.toggleSelection(uri.toString()) }
            compose.onNodeWithText("휴지통으로").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("휴지통 이동 요청").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("휴지통 이동 요청").assertIsDisplayed()
            check(vm, uri)
        } finally {
            app.contentResolver.delete(uri, null, null)
            compose.runOnUiThread { ViewModelProvider(compose.activity)[AppViewModel::class.java].setFilter(MediaFilter()) }
        }
    }
}
