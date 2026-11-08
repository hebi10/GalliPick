package com.hebi.gallipick.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.R
import com.hebi.gallipick.domain.ScanPhase
import kotlin.math.max

@Composable
internal fun PhotoAnalysisScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    val scan = state.photoScan
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val short = maxHeight < 470.dp * scale.coerceAtMost(1.3f)
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = if (short) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (short) 8.dp else 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = GalliUiMetrics.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconAction("사진 분석 닫기", Icons.Rounded.ArrowBack, onClick = onBack)
                Text("사진 분석", modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, color = Ink)
            }
            Text("유사 사진", style = MaterialTheme.typography.titleMedium,
                color = Primary, modifier = Modifier.semantics { heading() })
            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("비슷한 사진을 기기 안에서 분석해요",
                        style = MaterialTheme.typography.titleMedium, color = Ink)
                    if (!short) Text("크기를 변경하거나 다시 저장한 사진도 비교할 수 있도록 작은 미리보기의 특징을 계산합니다.",
                        style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                if (!short) Image(
                    painter = painterResource(R.drawable.gp_cat_search),
                    contentDescription = null, modifier = Modifier.size(94.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (state.access == MediaAccess.NONE) {
                EmptyState(
                    "사진 접근이 필요합니다",
                    "접근을 허용한 사진만 기기 안에서 분석합니다.",
                    action = { ActionButton("사진 접근 허용", onRequestAccess, filled = true) },
                )
            } else if (state.loading || state.catalogError != null) {
                Text(
                    if (state.loading) "사진 목록을 불러오고 있습니다." else "파일 목록 확인에 실패했습니다. 정리 탭에서 새로고침해 주세요.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val processed = (scan.completed + scan.skipped).coerceAtLeast(0)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val status = when (scan.phase) {
                        ScanPhase.IDLE -> "분석 전"
                        ScanPhase.QUEUED -> "분석 준비 중"
                        ScanPhase.RUNNING -> "사진 분석 중"
                        ScanPhase.PAUSED -> "분석 일시정지"
                        ScanPhase.COMPLETE -> "분석 완료"
                        ScanPhase.FAILED -> "분석 실패"
                    }
                    if (!short || scan.phase != ScanPhase.IDLE) {
                        Text(status, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (scan.phase) {
                                ScanPhase.IDLE -> "분석을 시작하면 사진을 하나씩 확인합니다."
                                else -> scan.message.ifBlank { status }
                            },
                            color = Muted, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (scan.phase != ScanPhase.IDLE) {
                        val total = max(0, scan.total)
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { (processed.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(7.dp),
                                color = Primary, trackColor = Panel,
                            )
                            Text("처리 ${processed.coerceAtMost(total)} / $total · 분석 성공 ${scan.completed}개 · 제외 ${scan.skipped}개",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        } else if (scan.phase == ScanPhase.QUEUED || scan.phase == ScanPhase.RUNNING) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Primary)
                        }
                    }
                    when (scan.phase) {
                        ScanPhase.IDLE, ScanPhase.COMPLETE -> ActionButton(
                            if (scan.phase == ScanPhase.IDLE) "사진 분석 시작" else "다시 분석하기",
                            onStart, Modifier.fillMaxWidth(), filled = true,
                            icon = if (scan.phase == ScanPhase.IDLE) Icons.Rounded.ImageSearch else Icons.Rounded.Refresh,
                        )
                        ScanPhase.QUEUED, ScanPhase.RUNNING -> ActionButton(
                            "분석 일시정지", onPause, Modifier.fillMaxWidth(),
                            icon = Icons.Rounded.PauseCircle,
                        )
                        ScanPhase.PAUSED, ScanPhase.FAILED -> ActionButton(
                            "분석 재개", onResume, Modifier.fillMaxWidth(), filled = true,
                            icon = Icons.Rounded.PlayCircle,
                        )
                    }
                    if (scan.phase == ScanPhase.COMPLETE) {
                        Text("분석된 사진의 묶음 비교·보관 선택 기능은 다음 단계에서 제공됩니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(GalliUiMetrics.cardCorner))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GalliIcon(Icons.Rounded.Shield, null, Modifier.size(24.dp), tint = Primary)
                    Text("분석은 기기 내부에서 진행되며 사진을 서버에 전송하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall, color = Ink)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GalliIcon(Icons.Rounded.DeleteOutline, null, Modifier.size(24.dp), tint = Primary)
                    Text("분석만으로 파일이 삭제되거나 휴지통으로 이동하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }
            if (state.access == MediaAccess.PARTIAL) {
                Text("일부 사진 접근 권한이므로 현재 허용된 사진만 분석합니다.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}
