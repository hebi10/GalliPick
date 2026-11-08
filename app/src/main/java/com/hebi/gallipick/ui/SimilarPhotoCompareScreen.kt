package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.domain.SimilarPhotoGroup
import com.hebi.gallipick.domain.SimilarPhotoReviewPolicy

@Composable
internal fun SimilarPhotoCompareScreen(
    group: SimilarPhotoGroup,
    groupIndex: Int,
    groupCount: Int,
    selectedUris: Set<String>,
    protectedFolders: Set<String>,
    onBack: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onOpenMedia: (com.hebi.gallipick.domain.MediaItem) -> Unit,
    onReview: () -> Unit,
) {
    var focusedUri by rememberSaveable(group.id) { mutableStateOf(group.photos.first().item.uri) }
    val focused = group.photos.firstOrNull { it.item.uri == focusedUri }?.item ?: group.photos.first().item
    val chosen = focused.uri in selectedUris
    val selectedCount = group.photos.count { it.item.uri in selectedUris }
    val keptCount = group.photos.size - selectedCount
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = maxWidth >= 700.dp && maxHeight > 400.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction("유사 사진 목록으로 돌아가기", Icons.Rounded.ArrowBack, onClick = onBack)
                Text("사진 비교", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("유사 사진 묶음 ${groupIndex} / ${groupCount}", style = MaterialTheme.typography.titleSmall)
                Text("${group.photos.size}장 · 남길 사진 ${keptCount}장 · 정리 후보 ${selectedCount}장",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("여러 장을 남길 수 있습니다. 미리보기 선택과 정리 후보 선택은 별도 동작입니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            LazyRow(Modifier.fillMaxWidth().semantics { contentDescription = "유사 사진 가로 비교 목록" },
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(group.photos, key = { it.item.uri }) { photo ->
                    val active = photo.item.uri == focused.uri
                    val selected = photo.item.uri in selectedUris
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier.width(if (maxWidth < 350.dp) 102.dp else 132.dp)
                            .border(if (active) 2.dp else 1.dp, if (active) Primary else Line, shape)
                            .semantics { contentDescription = "${photo.item.displayName} 미리보기 선택" }
                            .plainClick(action = { focusedUri = photo.item.uri })
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MediaThumbnail(photo.item, Modifier.fillMaxWidth().height(100.dp))
                        Text(photo.item.displayName, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (selected) "정리 후보" else "남길 사진", style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Primary else Muted)
                    }
                }
            }
            val details: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(focused.displayName, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${bytesLabel(focused.sizeBytes)} · ${focused.width} × ${focused.height}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${if (focused.dateTakenMs > 0) "촬영일" else "추가일"} ${dateLabel(focused.capturedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text(focused.relativePath.ifBlank { "폴더 정보 없음" }, style = MaterialTheme.typography.bodySmall,
                        color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (chosen) "정리 후보 · 선택됨" else "남길 사진 · 유지",
                        style = MaterialTheme.typography.titleSmall, color = Primary)
                    val eligible = SimilarPhotoReviewPolicy.allowed(focused, protectedFolders)
                    val maySelect = SimilarPhotoReviewPolicy.maySelect(
                        group, selectedUris, focused.uri, protectedFolders
                    )
                    ActionButton(
                        if (chosen) "선택 취소 · 남길 사진으로 지정" else "정리 후보 선택",
                        { onToggleCandidate(focused.uri) },
                        Modifier.fillMaxWidth(),
                        filled = !chosen,
                        enabled = chosen || eligible && maySelect,
                    )
                    if (!eligible) {
                        Text("보호 또는 즐겨찾기 사진은 정리 후보가 될 수 없습니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    } else if (!chosen && !maySelect) {
                        Text("묶음에서 최소 한 장은 남겨야 합니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    TextButton(onClick = { onOpenMedia(focused) }, modifier = Modifier.fillMaxWidth()) {
                        GalliIcon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("원본 사진 외부 앱으로 열기")
                    }
                }
            }
            if (horizontal) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        MediaThumbnail(focused, Modifier.fillMaxWidth().fillMaxHeight(), ContentScale.Fit)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { details() }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MediaThumbnail(focused, Modifier.fillMaxWidth().height(260.dp), ContentScale.Fit)
                    details()
                }
            }
            Text("사진은 자동 삭제되지 않습니다. Android 확인창에서 최종 이동 대상을 검토합니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (selectedCount > 0) ActionButton(
                "선택한 사진 정리 (${selectedUris.size}장)", onReview,
                Modifier.fillMaxWidth().padding(bottom = 6.dp), filled = true,
            )
        }
    }
}
