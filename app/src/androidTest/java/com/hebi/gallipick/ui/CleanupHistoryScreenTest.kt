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
import com.hebi.gallipick.domain.CleanupHistoryAction
import com.hebi.gallipick.domain.CleanupHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupHistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val history = listOf(
        CleanupHistoryEntry("move", 1_700_000_000_000L, CleanupHistoryAction.MOVE_TO_TRASH, 3, 2, 4096),
        CleanupHistoryEntry("restore", 1_700_000_100_000L, CleanupHistoryAction.RESTORE, 1, 1, 2048),
    )

    @Test fun historyShowsConfirmedCountAndLabelsPartialOperationsAccurately() {
        render(history)
        compose.onNodeWithText("총 2건").assertExists()
        compose.onNodeWithText("휴지통 이동").assertExists()
        compose.onNodeWithText("파일 복원").assertExists()
        compose.onNodeWithText("확인 완료 2개").assertExists()
        compose.onNodeWithText("전체 3개 요청 중 일부 확인").assertExists()
        compose.onNodeWithText("실제 확보 공간은 아닙니다.", substring = true).assertExists()
    }

    @Test fun individualRecordDeleteRequiresExplicitConfirmation() {
        val deleted = mutableListOf<String>()
        render(listOf(history.first()), onDelete = { deleted += it })
        compose.onNodeWithText("기록 삭제").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), deleted) }

        compose.onNodeWithText("기록 삭제").performScrollTo().performClick()
        compose.onNodeWithText("삭제 확인").performClick()
        compose.runOnIdle { assertEquals(listOf("move"), deleted) }
    }

    @Test fun clearAllRequiresExplicitConfirmation() {
        var cleared = 0
        render(history, onClear = { cleared++ })
        compose.onNodeWithText("전체 기록 삭제").performClick()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithText("전체 삭제 확인").performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
    }

    @Test fun emptyHistoryShowsNoDestructiveAction() {
        render(emptyList())
        compose.onNodeWithText("정리 기록이 없습니다").assertExists()
        compose.onNodeWithText("전체 기록 삭제").assertDoesNotExist()
        compose.onNodeWithText("기록 삭제").assertDoesNotExist()
    }

    private fun render(
        entries: List<CleanupHistoryEntry>,
        onDelete: (String) -> Unit = {},
        onClear: () -> Unit = {},
    ) {
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(.6f)) {
                    Surface(Modifier.requiredSize(360.dp, 800.dp)) {
                        CleanupHistoryScreen(entries, {}, onDelete, onClear)
                    }
                }
            }
        }
    }
}
