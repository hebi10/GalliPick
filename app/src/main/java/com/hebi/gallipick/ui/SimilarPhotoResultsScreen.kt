package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.SimilarPhotoGroup

@Composable
internal fun SimilarPhotoResultsScreen(
    groups: List<SimilarPhotoGroup>,
    selectedUris: Set<String>,
    onBack: () -> Unit,
    onCompare: (String) -> Unit,
    onReview: () -> Unit,
    title: String = "비슷한 사진 찾기",
    groupTitle: String = "유사 사진 묶음",
    explanation: String = "비슷하게 보일 수 있는 사진 후보입니다. 직접 비교하여 남길 사진을 선택하세요.",
    emptyTitle: String = "유사 사진 묶음이 없습니다",
    emptyExplanation: String = "현재 접근 가능한 사진의 특징을 비교한 결과입니다. 분석되지 못한 사진은 포함되지 않습니다.",
    caution: String = "유사도는 참고용이며, 자동으로 휴지통에 이동하거나 삭제하지 않습니다.",
    showCaptureTime: Boolean = false,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("사진 분석으로 돌아가기", Icons.Rounded.ArrowBack, onClick = onBack)
            Text(title, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        val count = groups.sumOf { it.photos.size }
        val selected = groups.flatMap { it.items }.distinctBy { it.uri }.count { it.uri in selectedUris }
        if (groups.isEmpty()) {
            EmptyState(emptyTitle, emptyExplanation)
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("summary") {
                    Column(
                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${groups.size}개 묶음 · ${count}장", style = MaterialTheme.typography.headlineSmall)
                        Text(explanation,
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                items(groups, key = { it.id }) { group ->
                    Column(
                        Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(GalliUiMetrics.cardCorner))
                            .semantics { role = Role.Button; contentDescription = "${group.photos.size}장 ${groupTitle} 비교" }
                            .plainClick(action = { onCompare(group.id) })
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(groupTitle, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    (if (showCaptureTime) dateLabel(group.photos.first().item.dateTakenMs) + " · " else "") +
                                        "사진 ${group.photos.size}장 · ${bytesLabel(group.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall, color = Muted,
                                )
                            }
                            GalliIcon(Icons.Rounded.ChevronRight, null, Modifier.size(22.dp))
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                        ) {
                            items(group.photos, key = { it.item.uri }) { photo ->
                                Column(Modifier.width(112.dp)) {
                                    MediaThumbnail(photo.item, Modifier.fillMaxWidth().height(94.dp))
                                    Text(photo.item.displayName, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (photo.item.uri in selectedUris) {
                                        Text("정리 후보", style = MaterialTheme.typography.labelSmall, color = Primary)
                                    }
                                }
                            }
                        }
                        Text("사진 비교", style = MaterialTheme.typography.labelMedium, color = Primary)
                    }
                }
                item("caution") {
                    Text(caution,
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        if (selected > 0) {
            ActionButton("선택한 ${selected}장 휴지통 이동 확인", onReview,
                Modifier.fillMaxWidth().padding(vertical = 8.dp), filled = true)
        }
    }
}
