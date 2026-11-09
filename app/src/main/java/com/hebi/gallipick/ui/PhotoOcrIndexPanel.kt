package com.hebi.gallipick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hebi.gallipick.AppUiState
import com.hebi.gallipick.MediaAccess
import com.hebi.gallipick.domain.*

@Composable
internal fun PhotoOcrIndexPanel(
    state: AppUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    requestAccess: () -> Unit,
) {
    val session = state.ocrScan
    val count = state.items.count { it.kind == MediaKind.PHOTO && !it.isTrashed }
    val current = PhotoOcrIndexPolicy.valid(state.items, state.ocrEntries)
    val indexed = current.values.count { it.status != OcrResultStatus.FAILED }
    val failures = current.values.count { it.status == OcrResultStatus.FAILED }
    Column(
        Modifier.fillMaxWidth()
            .background(Panel, RoundedCornerShape(GalliUiMetrics.cardCorner))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("사진 속 글자 검색 · 로컬 OCR", style = MaterialTheme.typography.titleSmall)
        Text(
            "현재 접근 가능한 사진 ${count}장 중 ${indexed}장 분석됨 · 읽기 실패 ${failures}장",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        if (session.phase == OcrPhase.RUNNING || session.phase == OcrPhase.QUEUED) {
            LinearProgressIndicator(
                progress = {
                    if (session.total == 0) 0f else
                        ((session.completed + session.skipped).toFloat() / session.total).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(), color = Primary, trackColor = Line,
            )
            Text("${session.completed + session.skipped} / ${session.total}장 처리 중",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (state.access == MediaAccess.NONE) {
            ActionButton("사진 접근 허용", requestAccess, Modifier.fillMaxWidth(), filled = true)
        } else {
            when (session.phase) {
                OcrPhase.QUEUED, OcrPhase.RUNNING -> {
                    ActionButton("글자 분석 일시정지", onPause, Modifier.fillMaxWidth())
                }
                OcrPhase.PAUSED -> {
                    ActionButton("글자 분석 재개", onResume, Modifier.fillMaxWidth(), filled = true)
                }
                OcrPhase.FAILED -> {
                    ActionButton("글자 분석 다시 시도", onStart, Modifier.fillMaxWidth(), filled = true)
                }
                OcrPhase.IDLE, OcrPhase.COMPLETE -> {
                    val remaining = (count - indexed).coerceAtLeast(0)
                    val action = if (session.phase == OcrPhase.COMPLETE && remaining > 0)
                        "미분석·실패 사진 다시 분석" else if (session.phase == OcrPhase.COMPLETE)
                        "사진 다시 분석" else "사진 글자 분석 시작"
                    ActionButton(action, onStart, Modifier.fillMaxWidth(), filled = true,
                        enabled = count > 0 && state.catalogError == null && !state.loading)
                }
            }
        }
        if (session.message.isNotBlank()) {
            Text(session.message, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (state.access == MediaAccess.PARTIAL) {
            Text("일부 사진만 허용되어 있습니다. 허용된 사진의 검색 결과만 표시합니다.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(
            "한국어·영문 인식 결과는 기기에만 저장됩니다. 미분석·읽기 실패·권한 제외 사진은 검색되지 않을 수 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
    }
}
