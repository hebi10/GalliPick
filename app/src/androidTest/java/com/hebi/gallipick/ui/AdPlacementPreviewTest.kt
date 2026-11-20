package com.hebi.gallipick.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdPlacementPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val removal = mutableStateOf(AdRemovalStatus.NOT_PURCHASED)
    private var confirmedExits = 0
    private var systemBacks = 0

    @Test fun freeSessionShowsOpeningOnceAndPinsSameSlotBelowAllRootTabs() {
        showApp()
        startupSlot().assertIsDisplayed()
        capture("opening-preview")
        compose.onNodeWithText("계속").performClick()
        startupSlot().assertDoesNotExist()

        val homeBannerBottom = bottomSlot().fetchSemanticsNode().boundsInRoot.bottom
        capture("home-preview")
        Destination.entries.forEach { destination ->
            tab(destination).performClick()
            startupSlot().assertDoesNotExist()
            val slot = bottomSlot().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(homeBannerBottom, slot.bottom, 1f)
            assertTrue(slot.bottom <= tab(destination).fetchSemanticsNode().boundsInRoot.top)
        }

        back()
        exitTitle().assertDoesNotExist()
        startupSlot().assertDoesNotExist()
        back()
        exitTitle().assertIsDisplayed()
        compose.onNodeWithTag("ad-preview-exit").assertIsDisplayed()
        capture("exit-preview")
        compose.onNodeWithText("취소").performClick()
        exitTitle().assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, confirmedExits) }
        back()
        compose.onNodeWithText("종료", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, confirmedExits) }
    }

    @Test fun verifiedAdRemovalHidesAllPlacementsAndLeavesRootBackToAndroid() {
        removal.value = AdRemovalStatus.VERIFIED_AD_FREE
        showApp()
        startupSlot().assertDoesNotExist()
        bottomSlot().assertDoesNotExist()
        capture("ad-free-home")
        Destination.entries.forEach { destination ->
            tab(destination).performClick()
            bottomSlot().assertDoesNotExist()
        }
        back()
        back()
        exitTitle().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, systemBacks)
            assertEquals(0, confirmedExits)
        }
    }

    @Test fun verifyingDoesNotFlashAdsAndActiveExitDialogClosesWhenAdRemovalIsVerified() {
        removal.value = AdRemovalStatus.VERIFYING
        showApp()
        startupSlot().assertDoesNotExist()
        bottomSlot().assertDoesNotExist()
        compose.runOnIdle { removal.value = AdRemovalStatus.NOT_PURCHASED }
        compose.onNodeWithText("계속").performClick()
        back()
        exitTitle().assertIsDisplayed()
        compose.runOnIdle { removal.value = AdRemovalStatus.VERIFIED_AD_FREE }
        exitTitle().assertDoesNotExist()
        bottomSlot().assertDoesNotExist()
        back()
        compose.runOnIdle { assertEquals(1, systemBacks) }
    }

    private fun showApp() {
        val viewModel = AppViewModel(compose.activity.application)
        val cloud = CloudAccountConnection(
            session = CloudAccountSession(object : CloudAccountBackend {
                override val account = MutableStateFlow<CloudAccountIdentity?>(null)
                override suspend fun signIn(googleIdToken: String) = Unit
                override fun signOut() = Unit
                override suspend fun clearGoogleState() = Unit
            }),
            getGoogleIdToken = { error("The placement test does not sign in") },
        )
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.addCallback(compose.activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { systemBacks++ }
            })
        }
        compose.setContent {
            GalliPickTheme {
                GalliPickApp(
                    viewModel, {}, {}, {}, {},
                    cloudContent = { _, _ -> Text("클라우드 계정 관리") },
                    cloudConnection = cloud,
                    adRemoval = removal.value,
                    adPreviewEnabled = true,
                    onConfirmExit = { confirmedExits++ },
                )
            }
        }
    }

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun tab(destination: Destination) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(destination.title),
    )

    private fun startupSlot() = compose.onNodeWithTag("ad-preview-startup")
    private fun bottomSlot() = compose.onNodeWithTag("ad-preview-bottom")
    private fun exitTitle() = compose.onNodeWithText("종료하시겠습니까?")

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureAdPlacements") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500) // The dialog window animates outside the Compose test clock.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(compose.activity.cacheDir, "ad-placement-captures").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
