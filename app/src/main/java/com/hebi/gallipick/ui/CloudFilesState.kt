package com.hebi.gallipick.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudGallerySnapshot
import kotlinx.coroutines.CancellationException

internal data class CloudFilesUiState(
    val enabled: Boolean = false,
    val accountUid: String? = null,
    val snapshot: CloudGallerySnapshot? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val retry: () -> Unit = {},
)

/** Retains account-scoped catalog state across tab switches, without loading local files from the network. */
@Composable
internal fun rememberCloudFilesState(connection: CloudAccountConnection?, active: Boolean): CloudFilesUiState {
    val backup = connection?.backup ?: return CloudFilesUiState()
    val identity by connection.session.account.collectAsStateWithLifecycle()
    val uid = identity?.uid
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

    return CloudFilesUiState(
        enabled = true,
        accountUid = uid,
        snapshot = snapshot?.takeIf { it.accountUid == uid },
        loading = uid != null && (loading || (active && lastAttempt == null)),
        failed = failed,
        retry = { retryKey++ },
    )
}
