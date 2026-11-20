package com.hebi.gallipick.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountExit
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudExitScope
import com.hebi.gallipick.data.cloud.CloudExitSnapshot
import com.hebi.gallipick.data.cloud.CloudExitState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudAccountExitUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class AccountBackend : CloudAccountBackend {
        override val account: StateFlow<CloudAccountIdentity?> = MutableStateFlow(
            CloudAccountIdentity("exit-ui-test-user", "테스트 사용자", "user@example.test"))
        override suspend fun signIn(googleIdToken: String) = Unit
        override fun signOut() = Unit
        override suspend fun clearGoogleState() = Unit
    }

    private class FakeExitApi : CloudAccountExit {
        val requests = mutableListOf<CloudExitScope>()
        var current = CloudExitSnapshot(CloudExitState.NONE)
        override suspend fun status(uid: String): CloudExitSnapshot {
            assertEquals("exit-ui-test-user", uid)
            return current
        }
        override suspend fun request(activity: Activity, uid: String, scope: CloudExitScope): CloudExitSnapshot {
            assertEquals("exit-ui-test-user", uid)
            requests.add(scope)
            return CloudExitSnapshot(CloudExitState.REQUESTED, scope).also { current = it }
        }
        override suspend fun resumeCloudBackup(activity: Activity, uid: String): CloudExitSnapshot {
            assertEquals("exit-ui-test-user", uid)
            return CloudExitSnapshot(CloudExitState.RESUMED, CloudExitScope.CLOUD).also { current = it }
        }
    }

    @Test
    fun accountExitRequiresTwoExplicitConfirmationsAndDoesNotClaimImmediateDeletion() {
        val api = FakeExitApi()
        show(CloudExitScope.ACCOUNT, api)

        compose.waitUntil(5_000L) {
            compose.onNodeWithText("회원 탈퇴").fetchSemanticsNodeOrNull() != null
        }
        compose.onNodeWithText("회원 탈퇴").performClick()
        compose.onNodeWithText("GalliPick 회원 탈퇴").assertIsDisplayed()
        compose.onNodeWithText("Google 재인증 후 삭제 요청").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-exit-confirm-permanent").performClick()
        compose.onNodeWithText("Google 재인증 후 삭제 요청").assertIsNotEnabled()
        compose.onNodeWithTag("cloud-exit-confirm-billing").performClick()
        compose.waitUntil(5_000L) {
            compose.onNodeWithText("Google 재인증 후 삭제 요청").fetchSemanticsNodeOrNull() != null
        }
        compose.onNodeWithText("Google 재인증 후 삭제 요청").assertIsEnabled().performClick()
        compose.waitUntil(5_000L) { compose.runOnIdle { api.requests.size == 1 } }
        compose.onNodeWithText("삭제 요청 처리 현황").assertIsDisplayed()
        compose.onNodeWithText("삭제 요청이 접수됐습니다.", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(CloudExitScope.ACCOUNT), api.requests) }
    }

    @Test
    fun cloudOnlyDeletionDoesNotCallAccountDeletion() {
        val api = FakeExitApi()
        show(CloudExitScope.CLOUD, api)
        compose.waitUntil(5_000L) {
            compose.onNodeWithText("클라우드 데이터 삭제 요청").fetchSemanticsNodeOrNull() != null
        }
        compose.onNodeWithText("클라우드 데이터 삭제 요청").performClick()
        compose.onNodeWithText("클라우드 데이터 삭제").assertIsDisplayed()
        compose.onNodeWithTag("cloud-exit-confirm-permanent").performClick()
        compose.onNodeWithTag("cloud-exit-confirm-billing").performClick()
        compose.onNodeWithText("Google 재인증 후 삭제 요청").assertIsEnabled().performClick()
        compose.waitUntil(5_000L) { compose.runOnIdle { api.requests.size == 1 } }
        compose.runOnIdle { assertEquals(listOf(CloudExitScope.CLOUD), api.requests) }
    }

    private fun show(scope: CloudExitScope, api: FakeExitApi) {
        val connection = CloudAccountConnection(
            session = CloudAccountSession(AccountBackend()),
            accountExit = api,
            getGoogleIdToken = { "" },
        )
        compose.setContent {
            GalliPickTheme {
                if (scope == CloudExitScope.ACCOUNT) CloudAccountDeletionButton(connection)
                else CloudOnlyDeletionButton(connection)
            }
        }
    }
}
