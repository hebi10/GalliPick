package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashReviewDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun separatesMovingFilesFromConfirmedKeptCopiesWithoutStartingAMove() {
        var proceeded = 0
        compose.setContent { GalliPickTheme(AppThemeMode.DARK) { Surface {
            TrashReviewContent(listOf(item(1, "move.mp4")), listOf(item(2, "keep.mp4")), false, 0, {}, { proceeded++ })
        } } }
        compose.onNodeWithText("move.mp4").assertIsDisplayed()
        compose.onNodeWithText("keep.mp4").assertDoesNotExist()
        compose.onNodeWithText("남김 1묶음").performClick()
        compose.onNodeWithText("keep.mp4").assertIsDisplayed()
        compose.onNodeWithText("move.mp4").assertDoesNotExist()
        assertEquals(0, proceeded)
        compose.onNodeWithText("휴지통 이동 요청").performClick()
        assertEquals(1, proceeded)
    }

    @Test fun shortLargeTextKeepsCancelAndSystemConfirmationReachable() {
        compose.setContent { GalliPickTheme { CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
            Box(Modifier.requiredSize(320.dp, 280.dp)) { TrashReviewContent(listOf(item(1, "move.mp4")), listOf(item(2, "keep.mp4")), false, 2, {}, {}) }
        } } }
        compose.onNodeWithText("취소").assertIsDisplayed()
        compose.onNodeWithText("휴지통 이동 요청").assertIsDisplayed()
        compose.onNodeWithContentDescription("이동할 파일 확인 목록").performScrollToNode(hasText("move.mp4"))
        compose.onNodeWithText("move.mp4").assertIsDisplayed()
    }

    private fun item(id: Long, name: String) = MediaItem("content://fixture/$id", id, "external", name, MediaKind.VIDEO,
        "video/mp4", "Movies/", 1024, 1920, 1080, 30_000, 10, 10, 10)
}
