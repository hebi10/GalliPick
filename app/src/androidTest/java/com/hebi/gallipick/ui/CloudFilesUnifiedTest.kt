package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.data.cloud.CloudGallerySnapshot
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudFilesUnifiedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sha = "b".repeat(64)

    private val saved = CloudOriginal(
        uploadId = "$sha-1024", displayName = "cloud-holiday.jpg", mimeType = "image/jpeg",
        sizeBytes = 1024, sha256 = sha, verifiedAtMs = 1730000000000,
        sourceFolders = listOf("DCIM/Trips"), capturedAtMs = 1720000000000,
    )
    private val local = MediaItem(
        uri = "content://media/external/images/media/19001", id = 19001L,
        volume = "external_primary", displayName = "local-photo.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg", relativePath = "DCIM/Camera/",
        sizeBytes = 1024, width = 100, height = 100, durationMs = 0,
        dateTakenMs = 1720100000000, dateAddedMs = 1720100000000,
        dateModifiedMs = 1720100000000,
    )

    @Test fun cloudSourceCanBeOpenedWithoutDeviceMediaPermission() {
        val opened = mutableStateOf(false)
        val state = AppUiState(access = MediaAccess.NONE)
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(400.dp, 850.dp)) {
                    FilesScreenContent(state = state, wide = false,
                        trash = {}, open = {}, requestAccess = {}, actions = actions(),
                        cloudFiles = cloudState(), openCloud = { opened.value = true })
                }
            }
        }
        compose.onNodeWithTag("files-source-cloud").performClick()
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithText("local-photo.jpg").assertDoesNotExist()
        compose.onNodeWithText("cloud-holiday.jpg").performClick()
        compose.onNodeWithText("클라우드 보관함 열기").performClick()
        compose.runOnIdle { assertTrue(opened.value) }
    }

    @Test fun deviceFilterDoesNotRenderCloudOriginals() {
        val state = AppUiState(access = MediaAccess.FULL, items = listOf(local), filtered = listOf(local))
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(state = state, wide = false,
                        trash = {}, open = {}, requestAccess = {}, actions = actions(),
                        cloudFiles = cloudState())
                }
            }
        }
        compose.onNodeWithTag("files-source-cloud").performClick()
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithText("local-photo.jpg").assertDoesNotExist()
        compose.onNodeWithTag("files-source-device").performClick()
        compose.onNodeWithText("local-photo.jpg").assertExists()
        compose.onNodeWithText("cloud-holiday.jpg").assertDoesNotExist()
        compose.onNodeWithTag("files-source-all").performClick()
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithText("local-photo.jpg").assertExists()
    }

    private fun cloudState() = CloudFilesUiState(
        enabled = true, accountUid = "owner",
        snapshot = CloudGallerySnapshot("owner", listOf(saved)),
    )

    private fun actions() = FilesScreenActions(
        setFilter = {}, selectAll = {}, toggleSelection = {}, clearSelection = {},
        savePreset = {}, deletePreset = {}, setProtected = { _, _ -> }, refresh = {},
    )
}
