package com.hebi.gallipick.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudBackupTransport
import com.hebi.gallipick.data.cloud.CloudSecretSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretCloudPasswordSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val owner = CloudAccountIdentity("vault-account", null, null)
    private val account = MutableStateFlow<CloudAccountIdentity?>(owner)

    @Test fun registersOnlyMatchingValidPasswordsAndClosesTheIssuedSessionBeforeReturning() {
        var opened = 0
        var closed = 0
        var registered = 0
        showSettings(
            openSession = { uid, _, setup ->
                assertEquals(owner.uid, uid)
                assertTrue(setup)
                opened++
                fakeSession { closed++ }
            },
            onRegistered = {
                assertEquals(1, closed)
                registered++
            },
        )

        submit().performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        fill("1234567", "1234567")
        submit().performScrollTo().assertIsNotEnabled()
        fill("가".repeat(86), "가".repeat(86))
        submit().performScrollTo().assertIsNotEnabled()
        fill("예시 비밀번호 하나", "일치하지 않는 확인")
        compose.onNodeWithText("비밀번호가 일치하지 않습니다.").performScrollTo().assertIsDisplayed()
        submit().performScrollTo().assertIsNotEnabled()
        fill("예시 비밀번호 하나", "예시 비밀번호 하나")
        submit().performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, closed)
            assertEquals(1, registered)
        }
        passwordField("비밀번호").assertDoesNotExist()
        compose.onNodeWithText("보관함 잠금 해제").assertDoesNotExist()
    }

    @Test fun existingPasswordIsShownAsConfiguredWithoutOpeningAnUnlockForm() {
        showSettings(readConfigured = { true })

        compose.onNodeWithText("비밀번호가 설정되어 있습니다.").assertIsDisplayed()
        passwordField("비밀번호").assertDoesNotExist()
        submit().assertDoesNotExist()
    }

    @Test fun rechecksRegistrationStatusWhenTheServerMayAlreadyHaveSavedIt() {
        var saved = false
        var statusReads = 0
        var successCallbacks = 0
        showSettings(
            readConfigured = { statusReads++; saved },
            openSession = { _, _, _ ->
                saved = true
                throw CloudBackupException(CloudBackupResult.NETWORK_ERROR)
            },
            onRegistered = { successCallbacks++ },
        )

        fill("예시 비밀번호 하나", "예시 비밀번호 하나")
        submit().performScrollTo().performClick()

        compose.onNodeWithText("비밀번호가 설정되어 있습니다.").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, statusReads)
            assertEquals(0, successCallbacks)
        }
    }

    @Test fun signInUsesTheExistingGoogleAccountFlowBeforeCheckingStatus() {
        account.value = null
        var signIns = 0
        var reads = 0
        showSettings(
            onSignIn = { signIns++ },
            readConfigured = {
                assertEquals(owner.uid, it)
                reads++
                false
            },
        )

        compose.onNodeWithText("Google로 로그인").performClick()
        passwordField("비밀번호").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, signIns)
            assertEquals(1, reads)
        }
    }

    @Test fun permissionFailureShowsAccountAvailabilityAndConnectionFailureOffersRetry() {
        var reads = 0
        showSettings(readConfigured = {
            when (++reads) {
                1 -> throw CloudBackupException(CloudBackupResult.NETWORK_ERROR)
                else -> throw CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)
            }
        })

        compose.onNodeWithText("다시 시도").performClick()
        compose.onNodeWithText("이 계정은 시크릿 클라우드를 이용할 수 없습니다.").assertIsDisplayed()
        passwordField("비밀번호").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun aWritePermissionFailureExplainsThatRegistrationIsUnavailable() {
        showSettings(openSession = { _, _, _ ->
            throw CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)
        })

        fill("예시 비밀번호 하나", "예시 비밀번호 하나")
        submit().performScrollTo().performClick()

        compose.onNodeWithText("현재 계정은 시크릿 클라우드 비밀번호를 설정할 수 없습니다.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun passwordsClearWhenTheAccountOrForegroundChanges() {
        showSettings()
        fill("예시 비밀번호 하나", "예시 비밀번호 하나")

        compose.runOnIdle { account.value = CloudAccountIdentity("another-vault-account", null, null) }
        submit().performScrollTo().assertIsNotEnabled()
        fill("예시 비밀번호 둘", "예시 비밀번호 둘")

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        submit().performScrollTo().assertIsNotEnabled()
    }

    private fun passwordField(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun submit() = compose.onNode(hasText("비밀번호 설정") and hasClickAction())

    private fun fill(password: String, confirmation: String) {
        passwordField("비밀번호").performScrollTo().performTextReplacement(password)
        passwordField("비밀번호 확인").performScrollTo().performTextReplacement(confirmation)
    }

    private fun fakeSession(onClose: () -> Unit) = CloudSecretSession(
        CloudBackup(
            compose.activity, account, { account.value?.uid }, { error("Fixture cannot call Firebase") },
            CloudBackupTransport("https://example.invalid", emptySet()),
        ),
        SystemClock.elapsedRealtime() + 60_000,
        onClose,
    )

    private fun showSettings(
        readConfigured: suspend (String) -> Boolean = { false },
        openSession: suspend (String, String, Boolean) -> CloudSecretSession = { _, _, _ -> error("Unexpected vault request") },
        onSignIn: () -> Unit = {},
        onRegistered: () -> Unit = {},
    ) {
        val backend = object : CloudAccountBackend {
            override val account = this@SecretCloudPasswordSettingsTest.account
            override suspend fun signIn(googleIdToken: String) { account.value = owner; onSignIn() }
            override fun signOut() { account.value = null }
            override suspend fun clearGoogleState() = Unit
        }
        val connection = CloudAccountConnection(CloudAccountSession(backend)) { "test-credential" }
        compose.setContent {
            GalliPickTheme {
                SecretCloudPasswordSettings(connection, {}, onRegistered, readConfigured = readConfigured, openSession = openSession)
            }
        }
    }
}
