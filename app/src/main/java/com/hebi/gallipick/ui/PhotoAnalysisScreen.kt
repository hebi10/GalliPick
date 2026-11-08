package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.AppViewModel
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.R
import com.hebi.gallipick.domain.PhotoAnalysisPhase

@Composable
internal fun PhotoAnalysisScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    requestAccess: () -> Unit,
    refresh: () -> Unit,
    onOpenResults: () -> Unit = {},
    onOpenBurstResults: () -> Unit = {},
    onOpenSoftFocusResults: () -> Unit = {},
) {
    val scan = state.photoAnalysis
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val compact = maxWidth < 350.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(
            Modifier.widthIn(max = 700.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction("사진 분석 닫기", Icons.Rounded.ArrowBack, onClick = onBack)
                Text("사진 분석", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            Text("유사 사진", style = MaterialTheme.typography.titleMedium, color = Primary)
            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                    .padding(GalliUiMetrics.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("비슷한 사진을 찾기 위한 분석", style = MaterialTheme.typography.titleMedium)
                    Text("사진을 축소해 시각적 특징을 기기 안에 저장합니다. 다른 크기나 압축본도 비교할 수 있도록 준비합니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (!compact) Image(
                    painterResource(R.drawable.gp_cat_search),
                    contentDescription = null, modifier = Modifier.size(84.dp),
                )
            }
            if (state.access == MediaAccess.NONE) {
                Text("사진에 접근할 수 없습니다. 권한을 허용해 주세요.",
                    style = MaterialTheme.typography.bodyMedium, color = Muted)
                ActionButton("사진 접근 허용", requestAccess, Modifier.fillMaxWidth(), filled = true)
            } else if (state.catalogError != null) {
                Text("사진 목록을 확인할 수 없습니다.", style = MaterialTheme.typography.bodyMedium)
                ActionButton("목록 다시 불러오기", refresh, Modifier.fillMaxWidth(), filled = true)
            } else if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary)
                Text("사진 목록을 불러오고 있습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else if (state.items.none { it.kind == com.hebi.gallipick.domain.MediaKind.PHOTO && !it.isTrashed }) {
                EmptyState("분석할 사진이 없습니다", "현재 허용된 사진 중 분석할 파일이 없습니다.")
            } else {
                when (scan.phase) {
                    PhotoAnalysisPhase.IDLE -> {
                        Text("분석을 시작하면 사진의 시각적 특징을 계산합니다.",
                            style = MaterialTheme.typography.bodyMedium)
                        ActionButton("사진 분석 시작", onStart, Modifier.fillMaxWidth(), filled = true)
                    }
                    PhotoAnalysisPhase.QUEUED, PhotoAnalysisPhase.RUNNING -> {
                        Text("사진 특징 분석 중", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { if (scan.total > 0) ((scan.completed + scan.skipped).toFloat() / scan.total).coerceIn(0f,1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Primary,
                        )
                        Text("${scan.completed + scan.skipped} / ${scan.total}개 확인 · 제외 ${scan.skipped}개",
                            color = Muted, style = MaterialTheme.typography.bodyMedium)
                        ActionButton("일시정지", onPause, Modifier.fillMaxWidth(), icon = Icons.Rounded.Pause)
                    }
                    PhotoAnalysisPhase.PAUSED -> {
                        Text("일시정지됨 · ${scan.completed + scan.skipped} / ${scan.total}개 확인",
                            style = MaterialTheme.typography.bodyMedium)
                        ActionButton("분석 재개", onResume, Modifier.fillMaxWidth(), filled = true, icon = Icons.Rounded.PlayArrow)
                    }
                    PhotoAnalysisPhase.COMPLETE -> {
                        Text("특징 분석 완료", style = MaterialTheme.typography.titleMedium)
                        Text("분석 성공 ${scan.completed}개 · 제외 ${scan.skipped}개",
                            style = MaterialTheme.typography.bodyMedium)
                        if (state.similarGroups.isNotEmpty()) {
                            Text("${state.similarGroups.size}개 유사 사진 묶음 · ${state.similarGroups.sumOf { it.photos.size }}장",
                                style = MaterialTheme.typography.bodyMedium, color = Primary)
                            ActionButton("유사 사진 결과 보기", onOpenResults, Modifier.fillMaxWidth(),
                                filled = true, icon = Icons.Rounded.Collections)
                        } else {
                            Text("현재 확인된 유사 사진 묶음이 없습니다. 사진의 유사도는 참고용입니다.",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Text("연속 촬영", style = MaterialTheme.typography.titleMedium, color = Primary)
                        Text("촬영 시각과 시각적 특징이 모두 가까운 사진을 묶습니다. 추가일이 아닌 실제 촬영 시각을 기준으로 합니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (state.burstGroups.isNotEmpty()) {
                            Text("${state.burstGroups.size}개 연속 촬영 묶음 · ${state.burstGroups.sumOf { it.photos.size }}장",
                                style = MaterialTheme.typography.bodyMedium, color = Primary)
                            ActionButton("연속 촬영 결과 보기", onOpenBurstResults, Modifier.fillMaxWidth(),
                                filled = true, icon = Icons.Rounded.PhotoLibrary)
                        } else {
                            Text("현재 확인된 연속 촬영 사진 묶음이 없습니다. 촬영 시각이 없는 사진은 제외됩니다.",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Text("선명도 확인", style = MaterialTheme.typography.titleMedium, color = Primary)
                        Text("사진 가장자리의 변화를 분석해 선명도가 낮을 수 있는 사진을 추정합니다. " +
                            "의도적인 배경 흐림이나 부드러운 장면도 후보에 포함될 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (state.sharpnessPending > 0) {
                            Text("기존 분석 ${state.sharpnessPending}장은 선명도 재평가가 필요합니다. 다시 분석을 선택해 주세요.",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (state.softFocusCandidates.isNotEmpty()) {
                            Text("선명도 확인 후보 ${state.softFocusCandidates.size}장",
                                style = MaterialTheme.typography.bodyMedium, color = Primary)
                            ActionButton("선명도 확인 후보 보기", onOpenSoftFocusResults,
                                Modifier.fillMaxWidth(), filled = true)
                        } else if (state.sharpnessPending == 0) {
                            Text("현재 선명도 확인 후보가 없습니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        ActionButton("다시 분석", onStart, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
                    }
                    PhotoAnalysisPhase.FAILED -> {
                        Text("사진 분석을 완료하지 못했습니다.", style = MaterialTheme.typography.bodyMedium)
                        ActionButton("다시 시도", onStart, Modifier.fillMaxWidth(), filled = true, icon = Icons.Rounded.Refresh)
                    }
                }
                if (scan.message.isNotBlank()) {
                    Text(scan.message, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Column(
                Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(GalliUiMetrics.cardCorner))
                    .padding(GalliUiMetrics.cardPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("분석 안내", style = MaterialTheme.typography.titleSmall)
                Text("사진은 서버로 전송하지 않으며, 기기 내부에서만 특징을 계산합니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("사진 분석만으로 파일이 삭제되거나 휴지통으로 이동하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (state.access == MediaAccess.PARTIAL) {
                    Text("현재 허용된 사진만 분석합니다.", style = MaterialTheme.typography.bodySmall, color = Primary)
                }
            }
        }
    }
}
