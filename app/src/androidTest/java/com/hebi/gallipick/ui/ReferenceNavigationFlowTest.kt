package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceNavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tabMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private fun tab(label: String) = compose.onNode(tabMatcher and hasText(label))

    @Test fun cloudManagementAppearsOnlyInItsOwnTab() {
        val vm = AppViewModel(compose.activity.application)
        val managementLabel = "클라우드 계정 관리 테스트"
        val accountBackend = object : CloudAccountBackend {
            override val account = MutableStateFlow<CloudAccountIdentity?>(null)
            override suspend fun signIn(googleIdToken: String) = Unit
            override fun signOut() = Unit
            override suspend fun clearGoogleState() = Unit
        }
        val cloudConnection = CloudAccountConnection(
            session = CloudAccountSession(accountBackend),
            getGoogleIdToken = { error("The navigation test does not sign in") },
        )
        compose.setContent {
            GalliPickTheme {
                GalliPickApp(
                    vm, {}, {}, {}, {},
                    cloudContent = { _, _ -> Text(managementLabel) },
                    cloudConnection = cloudConnection,
                )
            }
        }

        compose.onAllNodes(tabMatcher).assertCountEquals(5)
        tab("설정").performClick()
        compose.onNodeWithText(managementLabel).assertDoesNotExist()
        tab("클라우드").performClick()
        tab("클라우드").assertIsSelected()
        compose.onNodeWithText(managementLabel).assertIsDisplayed()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        tab("홈").assertIsSelected()
        compose.onNodeWithText(managementLabel).assertDoesNotExist()
    }

    @Test fun fourMainTabsAndCleanupDuplicateChildKeepBackAndSelectionCorrect() {
        val vm = AppViewModel(compose.activity.application)
        compose.setContent {
            GalliPickTheme {
                GalliPickApp(vm, {}, {}, {}, {})
            }
        }

        assertEquals(listOf("홈", "파일", "정리", "설정"), visibleDestinations().map { it.title })
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
        compose.onNodeWithTag("similar-analysis-status").assertExists()
        compose.onNodeWithText("비슷한 사진 찾기").assertExists()
        compose.onAllNodes(tabMatcher).assertCountEquals(4)
        tab("정리").assertIsSelected()
        compose.onNodeWithContentDescription("사진 분석 닫기").performClick()
        compose.onNodeWithContentDescription("유사 사진 분석 열기").assertExists()
        compose.onNodeWithTag("similar-analysis-status").assertDoesNotExist()
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
