package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderStorageScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun folderUsageShowsMediaTotalsAndOpensExactDirectory() {
        val input = listOf(
            media(1, "DCIM/Camera/", 1024 * 1024),
            media(2, "DCIM/Camera/", 2L * 1024 * 1024),
            media(3, "Download/", 512L * 1024),
            media(4, "Download/", 100, trashed = true),
        )
        var opened: String? = null
        screen(AppUiState(access = MediaAccess.FULL, items = input), onOpenFolder = { opened = it })
        compose.onNodeWithText("접근 가능한 사진·동영상 합계").assertExists()
        compose.onNodeWithText("3.5 MB").assertExists()
        compose.onNodeWithContentDescription("DCIM/Camera 폴더 파일 보기").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("DCIM/Camera/", opened) }
    }

    @Test fun unknownPathsContributeToTotalsWithoutFakeFolderNavigation() {
        screen(AppUiState(access = MediaAccess.FULL, items = listOf(media(1, "", 2048))))
        compose.onNodeWithContentDescription("폴더별 용량 목록")
            .performScrollToNode(hasText("폴더 정보 없음"))
        compose.onNodeWithText("폴더 정보 없음").assertExists()
        compose.onNodeWithText("폴더 경로가 없어 바로 열 수 없습니다.").assertExists()
        compose.onNodeWithContentDescription("폴더 정보 없음 폴더 파일 보기").assertDoesNotExist()
    }

    @Test fun usersCanChangeSortingWhileKeepingFolderIdentity() {
        screen(AppUiState(access = MediaAccess.FULL, items = listOf(
            media(1, "DCIM/Camera/", 2000),
            media(2, "Download/", 800),
            media(3, "Download/", 800),
        )))
        compose.onNodeWithContentDescription("폴더 정렬 선택").performClick()
        compose.onNodeWithText("파일 많은 순").performClick()
        compose.onNodeWithText("파일 많은 순").assertExists()
        val camera = compose.onNodeWithText("DCIM/Camera").fetchSemanticsNode().boundsInRoot
        val downloads = compose.onNodeWithText("Download").fetchSemanticsNode().boundsInRoot
        assertTrue("Download has more files and must be first", downloads.top < camera.top)
    }

    @Test fun noneAccessDoesNotLeakStaleStatisticsAndBackRemainsReachable() {
        var backs = 0
        screen(
            AppUiState(access = MediaAccess.NONE, items = listOf(media(1, "DCIM/Camera/", 1024))),
            onBack = { backs++ },
        )
        compose.onNodeWithText("미디어 접근이 필요합니다").assertIsDisplayed()
        compose.onNodeWithText("접근 가능한 사진·동영상 합계").assertDoesNotExist()
        compose.onNodeWithContentDescription("폴더별 용량 분석 닫기").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    private fun screen(
        state: AppUiState,
        onBack: () -> Unit = {},
        onOpenFolder: (String) -> Unit = {},
    ) {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 760.dp)) {
                        FolderStorageScreen(state, onBack, onOpenFolder, requestAccess = {}, refresh = {})
                    }
                }
            }
        }
    }

    private fun media(id: Long, path: String, bytes: Long, trashed: Boolean = false) = MediaItem(
        uri = "content://media/external/images/media/$id", id = id, volume = "external_primary",
        displayName = "$id.jpg", kind = MediaKind.PHOTO, mimeType = "image/jpeg",
        relativePath = path, sizeBytes = bytes, width = 100, height = 100,
        durationMs = 0, dateTakenMs = 1, dateAddedMs = 1, dateModifiedMs = 1,
        isTrashed = trashed,
    )
}
