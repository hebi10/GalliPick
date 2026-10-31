package com.hebi.gallipick.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.MediaFilter
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import com.hebi.gallipick.domain.MediaQuery
import com.hebi.gallipick.domain.ScanPhase
import com.hebi.gallipick.domain.ScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewExperienceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun populatedFilesQuickFiltersSearchAndSelection() {
        val now = System.currentTimeMillis()
        val longVideo = media(1, "long-video.mp4", kind = MediaKind.VIDEO, durationMs = 600_000, dateMs = now)
        val giantPhoto = media(2, "large-photo.jpg", sizeBytes = 1_073_741_824, dateMs = now)
        val oldVideo = media(3, "old-video.mp4", kind = MediaKind.VIDEO, dateMs = now - 730L * 24 * 60 * 60 * 1000)
        val recentPhoto = media(4, "recent-photo.jpg", dateMs = now)
        val all = listOf(longVideo, giantPhoto, oldVideo, recentPhoto)
        val state = mutableStateOf(AppUiState(access = MediaAccess.FULL, items = all, filtered = all))
        compose.setContent {
            GalliPickTheme {
                FilesScreenContent(state.value, false, {}, {}, {}, fileActions(
                    onFilter = { filter -> state.value = state.value.copy(filter = filter, filtered = MediaQuery.apply(all, filter), selected = emptySet()) },
                    onToggle = { uri -> state.value = state.value.copy(selected = state.value.selected + uri) },
                    onClear = { state.value = state.value.copy(selected = emptySet()) },
                ))
            }
        }
        compose.onNodeWithText("휴지통으로").assertDoesNotExist()
        compose.onNodeWithContentDescription("${longVideo.displayName} 선택").performClick()
        compose.onNodeWithText("1개 선택").assertIsDisplayed()
        saveScreenshot("review-files-populated")
        compose.onNodeWithText("10분 이상 영상").performClick()
        compose.onNodeWithText(longVideo.displayName).assertIsDisplayed()
        compose.onNodeWithText(giantPhoto.displayName).assertDoesNotExist()
        compose.onNodeWithText(oldVideo.displayName).assertDoesNotExist()
        compose.onNodeWithText("휴지통으로").assertDoesNotExist()
        compose.onNodeWithText("1 GiB 이상").performScrollTo().performClick()
        compose.onNodeWithText(giantPhoto.displayName).assertIsDisplayed()
        compose.onNodeWithText(longVideo.displayName).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(selected = setOf(longVideo.uri)) }
        compose.onNodeWithText("휴지통으로").assertDoesNotExist()
        compose.onNodeWithText("1년 이전 영상").performScrollTo().performClick()
        compose.onNodeWithText(oldVideo.displayName).assertIsDisplayed()
        compose.onNodeWithText(recentPhoto.displayName).assertDoesNotExist()
        compose.onNodeWithContentDescription("파일 이름 또는 폴더 검색").performTextInput("missing")
        compose.onNodeWithText("조건에 맞는 파일이 없습니다").assertIsDisplayed()
        compose.onNodeWithContentDescription("검색어 지우기").performClick()
        compose.onNodeWithText(oldVideo.displayName).assertIsDisplayed()
    }

    @Test fun fileGridReturnsToItsPositionAfterCatalogRevalidation() {
        val all = (0 until 60).map { media(it.toLong(), "file-%03d.jpg".format(it)) }
        val populated = AppUiState(access = MediaAccess.FULL, items = all, filtered = all)
        val state = mutableStateOf(populated)
        compose.setContent {
            GalliPickTheme { FilesScreenContent(state.value, false, {}, {}, {}, fileActions()) }
        }
        compose.onNodeWithContentDescription("미디어 파일 목록").performScrollToNode(hasText("file-040.jpg"))
        compose.onNodeWithText("file-040.jpg").assertIsDisplayed()
        compose.runOnIdle { state.value = populated.copy(loading = true, items = emptyList(), filtered = emptyList()) }
        compose.onNodeWithText("미디어를 불러오고 있습니다").assertIsDisplayed()
        compose.runOnIdle { state.value = populated }
        compose.onNodeWithText("file-040.jpg").assertIsDisplayed()
    }

    @Test fun catalogFailureShowsRetryInsteadOfStaleMediaOrEmptySuccess() {
        val item = media(1, "stale-photo.jpg")
        var retry = false
        val state = AppUiState(access = MediaAccess.FULL, items = listOf(item), filtered = listOf(item), selected = setOf(item.uri), catalogError = "미디어 제공자에서 응답하지 않았습니다.")
        compose.setContent {
            GalliPickTheme { FilesScreenContent(state, false, {}, {}, {}, fileActions(onRefresh = { retry = true })) }
        }
        compose.onNodeWithText(item.displayName).assertDoesNotExist()
        compose.onNodeWithText("휴지통으로").assertDoesNotExist()
        compose.onNodeWithText("조건에 맞는 파일이 없습니다").assertDoesNotExist()
        compose.onNodeWithText("파일을 불러오지 못했습니다").assertIsDisplayed()
        compose.onNodeWithText("다시 시도").performClick()
        compose.runOnIdle { assertTrue(retry) }
    }

    @Test fun duplicateKeeperChoiceKeepsProtectedCopyAndExposesScanProgress() {
        val defaultKeeper = media(1, "camera-original.jpg").copy(relativePath = "DCIM/Camera/")
        val chosenKeeper = media(2, "download-copy.jpg")
        val protectedCopy = media(3, "protected-copy.jpg").copy(isProtected = true)
        val group = DuplicateGroup("fixture-digest", listOf(defaultKeeper, chosenKeeper, protectedCopy))
        val state = mutableStateOf(AppUiState(
            access = MediaAccess.FULL, items = group.items, duplicates = listOf(group),
            duplicateKeepers = mapOf(group.hash to defaultKeeper.uri),
            scan = ScanState(phase = ScanPhase.RUNNING, completed = 3, skipped = 1, total = 8),
        ))
        compose.setContent {
            GalliPickTheme {
                DuplicatesScreenContent(state.value, {}, {}, {}, duplicateActions(onChoose = { chosenGroup, uri ->
                    state.value = state.value.copy(duplicateKeepers = state.value.duplicateKeepers + (chosenGroup.hash to uri))
                }))
            }
        }
        compose.onNodeWithContentDescription("중복 검사 진행률")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.5f, 0f..1f)))
        compose.onNodeWithText("휴지통으로").assertDoesNotExist()
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasContentDescription("${chosenKeeper.displayName} 보관본으로 지정"))
        compose.onNodeWithContentDescription("${chosenKeeper.displayName} 보관본으로 지정").performClick()
        compose.onNodeWithContentDescription("${chosenKeeper.displayName} 보관본 · 선택 제외").assertIsNotEnabled()
        saveScreenshot("review-duplicates-populated")
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasContentDescription("${defaultKeeper.displayName} 선택"))
        compose.onNodeWithContentDescription("${defaultKeeper.displayName} 선택").assertIsEnabled()
        compose.onNodeWithContentDescription("중복 파일 목록").performScrollToNode(hasContentDescription("${protectedCopy.displayName} 선택"))
        compose.onNodeWithContentDescription("${protectedCopy.displayName} 선택").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(chosenKeeper.uri, state.value.duplicateKeepers[group.hash]) }
    }

    @Test fun duplicatesProvidePermissionEntryPoint() {
        var permissionRequested = false
        compose.setContent {
            GalliPickTheme { DuplicatesScreenContent(AppUiState(access = MediaAccess.NONE), {}, {}, { permissionRequested = true }, duplicateActions()) }
        }
        compose.onNodeWithText("접근 허용").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(permissionRequested) }
        compose.onNodeWithText("검사 시작").assertIsNotEnabled()
    }

    @Test fun trashCapsSelectionBeforeRestoreAndRevalidatesTheCatalog() {
        val all = (0..500).map { media(it.toLong(), "trash-%03d.jpg".format(it)).copy(isTrashed = true) }
        val catalog = mutableStateOf(all)
        var requested = emptyList<MediaItem>()
        compose.setContent {
            GalliPickTheme { TrashScreen(catalog.value, { requested = it }, {}) }
        }
        compose.onNodeWithContentDescription("휴지통 전체 선택").performClick()
        compose.onNodeWithText("500개 선택").assertIsDisplayed()
        compose.onNodeWithText("선택한 파일 복원").performClick()
        compose.runOnIdle {
            assertEquals(500, requested.size)
            assertFalse(requested.any { it.uri == all.last().uri })
        }
        // Requesting approval alone does not clear a selection; the user may cancel Android's dialog.
        compose.onNodeWithText("500개 선택").assertIsDisplayed()
        compose.onNodeWithContentDescription("휴지통 파일 목록").performScrollToIndex(500)
        compose.onNodeWithContentDescription("${all.last().displayName} 복원 선택").performClick()
        compose.onNodeWithText("한 번에 최대 500개까지 복원할 수 있습니다. 선택을 해제한 뒤 다시 선택해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("500개 선택").assertIsDisplayed()
        saveScreenshot("review-trash-selection-limit")
        // A changed fingerprint is no longer the version that the user selected.
        compose.runOnIdle { catalog.value = all.mapIndexed { index, item -> if (index == 0) item.copy(generationModified = 1) else item } }
        compose.onNodeWithText("499개 선택").assertIsDisplayed()
        // A volume reset can reuse a URI and fingerprint for a different physical file.
        compose.runOnIdle { catalog.value = catalog.value.mapIndexed { index, item -> if (index == 1) item.copy(volumeVersion = "reset-epoch") else item } }
        compose.onNodeWithText("498개 선택").assertIsDisplayed()
        compose.onNodeWithText("선택한 파일 복원").performClick()
        compose.runOnIdle {
            assertEquals(498, requested.size)
            assertFalse(requested.any { it.uri == all[0].uri || it.uri == all[1].uri })
        }
        // Successful restored rows leave the trash catalog and disappear from the selection.
        compose.runOnIdle { catalog.value = listOf(all.last()) }
        compose.onNodeWithText("0개 선택").assertIsDisplayed()
        compose.onNodeWithText("선택한 파일 복원").assertIsNotEnabled()
    }

    private fun fileActions(
        onFilter: (MediaFilter) -> Unit = {},
        onToggle: (String) -> Unit = {},
        onClear: () -> Unit = {},
        onRefresh: () -> Unit = {},
    ) = FilesScreenActions(onFilter, {}, onToggle, onClear, {}, {}, { _, _ -> }, onRefresh)

    private fun duplicateActions(onChoose: (DuplicateGroup, String) -> Unit = { _, _ -> }) = DuplicatesScreenActions(
        {}, {}, {}, {}, {}, {}, onChoose, { _, _ -> }, {},
    )

    private fun media(
        id: Long,
        name: String,
        kind: MediaKind = MediaKind.PHOTO,
        sizeBytes: Long = 1024,
        durationMs: Long = 0,
        dateMs: Long = System.currentTimeMillis(),
    ) = MediaItem(
        uri = "content://media/external/images/media/${900_000 + id}", id = id, volume = "external_primary",
        displayName = name, kind = kind, mimeType = if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
        relativePath = "Pictures/ReviewFixtures/", sizeBytes = sizeBytes, width = 100, height = 100,
        durationMs = durationMs, dateTakenMs = dateMs, dateAddedMs = dateMs, dateModifiedMs = dateMs,
    )

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (outputPath?.let(::File) ?: File(instrumentation.targetContext.filesDir, "test-screenshots")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
