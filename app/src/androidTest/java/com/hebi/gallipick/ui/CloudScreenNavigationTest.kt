package com.hebi.gallipick.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithText("뒤로").performClick()
        compose.onNodeWithText("screen-OVERVIEW").assertIsDisplayed()
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

    private fun showCloud(backend: FakeAccountBackend) {
        val billing = object : CloudBilling {
            override fun open(uid: String): CloudBillingSession = error("The route test cannot start purchases")
        }
        val connection = CloudAccountConnection(
            session = CloudAccountSession(backend),
            billing = billing,
            getGoogleIdToken = { "" },
        )
        compose.setContent {
            GalliPickTheme {
                CloudScreen(connection) { route -> Text("screen-${route.name}") }
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
