package com.hebi.gallipick.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.MediaRepository
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudBackup
import com.hebi.gallipick.data.cloud.CloudBackupException
import com.hebi.gallipick.data.cloud.CloudBackupNetworkFailure
import com.hebi.gallipick.data.cloud.CloudBackupResult
import com.hebi.gallipick.data.cloud.CloudGalleryMatchPolicy
import com.hebi.gallipick.data.cloud.CloudGallerySnapshot
import com.hebi.gallipick.data.cloud.CloudOriginal
import com.hebi.gallipick.data.cloud.CloudSpaceAnalysis
import com.hebi.gallipick.data.cloud.VerifiedGalleryMatch
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal enum class DetailCloudAccess { UNAVAILABLE, SIGN_IN, CHECKING, CHECK_ACCESS, PLAN_REQUIRED, READY }

internal enum class CloudCatalogIssue(val blocksCachedCatalog: Boolean) {
    ACCOUNT_RESTRICTED(true), AUTH_REQUIRED(true), CONNECTION(false), TIMEOUT(false), TEMPORARY_FAILURE(false),
}

internal fun cloudCatalogIssue(error: Exception): CloudCatalogIssue = when {
    error is CloudBackupException && error.permissionDenied -> CloudCatalogIssue.ACCOUNT_RESTRICTED
    error is CloudBackupException && error.result in setOf(
        CloudBackupResult.TEST_ACCESS_REQUIRED, CloudBackupResult.ACCOUNT_CHANGED,
    ) -> CloudCatalogIssue.AUTH_REQUIRED
    error is CloudBackupException && error.networkFailure == CloudBackupNetworkFailure.CONNECTION -> CloudCatalogIssue.CONNECTION
    error is CloudBackupException && error.networkFailure == CloudBackupNetworkFailure.TIMEOUT -> CloudCatalogIssue.TIMEOUT
    else -> CloudCatalogIssue.TEMPORARY_FAILURE
}

internal fun defaultDetailCloudAccess(backup: CloudBackup?, accountUid: String?): DetailCloudAccess =
    if (backup != null && accountUid != null) DetailCloudAccess.READY else DetailCloudAccess.UNAVAILABLE

internal data class CloudFilesUiState(
    val enabled: Boolean = false,
    val accountUid: String? = null,
    val backup: CloudBackup? = null,
    val snapshot: CloudGallerySnapshot? = null,
    val loading: Boolean = false,
    val catalogIssue: CloudCatalogIssue? = null,
    val online: Boolean = true,
    val unmetered: Boolean = true,
    val cachedCatalog: Boolean = false,
    val retry: () -> Unit = {},
    val verifiedMatches: List<VerifiedGalleryMatch> = emptyList(),
    val matchChecking: Boolean = false,
    val matchChecked: Int = 0,
    val matchTotal: Int = 0,
    val matchFailed: Boolean = false,
    val matchNext: () -> Unit = {},
    val detailAccess: DetailCloudAccess = defaultDetailCloudAccess(backup, accountUid),
    val accountEmail: String? = null,
    val detailNotice: String? = null,
    val mayReadCatalog: Boolean = snapshot != null || catalogIssue != null,
)

