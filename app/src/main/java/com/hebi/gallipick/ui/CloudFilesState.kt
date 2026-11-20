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
import com.hebi.gallipick.data.cloud.CloudGalleryMatchPolicy
import com.hebi.gallipick.data.cloud.CloudGallerySnapshot
import com.hebi.gallipick.data.cloud.CloudSpaceAnalysis
import com.hebi.gallipick.data.cloud.VerifiedGalleryMatch
import com.hebi.gallipick.domain.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal enum class DetailCloudAccess { UNAVAILABLE, SIGN_IN, CHECKING, CHECK_ACCESS, PLAN_REQUIRED, READY }

internal fun defaultDetailCloudAccess(backup: CloudBackup?, accountUid: String?): DetailCloudAccess =
    if (backup != null && accountUid != null) DetailCloudAccess.READY else DetailCloudAccess.UNAVAILABLE

internal data class CloudFilesUiState(
    val enabled: Boolean = false,
    val accountUid: String? = null,
    val backup: CloudBackup? = null,
    val snapshot: CloudGallerySnapshot? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
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
)

/** Retains account-scoped catalog state across tab switches, without loading local files from the network. */
@Composable
internal fun rememberCloudFilesState(
    connection: CloudAccountConnection?,
    active: Boolean,
    repository: MediaRepository? = null,
    deviceItems: List<MediaItem> = emptyList(),
): CloudFilesUiState {
    if (connection == null) return CloudFilesUiState()
    val identity by connection.session.account.collectAsStateWithLifecycle()
    val uid = identity?.uid
    val status by connection.session.status.collectAsStateWithLifecycle()
    val configuredBackup = connection.backupAvailability
    val observation = configuredBackup?.collectAsStateWithLifecycle()?.value
    val backup = connection.backup.takeIf { configuredBackup == null || observation?.uid == uid }
    val waitingForAccess = uid != null && configuredBackup != null && observation?.uid != uid && connection.billing != null
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
        waitingForAccess -> if (accessCheckWaited) DetailCloudAccess.CHECK_ACCESS else DetailCloudAccess.CHECKING
        uid != null && connection.billing != null -> DetailCloudAccess.PLAN_REQUIRED
        else -> DetailCloudAccess.UNAVAILABLE
    }
    val accountLabel = identity?.transferAccountLabel
    val detailNotice = status.displayText(uid != null)
        ?: "클라우드 이용 권한을 아직 확인하지 못했습니다.".takeIf { detailAccess == DetailCloudAccess.CHECK_ACCESS }
    if (backup == null) return CloudFilesUiState(
        detailAccess = detailAccess, accountEmail = accountLabel, detailNotice = detailNotice,
    )
    val namespace = observation?.access?.namespace
    val network = rememberCloudNetworkStatus(enabled = true)
    LaunchedEffect(uid, namespace) { CloudPhotoThumbnailStore.activate(uid) }
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
    var failed by remember(uid, backup, namespace) { mutableStateOf(false) }
    var lastAttempt by remember(uid, backup, namespace) { mutableStateOf<Pair<Long, Int>?>(null) }

    LaunchedEffect(uid, backup, namespace, active, revision, retryKey, network.online) {
        if (!active || uid == null || !network.online ||
            lastAttempt == (revision to retryKey)
        ) return@LaunchedEffect
        loading = true
        failed = false
        try {
            val originals = backup.listOriginals(uid)
            if (connection.session.account.value?.uid == uid) {
                snapshot = CloudGallerySnapshot(uid, originals)
                lastAttempt = revision to retryKey
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (connection.session.account.value?.uid == uid) {
                failed = true
                lastAttempt = revision to retryKey
            }
        } finally {
            loading = false
        }
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

    LaunchedEffect(uid, namespace, active, snapshot, candidates, requestedBatches, analyzer, network.online) {
        val owner = uid ?: return@LaunchedEffect
        val catalog = snapshot?.takeIf { it.accountUid == owner } ?: return@LaunchedEffect
        val verifier = analyzer ?: return@LaunchedEffect
        if (!active || !network.online || failed || completedBatches >= requestedBatches ||
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
                if (connection.session.account.value?.uid != owner) break
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

    return CloudFilesUiState(
        enabled = true,
        accountUid = uid,
        backup = backup,
        snapshot = snapshot?.takeIf { it.accountUid == uid },
        loading = uid != null && network.online && (loading || (active && lastAttempt == null)),
        failed = failed,
        online = network.online,
        unmetered = network.unmetered,
        cachedCatalog = uid != null && snapshot?.accountUid == uid &&
            (!network.online || failed),
        retry = { retryKey++ },
        verifiedMatches = verifiedMatches.takeIf {
            uid != null && snapshot?.accountUid == uid
        }.orEmpty(),
        matchChecking = uid != null && matchChecking,
        matchChecked = matchChecked,
        matchTotal = candidates.size,
        matchFailed = matchFailed,
        matchNext = {
            if (!matchChecking && matchChecked < candidates.size) {
                matchFailed = false
                requestedBatches++
            }
        },
        detailAccess = detailAccess,
        accountEmail = accountLabel,
        detailNotice = detailNotice,
    )
}
