package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashEmptyLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyAnAuthoritativeEmptyTrashShowsTheEmptyState() {
        val state = mutableStateOf(AppUiState(loading = true, catalogError = "조회 실패"))
        var requestedAccess = 0
        var retried = 0
        compose.setContent { GalliPickTheme {
            TrashScreen(state.value, {}, {}, { requestedAccess++ }, { retried++ })
        } }
        compose.onNodeWithText("미디어 접근이 필요합니다").assertIsDisplayed()
        compose.onNodeWithText("접근 허용").performClick()
        compose.onNodeWithText("휴지통이 비어 있습니다").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, requestedAccess)
            state.value = state.value.copy(access = MediaAccess.FULL, catalogError = null)
        }
        compose.onNodeWithText("휴지통을 불러오고 있습니다").assertIsDisplayed()
        compose.onNodeWithText("휴지통이 비어 있습니다").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, catalogError = "조회 실패") }
        compose.onNodeWithText("휴지통을 불러오지 못했습니다").assertIsDisplayed()
        compose.onNodeWithText("다시 시도").performClick()
        compose.onNodeWithText("휴지통이 비어 있습니다").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, retried)
            state.value = state.value.copy(catalogError = null, catalogReady = true)
        }
        compose.onNodeWithText("휴지통이 비어 있습니다").assertIsDisplayed()
    }

    @Test fun shortLandscapeShowsEmptyTrashTitleWithoutHidingBack() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                    Box(Modifier.requiredSize(600.dp, 300.dp)) {
                        TrashScreen(AppUiState(access = MediaAccess.FULL, catalogReady = true), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("휴지통이 비어 있습니다").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").assertIsDisplayed()
    }

    @Test fun largeTextEmptyTrashHasVisibleTextAndBackAction() {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.6f)) {
                    Box(Modifier.requiredSize(320.dp, 460.dp)) {
                        TrashScreen(AppUiState(access = MediaAccess.FULL, catalogReady = true), {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("휴지통이 비어 있습니다").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").assertIsDisplayed()
    }

    @Test fun emptyTrashWithPartialAccessDescribesTheAllowedFilesOnly() {
        compose.setContent {
            GalliPickTheme {
                TrashScreen(AppUiState(access = MediaAccess.PARTIAL, catalogReady = true), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("허용한 파일 중 휴지통에 있는 파일이 없습니다").assertIsDisplayed()
        compose.onNodeWithText("휴지통이 비어 있습니다").assertDoesNotExist()
        compose.onNodeWithText("접근 범위 변경").assertIsDisplayed()
    }

    @Test fun trashOffersAppSettingsWhenTheSystemPermissionRequestIsUnavailable() {
        var requests = 0
        var settingsOpens = 0
        compose.setContent { GalliPickTheme {
            TrashScreen(AppUiState(access = MediaAccess.NONE), {}, {}, { requests++ }, {},
                onOpenPermissionSettings = { settingsOpens++ })
        } }
        compose.onNodeWithText("접근 허용").assertDoesNotExist()
        compose.onNodeWithText("앱 권한 설정").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, requests)
            assertEquals(1, settingsOpens)
        }
    }

    @Test fun selectAllReflectsTheExactFirstBatchWhenTheTrashOrderChanges() {
        val files = (1L..501L).map { id ->
            MediaItem("file:///missing-trash-$id.jpg", id, "test", "trash-$id.jpg",
                MediaKind.PHOTO, "image/jpeg", "Download/", 1024, 320, 240, 0, id, id, id,
                isTrashed = true)
        }
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, catalogReady = true, trashed = files.take(499)))
        var restored = emptyList<MediaItem>()
        compose.setContent { GalliPickTheme { TrashScreen(state.value, { restored = it }, {}, {}, {}) } }

        val selectAll = compose.onNode(hasContentDescription("휴지통 전체 선택") or hasContentDescription("앞 500개 선택"))
        selectAll.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("선택한 499개 복원").performClick()
        compose.runOnIdle {
            assertEquals(files.take(499), restored)
            state.value = state.value.copy(trashed = files.take(500))
        }
        selectAll.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("선택한 500개 복원").performClick()
        compose.runOnIdle {
            assertEquals(files.take(500), restored)
            state.value = state.value.copy(trashed = files)
        }
        selectAll.assertIsOn()
        compose.runOnIdle { state.value = state.value.copy(trashed = listOf(files.last()) + files.dropLast(1)) }
        selectAll.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("선택한 500개 복원").performClick()
        compose.runOnIdle { assertEquals(state.value.trashed.take(500), restored) }
    }
}
