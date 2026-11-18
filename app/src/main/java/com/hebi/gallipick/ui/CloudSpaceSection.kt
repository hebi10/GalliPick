package com.hebi.gallipick.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.data.MediaRepository
import com.hebi.gallipick.data.cloud.CLOUD_BACKUP_MAX_BYTES
import com.hebi.gallipick.data.cloud.CloudAccountConnection
import com.hebi.gallipick.data.cloud.CloudSpaceAnalysis
import com.hebi.gallipick.data.cloud.CloudSpaceBackupProof
import com.hebi.gallipick.domain.CloudSpaceMatchedFiles
import com.hebi.gallipick.domain.CloudSpacePolicy
import com.hebi.gallipick.domain.CloudSpaceTarget
import com.hebi.gallipick.domain.DuplicatePolicy
import com.hebi.gallipick.domain.DuplicateReviewPolicy
import com.hebi.gallipick.domain.PhotoReviewDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CLOUD_SPACE_PAGE_SIZE = 20

@Composable
internal fun CloudSpaceSection(
    connection: CloudAccountConnection,
    state: AppUiState,
    repository: MediaRepository,
    loadVerifiedBackups: suspend (String) -> Set<CloudSpaceBackupProof>,
) {
    val account by connection.session.account.collectAsStateWithLifecycle()
    val uid = account?.uid ?: return
    key(uid, state.access) {
        Column(
            Modifier.fillMaxWidth().galliCardSurface(AppSurface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("공간 확보 검토", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() })
            if (state.access == MediaAccess.FULL) {
                CloudSpaceControls(state, repository, { connection.session.account.value?.uid == uid }) {
                    loadVerifiedBackups(uid)
                }
            } else {
                Text("사진·동영상 전체 접근이 확인되면 연관 파일과 백업 후보를 함께 확인할 수 있습니다.", color = Muted)
            }
        }
    }
}

@Composable
private fun CloudSpaceControls(
    state: AppUiState,
    repository: MediaRepository,
    isCurrentAccount: () -> Boolean,
    loadVerifiedBackups: suspend () -> Set<CloudSpaceBackupProof>,
) {
    val context = LocalContext.current
    fun sizeLabel(bytes: Long): String = Formatter.formatFileSize(context, bytes)

    val analysis = remember(context, repository) { CloudSpaceAnalysis(context, repository) }
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(CloudSpaceTarget.FIVE_GB) }
    var loading by remember { mutableStateOf(false) }
    var matched by remember { mutableStateOf<CloudSpaceMatchedFiles?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val eligible = remember(state.items, state.protectedFolders, state.duplicates, state.duplicateKeepers,
        state.reviewEntries, state.similarGroups, state.burstGroups) {
        val keepUris = state.duplicateKeepers.values.toMutableSet()
        DuplicateReviewPolicy.liveGroups(state.duplicates, state.items).forEach { group ->
            keepUris += DuplicatePolicy.recommendedKeep(group, state.duplicateKeepers[group.hash]).uri
        }
        state.reviewEntries.filter { it.decision == PhotoReviewDecision.KEEP }.forEach { keepUris += it.uri }
        val current = state.items.associateBy { it.uri }
        (state.similarGroups + state.burstGroups).forEach { group ->
            group.items.firstOrNull { item ->
                current[item.uri]?.let { CloudSpacePolicy.sameOriginal(item, it) } == true
            }?.let { keepUris += it.uri }
        }
        CloudSpacePolicy.eligible(state.items, state.protectedFolders, keepUris)
    }
    val recommendations = remember(matched, eligible, target, state.loading, state.catalogError) {
        if (state.loading || state.catalogError != null) null
        else CloudSpacePolicy.recommend(eligible, matched?.files.orEmpty(), target, System.currentTimeMillis())
    }
    var visibleLimit by remember(recommendations) { mutableStateOf(CLOUD_SPACE_PAGE_SIZE) }

    CloudSpaceTarget.entries.chunked(2).forEach { targets ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            targets.forEach { option ->
                ActionButton(option.label, { target = option }, Modifier.weight(1f), filled = option == target)
            }
        }
    }
    ActionButton(
        if (loading) "백업 원본 비교 중" else "검증된 백업과 비교",
        {
            loading = true
            matched = null
            message = null
            scope.launch {
                try {
                    if (!isCurrentAccount()) return@launch
                    matched = withTimeoutOrNull(45_000) {
                        analysis.matchOnDevice(eligible, loadVerifiedBackups(), isCurrentAccount)
                    }
                    if (matched == null) message = "비교 시간이 초과됐습니다. 다시 시도해 주세요."
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    message = "현재 계정의 보관함 또는 기기 파일을 확인할 수 없습니다."
                } finally {
                    loading = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading && !state.loading && state.catalogError == null && eligible.isNotEmpty(),
    )
    val notice = when {
        state.loading -> "기기 파일을 확인하는 중입니다."
        state.catalogError != null -> "기기 파일을 확인할 수 없습니다."
        eligible.isEmpty() -> "현재 검토할 수 있는 파일이 없습니다."
        else -> message
    }
    notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = Muted) }
    recommendations?.let { result ->
        val unverifiedBytes = result.potentialBytes - result.verifiedBackupBytes
        Text("정리 후보 ${sizeLabel(result.potentialBytes)} · ${result.candidates.size}개",
            style = MaterialTheme.typography.bodyMedium)
        Text("백업 확인 ${sizeLabel(result.verifiedBackupBytes)} · 백업 미확인 ${sizeLabel(unverifiedBytes)}",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("현재 확보한 용량 ${sizeLabel(0)}", color = Muted, style = MaterialTheme.typography.bodySmall)
        if (matched != null && result.verifiedBackupBytes == 0L) {
            Text("비교 범위에서 원본이 일치하는 백업을 찾지 못했습니다.", color = Muted)
        }
        if (target.bytes?.let { result.potentialBytes < it } == true) {
            Text("현재 정리 후보의 용량이 목표보다 적습니다.", color = Muted)
        }
        matched?.takeIf { it.scannedCount < it.matchingSizeCount }?.let {
            Text("크기가 일치하는 ${it.matchingSizeCount}개 중 ${it.scannedCount}개를 비교했습니다.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        for (candidate in result.candidates.take(visibleLimit)) {
            val file = candidate.file
            val status = when {
                candidate.verifiedBackup -> "백업 확인"
                file.sizeBytes > CLOUD_BACKUP_MAX_BYTES -> "기기 파일 확인 한도(64 MiB) 초과"
                else -> "백업 미확인"
            }
            Text("${file.displayName} · ${sizeLabel(file.sizeBytes)} · $status", style = MaterialTheme.typography.bodySmall)
        }
        if (result.candidates.size > visibleLimit) {
            ActionButton("후보 더 보기", { visibleLimit += CLOUD_SPACE_PAGE_SIZE }, Modifier.fillMaxWidth())
        }
    }
}
