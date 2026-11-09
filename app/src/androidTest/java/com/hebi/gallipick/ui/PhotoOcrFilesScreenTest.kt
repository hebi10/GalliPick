package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoOcrFilesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun userControlsSearchScopeAndResultsNeverSelectTrashCandidates() {
        val photo = item("travel", "trip.jpg")
        val unrelated = item("receipt", "receipt.jpg")
        val current = mutableStateOf(AppUiState(
            access = MediaAccess.FULL, items = listOf(photo, unrelated),
            filtered = listOf(photo, unrelated),
            ocrEntries = listOf(
                PhotoOcrEntry(photo.uri, photo.fingerprint, photo.volumeVersion,
                    "서울 여행 숙소 예약", OcrResultStatus.SUCCESS),
                PhotoOcrEntry(unrelated.uri, unrelated.fingerprint, unrelated.volumeVersion,
                    "커피 결제", OcrResultStatus.SUCCESS),
            ),
            ocrScan = OcrScanState(OcrPhase.COMPLETE, completed = 2, total = 2),
        ))
        var trashRequests = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.65f)) {
                    Surface(Modifier.requiredSize(400.dp, 850.dp)) {
                        FilesScreenContent(
                            state = current.value, wide = false, trash = { trashRequests++ },
                            open = {}, requestAccess = {},
                            actions = FilesScreenActions(
                                setFilter = { filter ->
                                    current.value = current.value.copy(filter = filter,
                                        filtered = MediaQuery.apply(current.value.items, filter))
                                },
                                selectAll = {}, toggleSelection = {}, clearSelection = {},
                                savePreset = {}, deletePreset = {}, setProtected = { _, _ -> },
                                refresh = {},
                            ),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("사진 속 글자").performClick()
        compose.onNodeWithContentDescription("사진 속 글자 검색").performTextInput("서울")
        compose.onNodeWithText("trip.jpg").assertExists()
        compose.onNodeWithText("receipt.jpg").assertDoesNotExist()
        compose.onNodeWithText("인식된 글자:", substring = true).assertExists()
        compose.runOnIdle {
            assertTrue(current.value.selected.isEmpty())
            assertEquals(0, trashRequests)
        }
        compose.onNodeWithText("파일명·폴더").performClick()
        compose.onNodeWithText("receipt.jpg").assertExists()
    }

    @Test fun partialAccessDoesNotExposeStaleOcrText() {
        val photo = item("sample", "private.jpg")
        val state = AppUiState(
            access = MediaAccess.PARTIAL, items = emptyList(), filtered = emptyList(),
            ocrEntries = listOf(PhotoOcrEntry(photo.uri, photo.fingerprint,
                photo.volumeVersion, "민감한 주소", OcrResultStatus.SUCCESS)),
        )
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.7f)) {
                    Surface(Modifier.requiredSize(400.dp, 720.dp)) {
                        FilesScreenContent(state, false, {}, {}, {}, FilesScreenActions(
                            {}, {}, {}, {}, {}, {}, { _, _ -> }, {}))
                    }
                }
            }
        }
        compose.onNodeWithText("사진 속 글자").performClick()
        compose.onNodeWithText("0장 분석됨", substring = true).assertExists()
        compose.onNodeWithContentDescription("사진 속 글자 검색").performTextInput("주소")
        compose.onNodeWithText("private.jpg").assertDoesNotExist()
    }

    private fun item(id: String, name: String) = MediaItem(
        "content://example/" + id, id.hashCode().toLong(), "primary", name,
        MediaKind.PHOTO, "image/jpeg", "DCIM/", 1024L, 1400, 900, 0L,
        1L, 1L, 1L, volumeVersion = "one",
    )
}
