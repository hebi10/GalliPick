package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceNavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tabMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun tab(label: String) = compose.onNode(tabMatcher and hasText(label))

    @Test fun fourMainTabsAndCleanupDuplicateChildKeepBackAndSelectionCorrect() {
        val vm = AppViewModel(compose.activity.application)
        compose.setContent {
            GalliPickTheme {
                GalliPickApp(vm, {}, {}, {}, {})
            }
        }

        assertEquals(listOf("홈", "파일", "정리", "설정"), Destination.entries.map { it.title })
        compose.onAllNodes(tabMatcher).assertCountEquals(4)
        compose.onNode(tabMatcher and hasText("중복")).assertDoesNotExist()

        tab("정리").performClick()
        tab("정리").assertIsSelected()
        compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("정리로 돌아가기").assertIsDisplayed()
        compose.onNodeWithText("중복 정리").assertIsDisplayed()
        tab("정리").assertIsSelected()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("중복 파일 검사 열기").assertIsDisplayed()
        compose.onNodeWithContentDescription("정리로 돌아가기").assertDoesNotExist()
        tab("정리").assertIsSelected()

        compose.onNodeWithContentDescription("중복 파일 검사 열기").performScrollTo().performClick()
        tab("파일").performClick()
        tab("파일").assertIsSelected()
        compose.onNodeWithContentDescription("정리로 돌아가기").assertDoesNotExist()
    }

    @Test fun photoAnalysisIsChildOfCleanupAndBackReturnsWithoutFifthTab() {
        val vm = AppViewModel(compose.activity.application)
        compose.setContent {
            GalliPickTheme {
                GalliPickApp(vm, {}, {}, {}, {})
            }
        }
        tab("정리").performClick()
        compose.onNodeWithContentDescription("유사 사진 분석 열기")
            .performScrollTo().performClick()
        compose.onNodeWithText("사진 분석").assertIsDisplayed()
        compose.onAllNodes(tabMatcher).assertCountEquals(4)
        tab("정리").assertIsSelected()
        compose.onNodeWithContentDescription("사진 분석 닫기").performClick()
        compose.onNodeWithContentDescription("유사 사진 분석 열기").assertExists()
        compose.onNodeWithText("사진 분석").assertDoesNotExist()
    }

    @Test fun homeDuplicateShortcutOpensCleanupChildWithoutReintroducingAFifthTab() {
        val vm = AppViewModel(compose.activity.application)
        compose.setContent {
            GalliPickTheme {
                GalliPickApp(vm, {}, {}, {}, {})
            }
        }

        compose.onNode(hasText("중복 파일 찾기") and hasClickAction())
            .performScrollTo().performClick()
        tab("정리").assertIsSelected()
        compose.onNodeWithContentDescription("정리로 돌아가기").assertIsDisplayed()
        compose.onNodeWithContentDescription("정리로 돌아가기").performClick()
        compose.onNodeWithContentDescription("중복 파일 검사 열기").assertIsDisplayed()
        compose.onAllNodes(tabMatcher).assertCountEquals(4)
    }
}
