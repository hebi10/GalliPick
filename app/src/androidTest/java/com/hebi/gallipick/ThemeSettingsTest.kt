package com.hebi.gallipick

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun themeChangesImmediatelyAndSurvivesActivityRecreation() {
        val prefs = compose.activity.getSharedPreferences("filters", Context.MODE_PRIVATE)
        val original = prefs.getString("theme_mode", null)
        try {
            compose.onAllNodesWithText("설정").onFirst().performClick()
            compose.onNodeWithText("다크 모드").performScrollTo().performClick().assertIsSelected()
            assertEquals(AppThemeMode.DARK, AppSettings(compose.activity).loadThemeMode())
            assertTrue("Dark mode should update the actual background immediately", backgroundLuminance() < .1f)
            saveScreenshot("theme-dark-settings")
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithText("다크 모드").performScrollTo().assertIsSelected()
            assertTrue(backgroundLuminance() < .1f)
            compose.onAllNodesWithText("파일").onFirst().performClick()
            saveScreenshot("theme-dark-files")
            compose.onAllNodesWithText("설정").onFirst().performClick()
            compose.onNodeWithText("라이트 모드").performScrollTo().performClick().assertIsSelected()
            assertEquals(AppThemeMode.LIGHT, AppSettings(compose.activity).loadThemeMode())
            assertTrue(backgroundLuminance() > .8f)
        } finally {
            prefs.edit().apply { if (original == null) remove("theme_mode") else putString("theme_mode", original) }.commit()
        }
    }

    private fun backgroundLuminance(): Float {
        compose.waitForIdle()
        return compose.onRoot().captureToImage().toPixelMap()[1, 1].luminance()
    }

    private fun saveScreenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (path?.let(::File) ?: File(compose.activity.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
