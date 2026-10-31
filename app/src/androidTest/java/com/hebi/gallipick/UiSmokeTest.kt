package com.hebi.gallipick

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
        saveLauncher()
        compose.onAllNodesWithText("파일").onFirst().performClick()
        compose.onNodeWithContentDescription("필터와 정렬").performClick()
        compose.onNodeWithText("파일 필터").assertIsDisplayed()
        saveScreenshot("filters")
        compose.onNodeWithText("취소").performClick()
        saveScreenshot("files")
        compose.onAllNodesWithText("중복").onFirst().performClick()
        compose.onNodeWithText("완전히 같은 파일").assertIsDisplayed()
        saveScreenshot("duplicates")
        compose.onAllNodesWithText("정리").onFirst().performClick()
        saveScreenshot("cleanup")
        compose.onAllNodesWithText("설정").onFirst().performClick()
        saveScreenshot("settings")
        compose.onNodeWithText("개인정보").performScrollTo().assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val node = if (name == "filters") compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (outputPath?.let(::File) ?: File(compose.activity.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun saveLauncher() {
        val icon = compose.activity.packageManager.getApplicationIcon(compose.activity.packageName)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(Canvas(bitmap))
        val outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (outputPath?.let(::File) ?: File(compose.activity.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "launcher.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (Build.VERSION.SDK_INT >= 33 && icon is AdaptiveIconDrawable) {
            icon.monochrome?.let { monochrome ->
                monochrome.setTint(0xFF25483F.toInt())
                val themed = AdaptiveIconDrawable(ColorDrawable(0xFFCDE6D8.toInt()), monochrome)
                val themedBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                themed.setBounds(0, 0, 512, 512)
                themed.draw(Canvas(themedBitmap))
                File(directory, "launcher-themed.png").outputStream().use { themedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                themedBitmap.recycle()
            }
        }
    }
}
