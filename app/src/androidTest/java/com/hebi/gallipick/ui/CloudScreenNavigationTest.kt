package com.hebi.gallipick.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudBilling
import com.hebi.gallipick.data.cloud.CloudBillingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudScreenNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cloudRouteOpensAndReturnsToAccountOverview() {
        val backend = FakeAccountBackend("test-user-1")
        showCloud(backend)

        compose.onNodeWithTag("cloud-route-BILLING").performClick()
        compose.onNodeWithText("screen-BILLING").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
    }

    @Test
    fun newAccountSignInStaysOnCloudOverviewUntilBillingIsTapped() {
        val backend = FakeAccountBackend("new-account")
        backend.active.value = null
        val connection = showCloud(backend)
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()

        runBlocking { connection.session.chooseAccount { "test-credential" } }
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
        compose.onNodeWithText("회원 탈퇴").performScrollTo().performClick()
        compose.onNodeWithText("GalliPick 회원 탈퇴").assertIsDisplayed()
        compose.onNodeWithText("탈퇴 요청 메일 작성").assertExists()
        compose.onNodeWithText("돌아가기").performClick()
        compose.onNodeWithText("클라우드 데이터 삭제 요청").assertExists()
        compose.onNodeWithTag("cloud-route-BILLING").performClick()
        compose.onNodeWithText("screen-BILLING").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
    }

    @Test
    fun cloudShowsGoogleSignInProgressAndReturnsToOverviewWhenReady() {
        val backend = FakeAccountBackend("new-account")
        backend.active.value = null
        val connection = showCloud(backend)
        val entered = CompletableDeferred<Unit>()
        val credential = CompletableDeferred<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val login = scope.launch {
                connection.session.chooseAccount {
                    entered.complete(Unit)
                    credential.await()
                }
            }
            runBlocking { entered.await() }
            compose.onNodeWithTag("cloud-account-loading").assertIsDisplayed()
            compose.onNodeWithText("Google 계정으로 로그인하는 중입니다.").assertIsDisplayed()

            credential.complete("test-credential")
            runBlocking { login.join() }
            compose.onNodeWithTag("cloud-account-loading").assertDoesNotExist()
            compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
            compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unsuccessfulNewAccountSignInCannotOpenBilling() {
        val backend = FakeAccountBackend("new-account")
        backend.active.value = null
        val connection = showCloud(backend)

        runBlocking {
            connection.session.chooseAccount {
                throw com.hebi.gallipick.data.cloud.CloudAccountFailure(
                    com.hebi.gallipick.data.cloud.CloudAccountNotice.CANCELLED,
                )
            }
        }
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
    }

    @Test
    fun newAccountWithoutBillingAccessStaysOnOverview() {
        val backend = FakeAccountBackend("new-account")
        backend.active.value = null
        val connection = showCloud(backend, billingAvailable = false)

        runBlocking { connection.session.chooseAccount { "test-credential" } }
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
    }

    @Test
    fun signedOutCanOpenBillingAndReturnToCloudOverview() {
        val backend = FakeAccountBackend("test-user-1")
        backend.active.value = null
        showCloud(backend)

        compose.onNodeWithTag("cloud-route-BILLING").performScrollTo().performClick()
        compose.onNodeWithText("screen-BILLING").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
    }

    @Test
    fun signedInWithoutBillingShowsUnavailableStateRatherThanCheckout() {
        val backend = FakeAccountBackend("test-user-1")
        showCloud(backend, billingAvailable = false)

        compose.onNodeWithText("요금제·결제").assertExists()
        compose.onNodeWithText("이 빌드에서는 결제 기능을 사용할 수 없습니다.").assertExists()
        compose.onNodeWithTag("cloud-route-BILLING").assertDoesNotExist()
    }

    @Test
    fun changingOrRemovingAccountClearsPreviousCloudRoute() {
        val backend = FakeAccountBackend("test-user-1")
        showCloud(backend)

        compose.onNodeWithTag("cloud-route-BILLING").performClick()
        compose.onNodeWithText("screen-BILLING").assertIsDisplayed()

        compose.runOnIdle {
            backend.active.value = CloudAccountIdentity("test-user-2", null, null)
        }
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()

        compose.onNodeWithTag("cloud-route-BILLING").performScrollTo().performClick()
        compose.onNodeWithText("screen-BILLING").assertIsDisplayed()
        compose.runOnIdle { backend.active.value = null }
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
        compose.onNodeWithTag("cloud-route-BILLING").assertExists()
    }

    @Test
    fun secretRouteRequiresBothTheSettingAndASignedInAccount() {
        val backend = FakeAccountBackend("test-user-1")
        val enabled = mutableStateOf(false)
        showCloud(backend, billingAvailable = false, showSecretCloud = { enabled.value })
        compose.onNodeWithTag("cloud-route-SECRET").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("cloud-route-SECRET").performClick()
        compose.onNodeWithText("screen-SECRET").assertIsDisplayed()
        compose.runOnIdle { backend.active.value = null }
        compose.onNodeWithText("screen-SECRET").assertDoesNotExist()
        compose.onNodeWithTag("cloud-route-SECRET").assertDoesNotExist()
    }

    @Test
    fun adRemovalShortcutOpensBillingAfterSignInAndBackReturnsToOrigin() {
        val backend = FakeAccountBackend("test-user-1")
        backend.active.value = null
        val billingVisibility = mutableListOf<Boolean>()
        var returnsToOrigin = 0
        showCloud(
            backend,
            onBackFromAdRemoval = { returnsToOrigin++ },
            onBillingScreenVisible = { billingVisibility.add(it) },
        )

        compose.onNodeWithText("screen-BILLING_SIGN_IN").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), billingVisibility)
            backend.active.value = CloudAccountIdentity("test-user-1", null, null)
        }
        compose.onNodeWithText("screen-BILLING").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(true), billingVisibility)
            backend.active.value = null
        }
        compose.onNodeWithText("screen-BILLING_SIGN_IN").assertIsDisplayed()
        compose.onNodeWithText("screen-BILLING").assertDoesNotExist()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.runOnIdle {
            assertEquals(listOf(true, false), billingVisibility)
            assertEquals(1, returnsToOrigin)
        }
    }

    private fun showCloud(
        backend: FakeAccountBackend,
        billingAvailable: Boolean = true,
        onBackFromAdRemoval: (() -> Unit)? = null,
        onBillingScreenVisible: (Boolean) -> Unit = {},
        showSecretCloud: () -> Boolean = { false },
    ): CloudAccountConnection {
        val billing = if (billingAvailable) object : CloudBilling {
            override fun open(uid: String): CloudBillingSession = error("The route test cannot start purchases")
        } else null
        val connection = CloudAccountConnection(
            session = CloudAccountSession(backend),
            billing = billing,
            getGoogleIdToken = { "" },
        )
        compose.setContent {
            GalliPickTheme {
                CloudScreen(connection, onBackFromAdRemoval, onBillingScreenVisible, showSecretCloud()) { route ->
                    Text("screen-${route.name}")
                }
            }
        }
        return connection
    }

    private class FakeAccountBackend(private val uid: String) : CloudAccountBackend {
        val active = MutableStateFlow<CloudAccountIdentity?>(CloudAccountIdentity(uid, null, null))
        override val account: StateFlow<CloudAccountIdentity?> = active
        override suspend fun signIn(googleIdToken: String) {
            active.value = CloudAccountIdentity(uid, null, null)
        }
        override fun signOut() { active.value = null }
        override suspend fun clearGoogleState() = Unit
    }
}
