package com.hebi.gallipick.ui

import android.content.ContentUris
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hebi.gallipick.AppThemeMode
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.Stage7aFixtureMedia
import com.hebi.gallipick.data.cloud.CloudAccountBackend
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudAccountIdentity
import com.hebi.gallipick.data.cloud.CloudAccountSession
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupAccessState
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupNamespace
import com.hebi.gallipick.data.cloud.CloudBackupNetworkFailure
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudBackupTransport
import com.hebi.gallipick.data.cloud.CloudBillingPlan
import com.hebi.gallipick.data.cloud.CloudGalleryPerformancePolicy
import com.hebi.gallipick.data.cloud.CloudGallerySnapshot
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.VerifiedGalleryMatch
import com.hebi.gallipick.data.cloud.CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES
import com.hebi.gallipick.data.cloud.cloudBackupNetworkError
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.MediaKind
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudFilesUnifiedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sha = "b".repeat(64)

    private val saved = CloudOriginal(
        uploadId = "$sha-1024", displayName = "cloud-holiday.jpg", mimeType = "image/jpeg",
        sizeBytes = 1024, sha256 = sha, verifiedAtMs = 1730000000000,
        sourceFolders = listOf("DCIM/Trips"), capturedAtMs = 1720000000000,
    )
    private val local = MediaItem(
        uri = "content://media/external/images/media/19001", id = 19001L,
        volume = "external_primary", displayName = "local-photo.jpg",
        kind = MediaKind.PHOTO, mimeType = "image/jpeg", relativePath = "DCIM/Camera/",
        sizeBytes = 1024, width = 100, height = 100, durationMs = 0,
        dateTakenMs = 1720100000000, dateAddedMs = 1720100000000,
        dateModifiedMs = 1720100000000,
    )

    @Test fun cloudSourceCanBeOpenedWithoutDeviceMediaPermission() {
        val opened = mutableStateOf(false)
        val state = AppUiState(access = MediaAccess.NONE)
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(400.dp, 850.dp)) {
                    FilesScreenContent(state = state, wide = false,
                        trash = {}, open = {}, requestAccess = {}, actions = actions(),
                        cloudFiles = cloudState(), openCloud = { opened.value = true })
                }
            }
        }
        chooseSource("cloud")
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithContentDescription("클라우드에 보관됨").assertExists()
        compose.onNodeWithText("local-photo.jpg").assertDoesNotExist()
        compose.onNodeWithText("cloud-holiday.jpg").performClick()
        compose.onNodeWithText("파일 상세").assertIsDisplayed()
        compose.onNodeWithText("사진 정보").assertDoesNotExist()
        compose.onNodeWithText("보관 완료").assertExists()
        compose.runOnIdle { assertFalse(opened.value) }
    }

    @Test fun deviceFilterDoesNotRenderCloudOriginals() {
        val state = AppUiState(access = MediaAccess.FULL, items = listOf(local), filtered = listOf(local))
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(state = state, wide = false,
                        trash = {}, open = {}, requestAccess = {}, actions = actions(),
                        cloudFiles = cloudState())
                }
            }
        }
        chooseSource("cloud")
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithText("local-photo.jpg").assertDoesNotExist()
        chooseSource("device")
        compose.onNodeWithText("local-photo.jpg").assertExists()
        compose.onNodeWithText("cloud-holiday.jpg").assertDoesNotExist()
        compose.onNode(
            hasText("기기") and hasAnyAncestor(hasContentDescription("미디어 파일 목록")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNodeWithContentDescription("클라우드에 보관됨").assertDoesNotExist()
        chooseSource("all")
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithText("local-photo.jpg").assertExists()
    }

    @Test fun emptyCloudCatalogInAllSourcesStillOffersMissingDevicePermission() {
        var requests = 0
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = AppUiState(access = MediaAccess.NONE), wide = false,
                        trash = {}, open = {}, requestAccess = { requests++ }, actions = actions(),
                        cloudFiles = cloudState().copy(snapshot = CloudGallerySnapshot("owner", emptyList())),
                    )
                }
            }
        }
        compose.onNodeWithText("미디어 접근이 필요합니다").assertIsDisplayed()
        compose.onNodeWithText("접근 허용").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun cloudFailureUsesOneDialogAndRetryKeepsDeviceFilesAvailable() {
        val cloud = mutableStateOf(cloudState().copy(snapshot = null, catalogIssue = CloudCatalogIssue.TIMEOUT))
        var retries = 0
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val capture = InstrumentationRegistry.getArguments().getString("captureCloudTimeout") == "true"
        val app = compose.activity.applicationContext
        val samples = if (capture) Stage7aFixtureMedia.seed(app, instrumentation.context.assets) else emptyList()
        val media = if (capture) samples.mapIndexed { index, sample ->
            local.copy(uri = sample.uri.toString(), id = ContentUris.parseId(sample.uri), displayName = sample.name,
                kind = sample.kind, mimeType = if (sample.kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
                durationMs = if (sample.kind == MediaKind.VIDEO) 4_000 else 0,
                dateTakenMs = local.dateTakenMs - index, isFavorite = sample.protected)
        } else listOf(local)
        val state = AppUiState(access = MediaAccess.FULL, items = media, filtered = media)
        try {
            compose.setContent {
                GalliPickTheme(AppThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize(), color = AppBackground) {
                        val viewport = if (capture) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier
                        Column(Modifier.fillMaxSize().then(viewport)) {
                            if (capture) GalliFilesHeader(false, {}, false, {})
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                FilesScreenContent(
                                    state = state, wide = false, trash = {}, open = {}, requestAccess = {}, actions = actions(),
                                    cloudFiles = cloud.value.copy(retry = {
                                        retries++
                                        cloud.value = cloudState()
                                    }),
                                )
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("클라우드 파일을 불러오지 못했습니다").assertIsDisplayed()
            compose.onNodeWithText("클라우드 서버 응답 시간이 초과되었습니다.").assertIsDisplayed()
            if (capture) {
                compose.waitForIdle()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(app.filesDir, "files-cloud-timeout.png").outputStream().use {
                        check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    screenshot.recycle()
                }
            }
            compose.onNodeWithText("다시 시도").performClick()
            compose.onNodeWithText("클라우드 파일을 불러오지 못했습니다").assertDoesNotExist()
            compose.onNodeWithText("클라우드 일부 항목을 확인하지 못했습니다.").assertDoesNotExist()
            compose.onNodeWithText(media.first().displayName).assertIsDisplayed()
            compose.runOnIdle { assertEquals(1, retries) }
        } finally {
            samples.forEach { sample -> app.contentResolver.delete(sample.uri, null, null) }
        }
    }

    @Test fun unentitledAccountOnlyShowsAnEmptyStateWhenCloudIsSelected() {
        val state = AppUiState(access = MediaAccess.FULL, items = listOf(local), filtered = listOf(local))
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = state, wide = false, trash = {}, open = {}, requestAccess = {}, actions = actions(),
                        cloudFiles = CloudFilesUiState(enabled = true, accountUid = "owner", detailAccess = DetailCloudAccess.PLAN_REQUIRED),
                    )
                }
            }
        }
        compose.onNodeWithText("클라우드 파일을 불러오지 못했습니다").assertDoesNotExist()
        compose.onNodeWithText("local-photo.jpg").assertIsDisplayed()
        chooseSource("cloud")
        compose.onNodeWithText("기기 파일 보기").performClick()
        compose.onNodeWithText("local-photo.jpg").assertIsDisplayed()
    }

    @Test fun dismissedCloudDialogStaysClosedOnRestoreButCanAppearOnTheNextFilesVisit() {
        val restoration = StateRestorationTester(compose)
        val filesVisible = mutableStateOf(true)
        val cloud = mutableStateOf(cloudState())
        val title = "클라우드 파일을 불러오지 못했습니다"
        restoration.setContent {
            GalliPickTheme {
                if (filesVisible.value) {
                    FilesScreenContent(
                        state = AppUiState(access = MediaAccess.FULL, items = listOf(local), filtered = listOf(local)),
                        wide = false, trash = {}, open = {}, requestAccess = {}, actions = actions(), cloudFiles = cloud.value,
                    )
                }
            }
        }
        chooseSource("device")
        compose.runOnIdle { cloud.value = cloudState().copy(snapshot = null, catalogIssue = CloudCatalogIssue.CONNECTION) }
        compose.onNodeWithText(title).assertDoesNotExist()
        chooseSource("all")
        compose.onNodeWithText("인터넷 연결을 확인해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { filesVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { filesVisible.value = true }
        compose.onNodeWithText(title).assertIsDisplayed()

        compose.runOnIdle { cloud.value = CloudFilesUiState(enabled = true, accountUid = "other") }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText("local-photo.jpg").assertIsDisplayed()
    }

    @Test fun deniedReadForAnEntitledAccountOffersAccountSettings() {
        var openedCloud = 0
        val state = AppUiState(access = MediaAccess.FULL, items = listOf(local), filtered = listOf(local))
        compose.setContent {
            GalliPickTheme {
                FilesScreenContent(
                    state = state, wide = false, trash = {}, open = {}, requestAccess = {}, actions = actions(),
                    cloudFiles = cloudState().copy(snapshot = null, catalogIssue = CloudCatalogIssue.ACCOUNT_RESTRICTED),
                    openCloud = { openedCloud++ },
                )
            }
        }
        compose.onNodeWithText("Google 계정의 클라우드 보관함 접근 권한을 확인해 주세요.").assertIsDisplayed()
        compose.onNodeWithText("계정 확인").performClick()
        compose.onNodeWithText("클라우드 파일을 불러오지 못했습니다").assertDoesNotExist()
        compose.onNodeWithText("local-photo.jpg").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, openedCloud) }
    }

    @Test fun verifiedBothUsesCloudViewerFromCloudFilterWithoutOpeningLocalDetails() {
        val device = local.copy(volumeVersion = "version-one", generationModified = 1)
        val proof = VerifiedGalleryMatch(
            "owner", device.uri, device.fingerprint, device.volumeVersion, saved.uploadId, saved.sha256,
        )
        val state = AppUiState(access = MediaAccess.FULL, items = listOf(device), filtered = listOf(device))
        val cloudOpened = mutableStateOf(false)
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = state, wide = false, trash = {}, open = {}, requestAccess = {},
                        actions = actions(),
                        cloudFiles = cloudState().copy(verifiedMatches = listOf(proof)),
                        openCloud = { cloudOpened.value = true },
                    )
                }
            }
        }
        chooseSource("cloud")
        compose.onNodeWithText("local-photo.jpg").assertExists()
        compose.onNodeWithContentDescription("클라우드에 보관됨").assertExists()
        compose.onNodeWithText("cloud-holiday.jpg").assertDoesNotExist()
        compose.onNodeWithText("local-photo.jpg").performClick()
        compose.onNodeWithText("파일 상세").assertIsDisplayed()
        compose.onNodeWithText("보관 완료").assertExists()
        compose.runOnIdle { assertFalse(cloudOpened.value) }
    }

    @Test fun cloudOnlyNeverExposesDeviceTrashForStaleSelectedFiles() {
        val device = local.copy(volumeVersion = "version-one", generationModified = 1)
        val proof = VerifiedGalleryMatch(
            "owner", device.uri, device.fingerprint, device.volumeVersion, saved.uploadId, saved.sha256,
        )
        var trashCount = 0
        val currentState = AppUiState(
            access = MediaAccess.FULL, items = listOf(device), filtered = listOf(device),
            selected = setOf(device.uri),
        )
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = currentState, wide = false, trash = { trashCount++ },
                        open = {}, requestAccess = {},
                        actions = actions(), // Deliberately does not clear selections on filter changes.
                        cloudFiles = cloudState().copy(verifiedMatches = listOf(proof)),
                    )
                }
            }
        }
        chooseSource("cloud")
        compose.onNodeWithText("휴지통으로", substring = true).assertDoesNotExist()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, trashCount) }
    }

    @Test fun unifiedBulkSelectionUsesOnlyShownEligibleDeviceFilesAndClearsOnSourceChange() {
        val page = CloudGalleryPerformancePolicy.PAGE_SIZE
        val devices = (1..page + 1).map { index ->
            local.copy(
                uri = "${local.uri}/$index", id = local.id + index, displayName = "local-$index.jpg",
                dateTakenMs = local.dateTakenMs + index, isFavorite = index == page + 1,
            )
        }
        val cloud = saved.copy(capturedAtMs = local.dateTakenMs + page + 2)
        val selected = mutableStateOf(emptySet<String>())
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = AppUiState(access = MediaAccess.FULL, items = devices,
                            filtered = devices, selected = selected.value),
                        wide = false, trash = {}, open = {}, requestAccess = {},
                        actions = actions().copy(
                            selectAll = { results -> selected.value = results.map { it.uri }.toSet() },
                            clearSelection = { selected.value = emptySet() },
                        ),
                        cloudFiles = cloudState().copy(snapshot = CloudGallerySnapshot("owner", listOf(cloud))),
                    )
                }
            }
        }
        compose.onNodeWithText("현재 결과 ${page - 2}개 선택").performClick()
        compose.runOnIdle {
            assertEquals(devices.sortedByDescending { it.dateTakenMs }.take(page - 1)
                .filterNot { it.isFavorite }.map { it.uri }.toSet(), selected.value)
        }
        chooseSource("device")
        compose.runOnIdle { assertTrue(selected.value.isEmpty()) }
        compose.onNodeWithText("현재 결과 ${page}개 선택").assertIsDisplayed()
        chooseSource("cloud")
        compose.onNodeWithText("현재 결과", substring = true).assertDoesNotExist()
    }

    @Test fun offlineCloudCatalogStaysBrowsableWithoutDevicePermission() {
        val cached = cloudState().copy(online = false, unmetered = false, cachedCatalog = true)
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = AppUiState(access = MediaAccess.NONE),
                        wide = false, trash = {}, open = {}, requestAccess = {},
                        actions = actions(), cloudFiles = cached,
                    )
                }
            }
        }
        chooseSource("cloud")
        compose.onNodeWithText("cloud-holiday.jpg").assertExists()
        compose.onNodeWithText("오프라인", substring = true).assertExists()
    }

    @Test fun meteredConnectionsOfferExplicitThumbnailPermission() {
        val metered = cloudState().copy(online = true, unmetered = false)
        compose.setContent {
            GalliPickTheme {
                Surface(Modifier.requiredSize(420.dp, 850.dp)) {
                    FilesScreenContent(
                        state = AppUiState(access = MediaAccess.NONE),
                        wide = false, trash = {}, open = {}, requestAccess = {},
                        actions = actions(), cloudFiles = metered,
                    )
                }
            }
        }
        chooseSource("cloud")
        compose.onNodeWithText("모바일 데이터 사용 중", substring = true).assertExists()
        compose.onNodeWithText("이번 화면에서 허용").performClick()
        compose.onNodeWithText("이번 화면에서 허용").assertDoesNotExist()
    }

    @Test fun catalogKeepsCacheForTemporaryFailureAndHidesItForPermissionDenied() {
        val connection = fakeCatalogConnection(MutableStateFlow(CloudAccountIdentity("owner", null, null)))
        var nextFailure: CloudBackupException? = null
        val latest = observeCatalog(connection) {
            nextFailure?.let { throw it }
            listOf(saved)
        }
        compose.waitUntil(5_000) { latest.get().snapshot?.originals == listOf(saved) }

        compose.runOnIdle {
            nextFailure = CloudBackupException(CloudBackupResult.NETWORK_ERROR)
            latest.get().retry()
        }
        compose.waitUntil(5_000) { latest.get().catalogIssue == CloudCatalogIssue.TEMPORARY_FAILURE }
        compose.runOnIdle {
            assertTrue(latest.get().cachedCatalog)
            assertEquals(listOf(saved), latest.get().snapshot?.originals)
            nextFailure = null
            latest.get().retry()
        }
        compose.waitUntil(5_000) { latest.get().catalogIssue == null && !latest.get().cachedCatalog }

        compose.runOnIdle {
            nextFailure = CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true)
            latest.get().retry()
        }
        compose.waitUntil(5_000) { latest.get().catalogIssue == CloudCatalogIssue.ACCOUNT_RESTRICTED }
        compose.runOnIdle {
            assertEquals(null, latest.get().snapshot)
            assertFalse(latest.get().cachedCatalog)
            assertTrue(latest.get().verifiedMatches.isEmpty())
        }
    }

    @Test fun explicitReadDenialSkipsCatalogButAReadOnlyAccountCanBrowse() {
        val accounts = MutableStateFlow<CloudAccountIdentity?>(CloudAccountIdentity("owner", null, null))
        val access = CloudBackupAccessState { accounts.value?.uid }
        val connection = fakeCatalogConnection(accounts, access)
        val plan = CloudBillingPlan(backupAvailable = true, maxOriginalBytes = CLOUD_PRODUCTION_ORIGINAL_MAX_BYTES,
            backupObjectPrefix = CloudBackupNamespace.LICENSE_TEST.objectPrefix, cloudReadAllowed = false)
        access.observe("owner", plan)
        var loads = 0
        val latest = observeCatalog(connection) { loads++; listOf(saved) }
        compose.runOnIdle {
            assertFalse(latest.get().mayReadCatalog)
            assertEquals(null, latest.get().catalogIssue)
            assertEquals(0, loads)
            access.observe("owner", plan.copy(cloudReadAllowed = true))
        }
        compose.waitUntil(5_000) { latest.get().snapshot?.originals == listOf(saved) }
        compose.runOnIdle {
            assertTrue(latest.get().mayReadCatalog)
            assertEquals(1, loads)
            assertFalse(checkNotNull(access.forAccount("owner")).allowsManualUpload(System.currentTimeMillis()))
        }
    }

    @Test fun theFirstAccountCheckCanReportTimeoutWithoutListingOriginals() {
        val accounts = MutableStateFlow<CloudAccountIdentity?>(CloudAccountIdentity("owner", null, null))
        val access = CloudBackupAccessState { accounts.value?.uid }
        val connection = fakeCatalogConnection(accounts, access)
        val latest = observeCatalog(connection) { error("Catalog must wait for a verified read check") }

        compose.runOnIdle { access.observeReadCheckFailure("owner", cloudBackupNetworkError(CloudBackupNetworkFailure.TIMEOUT)) }
        compose.waitUntil(5_000) { latest.get().catalogIssue == CloudCatalogIssue.TIMEOUT }
        compose.runOnIdle {
            assertFalse(latest.get().loading)
            assertFalse(latest.get().mayReadCatalog)
            assertEquals(null, latest.get().snapshot)
        }
    }

    @Test fun theFirstProductionAccountCheckReportsRestrictedAccess() {
        val accounts = MutableStateFlow<CloudAccountIdentity?>(CloudAccountIdentity("owner", null, null))
        val access = CloudBackupAccessState { accounts.value?.uid }
        val connection = fakeCatalogConnection(accounts, access)
        val latest = observeCatalog(connection) { error("Restricted accounts must not load the catalog") }

        compose.runOnIdle {
            access.observeReadCheckFailure("owner", CloudBackupException(CloudBackupResult.TEST_ACCESS_REQUIRED, permissionDenied = true))
        }
        compose.waitUntil(5_000) { latest.get().catalogIssue == CloudCatalogIssue.ACCOUNT_RESTRICTED }
        compose.runOnIdle { assertEquals(null, latest.get().snapshot) }
    }

    @Test fun changingTheAccountNeverPublishesThePreviousOwnersCatalog() {
        val account = MutableStateFlow<CloudAccountIdentity?>(CloudAccountIdentity("owner", null, null))
        val connection = fakeCatalogConnection(account)
        val nextOwnersFile = saved.copy(displayName = "second-owner.jpg")
        val staleStarted = CompletableDeferred<Unit>()
        val staleResult = CompletableDeferred<List<CloudOriginal>>()
        var oldAccountLoads = 0
        val latest = observeCatalog(connection) { uid ->
            when {
                uid != "owner" -> listOf(nextOwnersFile)
                oldAccountLoads++ == 0 -> listOf(saved)
                else -> {
                    staleStarted.complete(Unit)
                    withContext(NonCancellable) { staleResult.await() }
                }
            }
        }
        compose.waitUntil(5_000) { latest.get().snapshot?.originals == listOf(saved) }
        try {
            compose.runOnIdle { latest.get().retry() }
            compose.waitUntil(5_000) { staleStarted.isCompleted }
            compose.runOnIdle { account.value = CloudAccountIdentity("second-owner", null, null) }
            compose.waitUntil(5_000) { latest.get().accountUid == "second-owner" }
            compose.runOnIdle {
                assertFalse(latest.get().snapshot?.originals.orEmpty().contains(saved))
                assertTrue(latest.get().verifiedMatches.isEmpty())
            }
            compose.waitUntil(5_000) { latest.get().snapshot?.originals == listOf(nextOwnersFile) }
        } finally {
            staleResult.complete(listOf(saved))
        }
        compose.runOnIdle { assertEquals(listOf(nextOwnersFile), latest.get().snapshot?.originals) }
    }

    @Test fun libraryFolderSelectionOnlyShowsOriginalsInTheSelectedFolder() {
        val connection = fakeCatalogConnection(MutableStateFlow(CloudAccountIdentity("owner", null, null)))
        val otherHash = "c".repeat(64)
        val other = saved.copy(uploadId = "$otherHash-1024", sha256 = otherHash,
            displayName = "cloud-camera.jpg", sourceFolders = listOf("DCIM/Camera"))
        compose.setContent {
            GalliPickTheme {
                Box(Modifier.fillMaxWidth()) {
                    CloudLibrarySection(connection, onRestored = { error("Fixture cannot restore") },
                        loadOriginals = { _, _ -> listOf(saved, other) }, onOpenRestored = { _, _ -> })
                }
            }
        }
        compose.onNodeWithText("원본 폴더 · 전체").performClick()
        compose.onNodeWithText("DCIM/Camera").performClick()
        compose.onNodeWithText("cloud-camera.jpg").assertIsDisplayed()
        compose.onNodeWithText("cloud-holiday.jpg").assertDoesNotExist()

        compose.onNodeWithText("원본 폴더 · DCIM/Camera").performClick()
        compose.onNodeWithText("모든 원본 폴더").performClick()
        compose.onNodeWithText("cloud-holiday.jpg").assertIsDisplayed()
    }

    private fun observeCatalog(
        connection: CloudAccountConnection,
        load: suspend (String) -> List<CloudOriginal>,
    ): AtomicReference<CloudFilesUiState> {
        val latest = AtomicReference(CloudFilesUiState())
        compose.setContent {
            val state = rememberCloudFilesState(connection, active = true, loadOriginals = { _, uid -> load(uid) })
            SideEffect { latest.set(state) }
        }
        return latest
    }

    private fun fakeCatalogConnection(
        accounts: MutableStateFlow<CloudAccountIdentity?>,
        access: CloudBackupAccessState? = null,
    ): CloudAccountConnection {
        val backend = object : CloudAccountBackend {
            override val account = accounts
            override suspend fun signIn(googleIdToken: String) = Unit
            override fun signOut() { account.value = null }
            override suspend fun clearGoogleState() = Unit
        }
        val backup = CloudBackup(
            compose.activity, accounts, { accounts.value?.uid }, { error("Fixture cannot call Firebase") },
            CloudBackupTransport("https://example.invalid", emptySet()),
            productionAccess = access,
        )
        return CloudAccountConnection(CloudAccountSession(backend), backup, productionBackupAccess = access) { error("No account picker") }
    }

    private fun chooseSource(source: String) {
        compose.onNodeWithTag("files-source-filter").performScrollTo().performClick()
        compose.onNodeWithTag("files-source-$source").performClick()
    }

    private fun cloudState() = CloudFilesUiState(
        enabled = true, accountUid = "owner",
        snapshot = CloudGallerySnapshot("owner", listOf(saved)),
    )

    private fun actions() = FilesScreenActions(
        setFilter = {}, selectAll = {}, toggleSelection = {}, clearSelection = {},
        savePreset = {}, deletePreset = {}, setProtected = { _, _ -> }, refresh = {},
    )
}
