package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.SoftFocusCandidate

@Composable
internal fun SoftFocusResultsScreen(
    candidates: List<SoftFocusCandidate>,
    selectedUris: Set<String>,
    onBack: () -> Unit,
    onCompare: (String) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onReview: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("사진 분석으로 돌아가기", Icons.Rounded.ArrowBack, onClick = onBack)
            Text("선명도 확인", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        if (candidates.isEmpty()) {
            EmptyState("선명도 확인 후보가 없습니다", "분석되지 않은 사진은 후보에 포함되지 않습니다.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("summary") {
                    Column(
                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("선명도 확인 후보 ${candidates.size}장", style = MaterialTheme.typography.headlineSmall)
                        Text("기기 내 이미지 변화량에 따른 추정입니다. 흐림 여부나 삭제 필요성을 확정하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text("보호·즐겨찾기 사진은 제외합니다. 비교할 사진이 없더라도 원본을 직접 확인할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                items(candidates, key = { it.item.uri }) { candidate ->
                    Column(
                        Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(GalliUiMetrics.cardCorner))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MediaThumbnail(candidate.item, Modifier.width(108.dp).height(104.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(candidate.item.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(bytesLabel(candidate.item.sizeBytes) +
                                    " · ${candidate.item.width} × ${candidate.item.height}",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                                Text("선명도 확인 필요 · 추정치 ${candidate.score.toInt()}",
                                    style = MaterialTheme.typography.bodySmall, color = Primary)
                                Text(if (candidate.comparisonGroupId == null) "비교할 비슷한 사진 없음" else "비슷한 사진 비교 가능",
                                    style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                        if (candidate.item.uri in selectedUris) {
                            Text("직접 선택한 정리 후보", style = MaterialTheme.typography.bodySmall, color = Primary)
                        }
                        ActionButton(
                            if (candidate.comparisonGroupId != null) "비슷한 사진과 비교" else "원본 사진 열기",
                            {
                                val groupId = candidate.comparisonGroupId
                                if (groupId != null) onCompare(groupId) else onOpenMedia(candidate.item)
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                item("notice") {
                    Text("자동 삭제는 하지 않습니다. 비교 가능한 묶음에서 사용자가 직접 선택한 사진만 " +
                        "Android 휴지통 확인 절차로 이동할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        if (selectedUris.isNotEmpty()) {
            ActionButton("선택한 ${selectedUris.size}장 휴지통 이동 확인", onReview,
                Modifier.fillMaxWidth().padding(vertical = 8.dp), filled = true)
        }
    }
}
