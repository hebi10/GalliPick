package com.hebi.gallipick.domain

import kotlin.math.ln

data class BestCutDecision(
    val recommendedUri: String?,
    val summary: String,
    val reasons: List<String> = emptyList(),
)

/**
 * Compares only photos in the same verified similarity/burst group.
 * This is a conservative suggestion, never a delete/retention instruction.
 */
object BestCutRecommender {
    fun assess(group: SimilarPhotoGroup): BestCutDecision {
        val photos = group.photos
        if (photos.size < 2) return undecided("비교할 사진이 부족합니다.")
        if (photos.any { photo ->
                val sharpness = photo.sharpnessScore
                val exposure = photo.exposureScore
                photo.item.width <= 0 || photo.item.height <= 0 ||
                    sharpness == null || !sharpness.isFinite() || sharpness < 0.0 ||
                    exposure == null || !exposure.isFinite() || exposure !in 0.0..1.0
            }) {
            return undecided("사진 품질 분석 정보가 부족합니다. 사진 분석을 다시 실행해 주세요.")
        }

        val sharpness = normalize(photos.map { ln(1.0 + it.sharpnessScore!!) }, 0.08)
        val resolution = normalize(photos.map {
            ln(1.0 + it.item.width.toDouble() * it.item.height.toDouble())
        }, 0.08)
        val exposure = normalize(photos.map { it.exposureScore!! }, 0.08)

        val ranked = photos.indices.map { index ->
            index to (0.55 * sharpness[index] + 0.25 * resolution[index] + 0.20 * exposure[index])
        }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }
            .thenBy { photos[it.first].item.uri })

        val first = ranked[0]
        val second = ranked[1]
        if (first.second - second.second < 0.16 ||
            (sharpness[first.first] - sharpness[second.first] < 0.15 &&
                resolution[first.first] - resolution[second.first] < 0.15)
        ) {
            return undecided("사진 품질 차이가 뚜렷하지 않아 추천을 보류합니다.")
        }

        val reasons = buildList {
            if (sharpness[first.first] - sharpness[second.first] >= 0.15) {
                add("선명도 추정값이 상대적으로 높습니다.")
            }
            if (resolution[first.first] - resolution[second.first] >= 0.15) {
                add("해상도가 상대적으로 높습니다.")
            }
            if (exposure[first.first] - exposure[second.first] >= 0.15) {
                add("명도와 밝기 극단값 기준의 노출 추정이 상대적으로 안정적입니다.")
            }
        }
        if (reasons.isEmpty()) return undecided("추천 근거가 충분하지 않습니다.")
        return BestCutDecision(
            recommendedUri = photos[first.first].item.uri,
            summary = "묶음 안에서 비교한 추천 사진입니다.",
            reasons = reasons,
        )
    }

    private fun normalize(values: List<Double>, tolerance: Double): List<Double> {
        val minimum = values.minOrNull() ?: return emptyList()
        val maximum = values.maxOrNull() ?: return emptyList()
        val spread = maximum - minimum
        if (spread <= tolerance) return List(values.size) { 0.5 }
        return values.map { ((it - minimum) / spread).coerceIn(0.0, 1.0) }
    }

    private fun undecided(message: String) = BestCutDecision(
        recommendedUri = null,
        summary = message,
    )
}
