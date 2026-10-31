package com.hebi.gallipick

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeNavigationAndFiltersOpen() {
        compose.onNodeWithText("GalliPick").assertIsDisplayed()
        saveScreenshot("home")
        compose.onAllNodesWithText("파일").onFirst().performClick()
        compose.onNodeWithContentDescription("필터와 정렬").performClick()
        compose.onNodeWithText("파일 필터").assertIsDisplayed()
        saveScreenshot("filters")
        compose.onNodeWithText("취소").performClick()
        compose.onAllNodesWithText("중복").onFirst().performClick()
        compose.onNodeWithText("완전히 같은 파일").assertIsDisplayed()
        compose.onAllNodesWithText("정리").onFirst().performClick()
        compose.onAllNodesWithText("설정").onFirst().performClick()
        compose.onNodeWithText("개인정보").performScrollTo().assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        val outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (outputPath?.let(::File) ?: File(compose.activity.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
