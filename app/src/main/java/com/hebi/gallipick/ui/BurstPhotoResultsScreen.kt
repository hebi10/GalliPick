package com.hebi.gallipick.ui

import androidx.compose.runtime.Composable
import com.hebi.gallipick.domain.SimilarPhotoGroup

@Composable
internal fun BurstPhotoResultsScreen(
    groups: List<SimilarPhotoGroup>,
    selectedUris: Set<String>,
    onBack: () -> Unit,
    onCompare: (String) -> Unit,
    onReview: () -> Unit,
) {
    SimilarPhotoResultsScreen(
        groups = groups,
        selectedUris = selectedUris,
        onBack = onBack,
        onCompare = onCompare,
        onReview = onReview,
        title = "연속 촬영 사진",
        groupTitle = "연속 촬영 묶음",
        explanation = "촬영 시각이 가깝고 시각적 특징이 비슷한 사진 후보입니다. 남길 사진을 직접 비교하세요.",
        emptyTitle = "연속 촬영 사진이 없습니다",
        emptyExplanation = "촬영 시각이 기록되지 않았거나 서로 다른 장면인 사진은 묶지 않습니다.",
        caution = "촬영 간격과 이미지 유사도를 기준으로 분류했습니다. 자동으로 삭제하지 않습니다.",
        showCaptureTime = true,
    )
}
