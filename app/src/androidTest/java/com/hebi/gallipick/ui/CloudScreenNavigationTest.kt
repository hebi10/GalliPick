package com.hebi.gallipick.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudBilling
import com.hebi.gallipick.data.cloud.CloudBillingSession
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun signedOutShowsBillingInformationButDoesNotOpenPayment() {
        val backend = FakeAccountBackend("test-user-1")
        backend.active.value = null
        showCloud(backend)

        compose.onNodeWithText("요금제·결제").assertExists()
        compose.onNodeWithText("Google 로그인 후 요금제와 구독 상태를 확인할 수 있습니다.").assertExists()
        compose.onNodeWithTag("cloud-route-BILLING").assertDoesNotExist()
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

        compose.runOnIdle { backend.active.value = null }
        compose.onNodeWithTag("cloud-route-BILLING").assertDoesNotExist()
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
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
    ) {
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
                CloudScreen(connection, onBackFromAdRemoval, onBillingScreenVisible) { route ->
                    Text("screen-${route.name}")
                }
            }
        }
    }

    private class FakeAccountBackend(uid: String) : CloudAccountBackend {
        val active = MutableStateFlow<CloudAccountIdentity?>(CloudAccountIdentity(uid, null, null))
        override val account: StateFlow<CloudAccountIdentity?> = active
        override suspend fun signIn(googleIdToken: String) = Unit
        override fun signOut() { active.value = null }
        override suspend fun clearGoogleState() = Unit
    }
}
