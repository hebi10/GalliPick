package com.hebi.gallipick

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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
            compose.onNodeWithText("Android 확인").assertDoesNotExist()
            compose.onNodeWithText("선택이 변경되어 확인창을 닫았습니다. 이동할 파일을 다시 확인해 주세요.").assertIsDisplayed()
        }
    }

    @Test fun changedFileIsRejectedAgainAfterTheReviewBeforeTheSystemRequest() = runBlocking {
        withReviewedFile { vm, uri ->
            val messages = CopyOnWriteArrayList<String>()
            val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.ui.collect { it.message?.let(messages::add) } }
            try {
                compose.activity.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "changed-after-review-${System.nanoTime()}.png")
                }, null, null)
                compose.onNodeWithText("Android 확인").performClick()
                compose.waitUntil(15_000) { messages.any { it.contains("확인 중 파일 상태가 변경되었습니다") } }
                compose.onNodeWithText("Android 확인").assertDoesNotExist()
                assertFalse(vm.repository.reader.current(uri.toString())!!.isTrashed)
                assertEquals(compose.activity.packageName, InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow.packageName.toString())
            } finally { job.cancel() }
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
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Android 확인").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Android 확인").assertIsDisplayed()
            check(vm, uri)
        } finally {
            app.contentResolver.delete(uri, null, null)
            compose.runOnUiThread { ViewModelProvider(compose.activity)[AppViewModel::class.java].setFilter(MediaFilter()) }
        }
    }
}
