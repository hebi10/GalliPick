package com.hebi.gallipick.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.MediaFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage6AccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun filePermissionRetryAndEmptyFiltersRemainReachableInShortLargeTextWindow() {
        val state = mutableStateOf(AppUiState(access = MediaAccess.NONE))
        var requestedAccess = 0
        var retried = 0
        var resetFilter: MediaFilter? = null
        val actions = FilesScreenActions(
            { resetFilter = it }, {}, {}, {}, {}, {}, { _, _ -> }, { retried++ },
        )
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                    Box(Modifier.requiredSize(320.dp, 260.dp)) {
                        FilesScreenContent(state.value, false, {}, {}, { requestedAccess++ }, actions)
                    }
                }
            }
        }

        compose.onNodeWithText("접근 허용").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, requestedAccess) }

        compose.runOnIdle { state.value = AppUiState(access = MediaAccess.FULL, catalogError = "읽기 오류") }
        compose.onNodeWithText("다시 시도").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retried) }

        compose.runOnIdle { state.value = AppUiState(access = MediaAccess.FULL) }
        compose.onNodeWithText("필터 초기화").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(MediaFilter(), resetFilter) }
    }

    @Test fun screenTitlesAndEmptyStatesAreNavigableAsAccessibilityHeadings() {
        compose.setContent {
            GalliPickTheme {
                Column {
                    SectionTitle("접근성 화면 제목", "보조 설명")
                    EmptyState("접근성 빈 상태", "안내 본문")
                }
            }
        }
        val heading = SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
        compose.onNodeWithText("접근성 화면 제목").assert(heading)
        compose.onNodeWithText("접근성 빈 상태").assert(heading)
    }

    @Test fun settingsHeadingsAndPermissionActionsWorkAtLargeText() {
        val vm = AppViewModel(compose.activity.application)
        var permissionClicks = 0
        var settingsClicks = 0
        compose.setContent {
            GalliPickTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                    Box(Modifier.requiredSize(320.dp, 640.dp)) {
                        SettingsScreen(
                            AppUiState(access = MediaAccess.PARTIAL), vm,
                            { permissionClicks++ }, { settingsClicks++ }, {},
                        )
                    }
                }
            }
        }

        val heading = SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
        listOf("화면 테마", "미디어 접근", "휴지통", "보호 폴더", "분석 캐시", "개인정보").forEach { title ->
            compose.onNodeWithText(title).performScrollTo().assert(heading)
        }
        compose.onNodeWithText("접근 범위 변경").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("앱 권한 설정").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, permissionClicks)
            assertEquals(1, settingsClicks)
        }
        assertTrue("Device permission settings must be represented in the UI", settingsClicks > 0)
    }
}
