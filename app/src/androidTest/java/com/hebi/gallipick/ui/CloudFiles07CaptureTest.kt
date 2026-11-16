package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.data.cloud.CloudGallerySnapshot
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.VerifiedGalleryMatch
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures genuine Compose screens with deliberately synthetic catalog metadata.
 * No user's media, Firebase account, signed URL, ad ID, or server is accessed.
 */
@RunWith(AndroidJUnit4::class)
class CloudFiles07CaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val local = MediaItem(
        uri = "content://media/external/images/media/101",
        id = 101, volume = "external", displayName = "EXAMPLE_device_photo.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = "DCIM/Camera/", sizeBytes = 2400, width = 1600, height = 1200,
        durationMs = 0, dateTakenMs = 1731100000000L, dateAddedMs = 1731100000000L,
        dateModifiedMs = 1731100000000L, generationModified = 7, volumeVersion = "qa-epoch",
    )
    private val localOnly = local.copy(
        uri = "content://media/external/images/media/102",
        id = 102, displayName = "EXAMPLE_local_only.jpg", sizeBytes = 9200,
    )
    private val hash = "a".repeat(64)
    private val backed = CloudOriginal(
        uploadId = "$hash-2400", displayName = "EXAMPLE_cloud_backed.jpg",
        mimeType = "image/jpeg", sizeBytes = 2400, sha256 = hash,
        verifiedAtMs = 1731200000000L, capturedAtMs = 1731100000000L,
        sourceFolders = listOf("DCIM/Camera"),
    )
    private val photoOnly = CloudOriginal(
        uploadId = "${"b".repeat(64)}-38000",
        displayName = "EXAMPLE_cloud_only.jpg",
        mimeType = "image/jpeg", sizeBytes = 38000, sha256 = "b".repeat(64),
        verifiedAtMs = 1731300000000L, capturedAtMs = 1731101000000L,
        sourceFolders = listOf("Pictures/Trips"),
    )
    private val videoOnly = CloudOriginal(
        uploadId = "${"c".repeat(64)}-9500000",
        displayName = "EXAMPLE_cloud_video.mp4",
        mimeType = "video/mp4", sizeBytes = 9500000, sha256 = "c".repeat(64),
        verifiedAtMs = 1731400000000L, capturedAtMs = 1731102000000L,
        durationMs = 87000L,
    )

    @Test
    fun captureCombinedCloudOfflineAndMeteredFiveProfilesInBothThemes() {
        val app = compose.activity.applicationContext
        val output = File(app.filesDir, "cloud-files-07-qa-captures").apply { mkdirs() }
        output.listFiles()?.forEach { it.delete() }
        val profile = InstrumentationRegistry.getArguments().getString("captureProfile")
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "fixture"
        val theme = mutableStateOf(AppThemeMode.LIGHT)
        val network = mutableStateOf(cloudState())
        val inventory = JSONArray()
        val state = AppUiState(
            access = MediaAccess.FULL, items = listOf(local, localOnly),
            filtered = listOf(local, localOnly),
        )
        compose.setContent {
            GalliPickTheme(theme.value) {
                Surface(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        FilesScreenContent(
                            state = state,
                            wide = maxWidth >= 650.dp,
                            trash = { error("QA fixture must never trash a device item") },
                            open = {},
                            requestAccess = {},
                            actions = FilesScreenActions({}, {}, {}, {}, {}, {}, { _, _ -> }, {}),
                            cloudFiles = network.value,
                        )
                    }
                }
            }
        }

        fun capture(name: String) {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(250)
            val image = checkNotNull(InstrumentationRegistry.getInstrumentation()
                .uiAutomation.takeScreenshot()) { "Android UI screenshot unavailable: $name" }
            try {
                assertTrue("Capture is too small", image.width >= 640 && image.height >= 640)
                val file = File(output, "$name.png")
                file.outputStream().use {
                    assertTrue("PNG encoding failed: $name",
                        image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                assertTrue("Capture bytes are missing: $name", file.length() > 2_000L)
                inventory.put(JSONObject().put("name", name).put("file", file.name)
                    .put("width", image.width).put("height", image.height)
                    .put("source", "synthetic-metadata-compose-actual-screen"))
            } finally {
                image.recycle()
            }
        }

        for ((label, mode) in listOf(
            "light" to AppThemeMode.LIGHT,
            "dark" to AppThemeMode.DARK,
        )) {
            compose.runOnIdle {
                theme.value = mode
                network.value = cloudState()
            }
            compose.onNodeWithTag("files-source-all").performClick()
            compose.onNodeWithText("EXAMPLE_local_only.jpg").assertExists()
            compose.onNodeWithText("EXAMPLE_cloud_only.jpg").assertExists()
            capture("$label-01-combined")

            compose.onNodeWithTag("files-source-cloud").performClick()
            compose.onNodeWithText("EXAMPLE_cloud_video.mp4").assertExists()
            capture("$label-02-cloud")

            compose.runOnIdle { network.value = cloudState().copy(
                online = false, unmetered = false, cachedCatalog = true,
            ) }
            compose.onNodeWithText("오프라인", substring = true).assertExists()
            capture("$label-03-offline")

            compose.runOnIdle { network.value = cloudState().copy(unmetered = false) }
            compose.onNodeWithText("모바일 데이터 사용 중", substring = true).assertExists()
            capture("$label-04-metered")
        }
        assertEquals(8, inventory.length())
        File(output, "$profile-manifest.json").writeText(
            JSONObject()
                .put("profile", profile)
                .put("fixture", "synthetic catalog; no real account or network upload")
                .put("captureCount", inventory.length())
                .put("captures", inventory).toString(2),
            Charsets.UTF_8,
        )
    }

    private fun cloudState() = CloudFilesUiState(
        enabled = true,
        accountUid = "qa-account-fixture",
        snapshot = CloudGallerySnapshot("qa-account-fixture", listOf(backed, photoOnly, videoOnly)),
        verifiedMatches = listOf(VerifiedGalleryMatch(
            "qa-account-fixture", local.uri, local.fingerprint,
            local.volumeVersion, backed.uploadId, backed.sha256,
        )),
        online = true, unmetered = true,
    )
}
