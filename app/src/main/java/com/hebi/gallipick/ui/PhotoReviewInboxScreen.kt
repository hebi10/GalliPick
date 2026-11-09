package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.*

private enum class ReviewFilter(val title: String) {
    ALL("전체"), PENDING("미완료"), DEFERRED("검토 보류"), COMPLETE("완료"),
}

@Composable
internal fun PhotoReviewInboxScreen(
    groups: List<SimilarPhotoGroup>,
    entries: List<PhotoReviewEntry>,
    protectedFolders: Set<String>,
    onBack: () -> Unit,
    onAnalyze: () -> Unit,
    onMark: (String, String, PhotoReviewDecision) -> Unit,
    onCompare: (String, Boolean) -> Unit,
    onConfirmCandidates: () -> Unit,
    onClear: () -> Unit,
) {
    val valid = remember(groups, entries, protectedFolders) {
        PhotoReviewInboxPolicy.valid(groups, entries, protectedFolders)
    }
    val progress = remember(groups, valid, protectedFolders) {
        PhotoReviewInboxPolicy.summaries(groups, valid, protectedFolders)
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val completed = progress.count { it.completed }
    val deferred = valid.count { it.decision == PhotoReviewDecision.DEFER }
    val candidates = valid.count { it.decision == PhotoReviewDecision.CANDIDATE }
    val kept = valid.count { it.decision == PhotoReviewDecision.KEEP }
    val visible = progress.filter {
        when (filter) {
            ReviewFilter.ALL -> true
            ReviewFilter.PENDING -> !it.completed
            ReviewFilter.DEFERRED -> it.deferredCount > 0
            ReviewFilter.COMPLETE -> it.completed
        }
    }
    val selected = progress.firstOrNull { it.group.id == selectedId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 820.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(
            Modifier.fillMaxSize().widthIn(max = 1220.dp).align(Alignment.TopCenter)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(
                    if (!wide && selected != null) "검토함 목록으로 돌아가기" else "정리로 돌아가기",
                    Icons.Outlined.ArrowBack,
                    onClick = { if (!wide && selected != null) selectedId = null else onBack() },
                )
                Text("사진 검토함", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                    Text("기록 초기화")
                }
            }
            if (groups.isEmpty()) {
                EmptyState("검토할 사진 묶음이 없습니다",
                    "사진 분석을 완료한 뒤 유사 사진과 연속 촬영 결과를 검토할 수 있습니다.")
                ActionButton("사진 분석 열기", onAnalyze, Modifier.fillMaxWidth(), filled = true)
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${progress.size}개 묶음 · 완료 ${completed}개 · 미완료 ${progress.size - completed}개",
                        style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { completed.toFloat() / progress.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = Line,
                    )
                    Text("남김 ${kept}장 · 정리 후보 ${candidates}장 · 검토 보류 ${deferred}장",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text("검토 기록은 원본 사진과 휴지통 이동 기록과 별도로 저장됩니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (wide || selected == null) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReviewFilter.entries.forEach { choice ->
                            FilterChip(
                                selected = filter == choice, onClick = { filter = choice },
                                label = { Text(choice.title) },
                            )
                        }
                    }
                }
                if (wide) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ReviewGroupList(visible, selectedId, { selectedId = it },
                            Modifier.weight(0.9f))
                        if (selected != null) {
                            ReviewGroupDetail(
                                selected, valid, protectedFolders,
                                onMark, onCompare,
                                onNext = {
                                    val index = progress.indexOfFirst { it.group.id == selected.group.id }
                                    selectedId = progress.getOrNull(index + 1)?.group?.id
                                },
                                modifier = Modifier.weight(1.1f),
                            )
                        } else {
                            Box(Modifier.weight(1.1f), contentAlignment = Alignment.Center) {
                                Text("검토할 묶음을 선택해 주세요.",
                                    style = MaterialTheme.typography.bodyMedium, color = Muted)
                            }
                        }
                    }
                } else {
                    if (selected == null) {
                        ReviewGroupList(visible, selectedId, { selectedId = it },
                            Modifier.weight(1f))
                    } else {
                        ReviewGroupDetail(
                            selected, valid, protectedFolders,
                            onMark, onCompare,
                            onNext = {
                                val index = progress.indexOfFirst { it.group.id == selected.group.id }
                                selectedId = progress.getOrNull(index + 1)?.group?.id
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (candidates > 0) {
                    ActionButton("정리 후보 ${candidates}장 확인", onConfirmCandidates,
                        Modifier.fillMaxWidth().padding(bottom = 4.dp), filled = true)
                } else {
                    Text("검토만으로 파일이 삭제되거나 휴지통으로 이동하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("검토 기록 초기화") },
            text = {
                Text("남김·정리 후보·보류 상태만 초기화합니다. 원본 사진과 실제 휴지통 기록은 변경하지 않습니다.")
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; selectedId = null; onClear() }) {
                    Text("기록 초기화")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ReviewGroupList(
    groups: List<PhotoReviewGroupProgress>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxWidth().testTag("review-inbox-groups"),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (groups.isEmpty()) item {
            Text("해당 상태의 묶음이 없습니다.",
                style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        items(groups, key = { it.group.id }) { item ->
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        if (selectedId == item.group.id) Panel else AppSurface,
                        RoundedCornerShape(GalliUiMetrics.cardCorner),
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription =
                            "${if (item.group.id.startsWith("burst:")) "연속 촬영" else "유사 사진"} ${item.group.photos.size}장 검토"
                    }
                    .plainClick(action = { onSelect(item.group.id) })
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.group.id.startsWith("burst:")) "연속 촬영 묶음" else "유사 사진 묶음",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    )
                    GalliIcon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                }
                Text("${item.group.photos.size}장 · ${bytesLabel(item.group.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MediaThumbnail(item.group.photos.first().item, Modifier.size(64.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("결정 ${item.decidedCount} / ${item.group.photos.size}장",
                            style = MaterialTheme.typography.bodySmall)
                        Text(
                            when {
                                item.completed -> "검토 완료"
                                item.deferredCount > 0 -> "검토 보류 ${item.deferredCount}장"
                                item.started -> "이어서 검토"
                                else -> "검토 전"
                            },
                            style = MaterialTheme.typography.labelMedium, color = Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewGroupDetail(
    summary: PhotoReviewGroupProgress,
    entries: List<PhotoReviewEntry>,
    protectedFolders: Set<String>,
    onMark: (String, String, PhotoReviewDecision) -> Unit,
    onCompare: (String, Boolean) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = summary.group
    val byUri = entries.filter { it.groupId == group.id }.associateBy { it.uri }
    LazyColumn(
        modifier.fillMaxWidth().testTag("review-inbox-detail"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("현재 묶음 · 사진 ${group.photos.size}장",
                    style = MaterialTheme.typography.titleMedium)
                Text("남김·정리 후보·검토 보류 상태를 직접 지정할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                ActionButton("사진 A/B 비교",
                    { onCompare(group.id, group.id.startsWith("burst:")) },
                    Modifier.fillMaxWidth())
            }
        }
        items(group.photos, key = { it.item.uri }) { photo ->
            ReviewPhotoRow(group, photo, byUri[photo.item.uri]?.decision,
                entries, protectedFolders, onMark)
        }
        item("next") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("다음 묶음", onNext, Modifier.fillMaxWidth())
                Text("정리 후보는 실제 삭제가 아닙니다. 최종 이동은 Android 확인창에서 승인해야 합니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun ReviewPhotoRow(
    group: SimilarPhotoGroup,
    photo: SimilarPhoto,
    choice: PhotoReviewDecision?,
    entries: List<PhotoReviewEntry>,
    protectedFolders: Set<String>,
    onMark: (String, String, PhotoReviewDecision) -> Unit,
) {
    val item = photo.item
    val canCandidate = PhotoReviewInboxPolicy.maySet(
        group, entries, item.uri, PhotoReviewDecision.CANDIDATE, protectedFolders)
    Column(
        Modifier.fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(GalliUiMetrics.cardCorner))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaThumbnail(item, Modifier.size(70.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${bytesLabel(item.sizeBytes)} · ${item.width} × ${item.height}",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(when (choice) {
                    PhotoReviewDecision.KEEP -> "현재 상태 · 남김"
                    PhotoReviewDecision.CANDIDATE -> "현재 상태 · 정리 후보"
                    PhotoReviewDecision.DEFER -> "현재 상태 · 검토 보류"
                    null -> "현재 상태 · 검토 전"
                }, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ReviewDecisionButton("남김", choice == PhotoReviewDecision.KEEP, true,
                Modifier.weight(1f)) { onMark(group.id, item.uri, PhotoReviewDecision.KEEP) }
            ReviewDecisionButton("정리 후보", choice == PhotoReviewDecision.CANDIDATE,
                canCandidate, Modifier.weight(1f)) {
                onMark(group.id, item.uri, PhotoReviewDecision.CANDIDATE)
            }
            ReviewDecisionButton("검토 보류", choice == PhotoReviewDecision.DEFER, true,
                Modifier.weight(1f)) { onMark(group.id, item.uri, PhotoReviewDecision.DEFER) }
        }
        if (!canCandidate && choice != PhotoReviewDecision.CANDIDATE) {
            Text(
                if (!SimilarPhotoReviewPolicy.allowed(item, protectedFolders))
                    "보호·즐겨찾기는 정리 후보에서 제외됩니다."
                else "묶음에서 최소 한 장은 남겨야 합니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted,
            )
        }
    }
}

@Composable
private fun ReviewDecisionButton(
    title: String, selected: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "${title} 상태 선택" },
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) Primary else Muted,
        ),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}
