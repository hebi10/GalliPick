package com.hebi.gallipick.ui

import com.hebi.gallipick.domain.DuplicateGroup
import com.hebi.gallipick.domain.DuplicatePolicy
import com.hebi.gallipick.domain.DuplicateReviewPolicy
import com.hebi.gallipick.domain.MediaItem
import com.hebi.gallipick.domain.SelectionPolicy

internal data class DuplicateRoles(
    val keeper: MediaItem,
    val preferredUri: String?,
    val kept: List<MediaItem>,
    val candidates: List<MediaItem>,
    val protectedFolders: Set<String>,
) {
    val candidateUris: Set<String> = candidates.map { it.uri }.toSet()

    fun label(item: MediaItem, selected: Set<String>): String = when {
        item.uri in candidateUris -> if (item.uri in selected) "이동 대상 · 선택됨" else "정리 후보"
        item.uri == keeper.uri && item.uri == preferredUri -> "남길 파일 · 직접 지정"
        item.isProtected || item.hasFolderProtection(protectedFolders) -> "남길 파일 · 보호됨"
        item.isFavorite -> "남길 파일 · 즐겨찾기"
        else -> "남길 파일 · 앱 추천"
    }

    fun reason(item: MediaItem): String = when {
        item.hasFolderProtection(protectedFolders) -> "보호 폴더에 있어 정리에서 제외합니다."
        item.isProtected -> "보호한 파일이므로 현재 위치에 남깁니다."
        item.isFavorite -> "즐겨찾기 파일이므로 현재 위치에 남깁니다."
        item.uri == preferredUri -> "사용자가 이 묶음에서 남기도록 지정했습니다."
        else -> recommendationReason(item)
    }

    private fun recommendationReason(item: MediaItem): String {
        val camera = item.inCameraFolder()
        // DuplicatePolicy compares dates only after protection, favorite and Camera-folder priority.
        val peers = (kept + candidates).filter {
            it.isProtected == item.isProtected && it.isFavorite == item.isFavorite && it.inCameraFolder() == camera
        }
        val dateRanks = peers.map { it.capturedAt.takeIf { timestamp -> timestamp > 0 } ?: Long.MAX_VALUE }
        if (dateRanks.distinct().size > 1 && item.capturedAt > 0) {
            val field = if (item.dateTakenMs > 0) "촬영일" else "저장일"
            val scope = if (camera) "카메라 폴더에서 " else ""
            val basis = if (dateRanks.count { it == item.capturedAt } > 1) "가장 이른 날짜가 같은 사본 중 한 개를 추천했습니다."
                else "가장 이른 날짜로 추천했습니다."
            return "$scope$basis $field ${dateLabel(item.capturedAt)}."
        }
        return if (camera && (kept + candidates).any { !it.inCameraFolder() }) "카메라 폴더의 파일을 우선 추천했습니다."
        else "같은 내용의 사본 중 한 개를 남기도록 추천했습니다."
    }
}

private fun MediaItem.inCameraFolder(): Boolean = relativePath.split('/').any { it.equals("Camera", ignoreCase = true) }

internal fun duplicateRoles(group: DuplicateGroup, preferredUri: String?, protectedFolders: Set<String>): DuplicateRoles {
    val unique = group.copy(items = group.items.filterNot { it.isTrashed }.distinctBy { it.uri })
    val keeper = DuplicatePolicy.recommendedKeep(unique, preferredUri)
    val candidates = DuplicateReviewPolicy.candidates(listOf(unique), unique.items,
        preferredUri?.let { mapOf(unique.hash to it) } ?: emptyMap(), protectedFolders)
    val candidateUris = candidates.mapTo(hashSetOf()) { it.uri }
    val kept = unique.items.filter { it.uri !in candidateUris }
        .sortedBy { if (it.uri == keeper.uri) 0 else 1 }
    return DuplicateRoles(keeper, preferredUri, kept, candidates, protectedFolders)
}

internal fun duplicateSelectionLabel(candidateCount: Int): String = if (candidateCount == 0) "전체 후보 없음"
    else "정리 후보 ${candidateCount.coerceAtMost(SelectionPolicy.MAX_SELECTION_SIZE)}개 선택"

internal fun duplicateGroupSelectionLabel(candidateCount: Int, selectedInGroup: Int, totalSelected: Int): String {
    if (candidateCount == 0) return "정리 가능한 사본 없음"
    val unselected = (candidateCount - selectedInGroup).coerceAtLeast(0)
    if (unselected == 0) return "이 묶음의 후보 ${candidateCount}개 선택됨"
    val remaining = (SelectionPolicy.MAX_SELECTION_SIZE - totalSelected).coerceAtLeast(0)
    if (remaining == 0) return "선택 한도 ${SelectionPolicy.MAX_SELECTION_SIZE}개"
    return "이 묶음의 후보 ${unselected.coerceAtMost(remaining)}개 ${if (totalSelected > 0) "추가 선택" else "선택"}"
}

internal fun duplicateBytes(items: List<MediaItem>): Long = items.distinctBy { it.uri }.fold(0L) { sum, item ->
    val bytes = item.sizeBytes.coerceAtLeast(0)
    if (bytes > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + bytes
}
