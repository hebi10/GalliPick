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

internal data class CloudFilesUiState(
    val enabled: Boolean = false,
    val accountUid: String? = null,
    val backup: CloudBackup? = null,
    val snapshot: CloudGallerySnapshot? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val retry: () -> Unit = {},
    val verifiedMatches: List<VerifiedGalleryMatch> = emptyList(),
    val matchChecking: Boolean = false,
    val matchChecked: Int = 0,
    val matchTotal: Int = 0,
    val matchFailed: Boolean = false,
    val matchNext: () -> Unit = {},
)

/** Retains account-scoped catalog state across tab switches, without loading local files from the network. */
@Composable
internal fun rememberCloudFilesState(
    connection: CloudAccountConnection?,
    active: Boolean,
    repository: MediaRepository? = null,
    deviceItems: List<MediaItem> = emptyList(),
): CloudFilesUiState {
    val backup = connection?.backup ?: return CloudFilesUiState()
    val identity by connection.session.account.collectAsStateWithLifecycle()
    val uid = identity?.uid
    LaunchedEffect(uid) { CloudPhotoThumbnailStore.activate(uid) }
    val revision by backup.catalogRevision.collectAsStateWithLifecycle()
    var retryKey by remember(uid) { mutableIntStateOf(0) }
    var snapshot by remember(uid, backup) { mutableStateOf<CloudGallerySnapshot?>(null) }
    var loading by remember(uid, backup) { mutableStateOf(false) }
    var failed by remember(uid, backup) { mutableStateOf(false) }
    var lastAttempt by remember(uid, backup) { mutableStateOf<Pair<Long, Int>?>(null) }

    LaunchedEffect(uid, backup, active, revision, retryKey) {
        if (!active || uid == null || lastAttempt == (revision to retryKey)) return@LaunchedEffect
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
    val candidates = remember(snapshot, deviceItems, uid) {
        if (snapshot?.accountUid != uid) emptyList()
        else CloudGalleryMatchPolicy.candidates(deviceItems, snapshot?.originals.orEmpty())
    }
    var verifiedMatches by remember(uid, snapshot, deviceItems) {
        mutableStateOf(emptyList<VerifiedGalleryMatch>())
    }
    var matchChecked by remember(uid, snapshot, deviceItems) { mutableIntStateOf(0) }
    var completedBatches by remember(uid, snapshot, deviceItems) { mutableIntStateOf(0) }
    var requestedBatches by remember(uid, snapshot, deviceItems) {
        mutableIntStateOf(CloudGalleryMatchPolicy.INITIAL_BATCHES)
    }
    var matchChecking by remember(uid, snapshot, deviceItems) { mutableStateOf(false) }
    var matchFailed by remember(uid, snapshot, deviceItems) { mutableStateOf(false) }

    LaunchedEffect(uid, active, snapshot, candidates, requestedBatches, analyzer) {
        val owner = uid ?: return@LaunchedEffect
        val catalog = snapshot?.takeIf { it.accountUid == owner } ?: return@LaunchedEffect
        val verifier = analyzer ?: return@LaunchedEffect
        if (!active || failed || completedBatches >= requestedBatches ||
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
        loading = uid != null && (loading || (active && lastAttempt == null)),
        failed = failed,
        retry = { retryKey++ },
        verifiedMatches = verifiedMatches.takeIf {
            uid != null && !failed && snapshot?.accountUid == uid
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
    )
}