/** Retains account-scoped catalog state across tab switches, without loading local files from the network. */
@Composable
internal fun rememberCloudFilesState(
    connection: CloudAccountConnection?,
    active: Boolean,
    repository: MediaRepository? = null,
    deviceItems: List<MediaItem> = emptyList(),
    loadOriginals: suspend (CloudBackup, String) -> List<CloudOriginal> = CloudBackup::listOriginals,
): CloudFilesUiState {
    if (connection == null) return CloudFilesUiState()
    val identity by connection.session.account.collectAsStateWithLifecycle()
    val uid = identity?.uid
    val status by connection.session.status.collectAsStateWithLifecycle()
    val configuredBackup = connection.backupAvailability
    val observation = configuredBackup?.collectAsStateWithLifecycle()?.value?.takeIf { it.uid == uid }
    val waitingForAccess = uid != null && configuredBackup != null && observation == null && connection.billing != null
    val backup = connection.backup.takeUnless { waitingForAccess }
    val accessIssue = observation?.checkFailure?.let(::cloudCatalogIssue)?.takeUnless {
        // The development server rejects accounts outside the pilot before returning a plan.
        !connection.usesProductionBackupPlan && it == CloudCatalogIssue.ACCOUNT_RESTRICTED &&
            observation?.mayReadCatalog != true
    }
    var accessCheckWaited by remember(connection, uid, waitingForAccess) { mutableStateOf(false) }
    LaunchedEffect(connection, uid, waitingForAccess) {
        if (waitingForAccess) {
            delay(5_000)
            accessCheckWaited = true
        }
    }
    val detailAccess = when {
        !connection.session.available -> DetailCloudAccess.UNAVAILABLE
        status.operation != null -> DetailCloudAccess.CHECKING
        uid == null && (connection.billing != null || backup != null) -> DetailCloudAccess.SIGN_IN
        uid != null && backup?.accessFor(uid)?.allowsManualUpload(System.currentTimeMillis()) == true ->
            DetailCloudAccess.READY
        accessIssue != null -> DetailCloudAccess.CHECK_ACCESS
        waitingForAccess -> if (accessCheckWaited) DetailCloudAccess.CHECK_ACCESS else DetailCloudAccess.CHECKING
        uid != null && connection.billing != null -> DetailCloudAccess.PLAN_REQUIRED
        else -> DetailCloudAccess.UNAVAILABLE
    }
    val accountLabel = identity?.transferAccountLabel
    val detailNotice = status.displayText(uid != null)
        ?: "클라우드 이용 권한을 아직 확인하지 못했습니다.".takeIf { detailAccess == DetailCloudAccess.CHECK_ACCESS }
    val namespace = uid?.let { backup?.accessFor(it)?.namespace }
    if (backup == null || observation?.mayReadCatalog == false) {
        var retryAccess by remember(connection, uid) { mutableStateOf(false) }
        LaunchedEffect(connection, uid, active, retryAccess) {
            if (!retryAccess || uid == null) return@LaunchedEffect
            try {
                if (active) connection.refreshBackupAccess(uid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The access observation carries this check's failure.
            } finally {
                retryAccess = false
            }
        }
        LaunchedEffect(uid) { CloudPhotoThumbnailStore.activate(null) }
        return CloudFilesUiState(
            enabled = configuredBackup != null || backup != null,
            accountUid = uid,
            loading = retryAccess || waitingForAccess,
            catalogIssue = accessIssue,
            retry = { retryAccess = true },
            detailAccess = detailAccess, accountEmail = accountLabel, detailNotice = detailNotice,
            mayReadCatalog = observation?.mayReadCatalog == true,
        )
    }
    val network = rememberCloudNetworkStatus(enabled = true)
    val revision by backup.catalogRevision.collectAsStateWithLifecycle()
    val recentUpload by backup.recentVerifiedUpload.collectAsStateWithLifecycle()
    var retryKey by remember(uid, namespace) { mutableIntStateOf(0) }
    var seenOffline by remember(uid, namespace) { mutableStateOf(false) }
    LaunchedEffect(uid, namespace, network.online) {
        if (!network.online) seenOffline = true
        else if (seenOffline) {
            seenOffline = false
            retryKey++
        }
    }
    var snapshot by remember(uid, backup, namespace) { mutableStateOf<CloudGallerySnapshot?>(null) }
    var loading by remember(uid, backup, namespace) { mutableStateOf(false) }
    var catalogIssue by remember(uid, backup, namespace) { mutableStateOf<CloudCatalogIssue?>(null) }
    var lastAttempt by remember(uid, backup, namespace) { mutableStateOf<Pair<Long, Int>?>(null) }

    LaunchedEffect(uid, backup, namespace, active, revision, retryKey, network.online) {
        if (!active && catalogIssue != null) lastAttempt = null
        if (!active || uid == null || !network.online ||
            lastAttempt == (revision to retryKey)
        ) return@LaunchedEffect
        loading = true
        try {
            val originals = loadOriginals(backup, uid)
            currentCoroutineContext().ensureActive()
            if (connection.session.account.value?.uid == uid) {
                snapshot = CloudGallerySnapshot(uid, originals)
                catalogIssue = null
                lastAttempt = revision to retryKey
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (connection.session.account.value?.uid == uid) {
                val issue = cloudCatalogIssue(error)
                if (issue.blocksCachedCatalog) {
                    snapshot = null
                    CloudPhotoThumbnailStore.activate(null)
                }
                catalogIssue = issue
                lastAttempt = revision to retryKey
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(uid, namespace, catalogIssue) {
        if (catalogIssue?.blocksCachedCatalog != true) CloudPhotoThumbnailStore.activate(uid)
    }

    // SHA-256 comparison is bounded: one short batch starts automatically.
    // The user explicitly requests subsequent batches to limit IO/battery usage.
    val analyzer = remember(repository) {
        repository?.let { CloudSpaceAnalysis(it.appContext, it) }
    }
    val candidates = remember(snapshot, deviceItems, uid, recentUpload) {
        if (snapshot?.accountUid != uid) emptyList()
        else CloudGalleryMatchPolicy.prioritizeRecentUpload(
            CloudGalleryMatchPolicy.candidates(deviceItems, snapshot?.originals.orEmpty()),
            recentUpload, uid,
        )
    }
    var verifiedMatches by remember(uid, namespace, snapshot, deviceItems) {
        mutableStateOf(emptyList<VerifiedGalleryMatch>())
    }
    var matchChecked by remember(uid, namespace, snapshot, deviceItems) { mutableIntStateOf(0) }
    var completedBatches by remember(uid, namespace, snapshot, deviceItems) { mutableIntStateOf(0) }
    var requestedBatches by remember(uid, namespace, snapshot, deviceItems) {
        mutableIntStateOf(CloudGalleryMatchPolicy.INITIAL_BATCHES)
    }
    var matchChecking by remember(uid, namespace, snapshot, deviceItems) { mutableStateOf(false) }
    var matchFailed by remember(uid, namespace, snapshot, deviceItems) { mutableStateOf(false) }

    LaunchedEffect(uid, namespace, active, snapshot, candidates, requestedBatches, analyzer, network.online, catalogIssue, loading) {
        val owner = uid ?: return@LaunchedEffect
        val catalog = snapshot?.takeIf { it.accountUid == owner } ?: return@LaunchedEffect
        val verifier = analyzer ?: return@LaunchedEffect
        if (!active || !network.online || catalogIssue != null || loading || completedBatches >= requestedBatches ||
            matchChecked >= candidates.size
        ) return@LaunchedEffect

        matchChecking = true
        matchFailed = false
        try {
            while (completedBatches < requestedBatches && matchChecked < candidates.size) {
                val batch = CloudGalleryMatchPolicy.nextBatch(candidates, matchChecked)
                if (batch.isEmpty()) break
                val matches = verifier.matchGalleryBatch(batch, catalog.originals, owner) {
                    connection.session.account.value?.uid == owner
                }
                currentCoroutineContext().ensureActive()
                if (connection.session.account.value?.uid != owner || snapshot !== catalog) break
                verifiedMatches = (verifiedMatches + matches)
                    .distinctBy { it.localUri }
                matchChecked += batch.size
                completedBatches++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (connection.session.account.value?.uid == owner) matchFailed = true
        } finally {
            matchChecking = false
        }
    }

    val visibleCatalogIssue = if (active && uid != null && namespace != null && !network.online &&
        catalogIssue?.blocksCachedCatalog != true) CloudCatalogIssue.CONNECTION else catalogIssue
    return CloudFilesUiState(
        enabled = true,
        accountUid = uid,
        backup = backup,
        snapshot = snapshot?.takeIf { it.accountUid == uid && catalogIssue?.blocksCachedCatalog != true },
        loading = uid != null && network.online && (loading || (active && lastAttempt != (revision to retryKey))),
        catalogIssue = visibleCatalogIssue,
        online = network.online,
        unmetered = network.unmetered,
        cachedCatalog = uid != null && snapshot?.accountUid == uid &&
            (!network.online || catalogIssue?.blocksCachedCatalog == false),
        retry = { retryKey++ },
        verifiedMatches = verifiedMatches.takeIf {
            uid != null && snapshot?.accountUid == uid && catalogIssue?.blocksCachedCatalog != true
        }.orEmpty(),
        matchChecking = uid != null && matchChecking,
        matchChecked = matchChecked,
        matchTotal = candidates.size,
        matchFailed = matchFailed,
        matchNext = {
            if (catalogIssue == null && network.online && !matchChecking && matchChecked < candidates.size) {
                matchFailed = false
                requestedBatches++
            }
        },
        detailAccess = if (catalogIssue?.blocksCachedCatalog == true) DetailCloudAccess.CHECK_ACCESS else detailAccess,
        accountEmail = accountLabel,
        detailNotice = detailNotice ?: when (catalogIssue) {
            CloudCatalogIssue.ACCOUNT_RESTRICTED -> "현재 계정은 클라우드 보관함을 이용할 수 없습니다."
            CloudCatalogIssue.AUTH_REQUIRED -> "Google 계정 연결을 다시 확인해 주세요."
            else -> null
        },
        mayReadCatalog = namespace != null,
    )
}
