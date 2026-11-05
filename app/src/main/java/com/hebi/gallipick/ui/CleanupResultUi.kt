package com.hebi.gallipick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hebi.gallipick.domain.TrashOutcome

enum class CleanupResultStatus { SUCCESS, PARTIAL, CANCELED, FAILED, UNKNOWN }

data class CleanupOperationResult(
    val status: CleanupResultStatus,
    val restore: Boolean,
    val requestedCount: Int,
    val completedCount: Int = 0,
    val unchangedCount: Int = 0,
    val unknownCount: Int = 0,
    val requestedBytes: Long = 0,
) {
    companion object {
        fun verified(outcome: TrashOutcome, restore: Boolean, requestedCount: Int, requestedBytes: Long): CleanupOperationResult {
            val status = when {
                outcome.completed > 0 && outcome.unchanged == 0 && outcome.unknown == 0 -> CleanupResultStatus.SUCCESS
                outcome.completed > 0 -> CleanupResultStatus.PARTIAL
                outcome.unknown > 0 -> CleanupResultStatus.UNKNOWN
                else -> CleanupResultStatus.FAILED
            }
            return CleanupOperationResult(status, restore, requestedCount, outcome.completed, outcome.unchanged, outcome.unknown, requestedBytes)
        }

        fun cancelled(restore: Boolean, requestedCount: Int, requestedBytes: Long) =
            CleanupOperationResult(CleanupResultStatus.CANCELED, restore, requestedCount, requestedBytes = requestedBytes)

        fun failed(restore: Boolean, requestedCount: Int, requestedBytes: Long) =
            CleanupOperationResult(CleanupResultStatus.FAILED, restore, requestedCount, requestedBytes = requestedBytes)

        fun unknown(restore: Boolean, requestedCount: Int, requestedBytes: Long) =
            CleanupOperationResult(CleanupResultStatus.UNKNOWN, restore, requestedCount, unknownCount = requestedCount, requestedBytes = requestedBytes)
    }

    val title: String
        get() = when (status) {
            CleanupResultStatus.SUCCESS -> if (restore) "파일 복원 완료" else "휴지통 이동 완료"
            CleanupResultStatus.PARTIAL -> "일부 파일만 처리 확인"
            CleanupResultStatus.CANCELED -> "작업 취소"
            CleanupResultStatus.FAILED -> "작업 완료 실패"
            CleanupResultStatus.UNKNOWN -> "파일 상태 확인 불가"
        }

    val description: String
        get() = when (status) {
            CleanupResultStatus.SUCCESS -> "파일 상태를 다시 조회해 처리 완료를 확인했습니다."
            CleanupResultStatus.PARTIAL -> "일부 항목만 처리 상태가 확인되었습니다. 남은 항목은 파일 목록에서 확인해 주세요."
            CleanupResultStatus.CANCELED -> "Android 확인을 취소했습니다. 작업을 완료한 것으로 표시하지 않습니다."
            CleanupResultStatus.FAILED -> "요청한 변경이 확인되지 않았거나 작업을 시작하지 못했습니다. 접근 권한과 파일을 확인해 주세요."
            CleanupResultStatus.UNKNOWN -> "Android 요청 후 파일 상태를 확인하지 못했습니다. 성공으로 간주하지 않으며 다시 확인이 필요합니다."
        }
}

@Composable
fun CleanupResultDialog(
    result: CleanupOperationResult,
    onDismiss: () -> Unit,
    onHome: () -> Unit,
    onNext: () -> Unit,
) {
    val action = if (result.restore) "복원" else "이동"
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(20.dp),
                color = AppSurface,
                tonalElevation = 0.dp,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("정리 작업 결과", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconAction("결과 닫기", Icons.Rounded.Close, onClick = onDismiss)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GalliIllustration(
                            if (result.status == CleanupResultStatus.SUCCESS) GalliScene.COMPLETE else GalliScene.SEARCH,
                            Modifier.size(72.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleLarge)
                            Text(result.description, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = Panel) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("요청 ${result.requestedCount}개 · 확인 완료 ${result.completedCount}개", style = MaterialTheme.typography.titleSmall)
                            if (result.unchangedCount > 0) Text("미${action} ${result.unchangedCount}개", style = MaterialTheme.typography.bodySmall)
                            if (result.unknownCount > 0) Text("확인 불가 ${result.unknownCount}개", style = MaterialTheme.typography.bodySmall)
                            Text("요청한 파일의 용량 ${bytesLabel(result.requestedBytes)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalliIcon(Icons.Rounded.Info, null, Modifier.size(22.dp))
                        Text(
                            "표시된 용량은 요청한 파일 크기의 합계이며 실제 확보된 저장공간이 아닙니다. 휴지통 이동만으로 공간이 즉시 늘어나지는 않을 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f),
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 420.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                        if (stacked) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton("홈으로", onHome, Modifier.fillMaxWidth())
                                ActionButton(if (result.restore) "휴지통 확인" else "다음 정리 후보 확인",
                                    onNext, Modifier.fillMaxWidth(), filled = true)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionButton("홈으로", onHome, Modifier.weight(1f))
                                ActionButton(if (result.restore) "휴지통 확인" else "다음 정리 후보 확인",
                                    onNext, Modifier.weight(1f), filled = true)
                            }
                        }
                    }
                }
            }
        }
    }
}
