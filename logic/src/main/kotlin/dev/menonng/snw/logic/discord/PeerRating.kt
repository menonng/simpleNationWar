package dev.menonng.snw.logic.discord

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 상호평가 집계(docs/discord/peer-rating.md 4절).
 * - '잘 모름'(null)은 뺀다. n ≥ 6 이면 최고·최저 하나씩 버린 평균, 아니면 단순 평균 → m.
 * - 적은 표본 보정: (n·m + 3·μ_c) / (n + 3), μ_c = 그 항목 전체 평균.
 * - 이상치 경고(관리자만): 한 평가자가 특정 대상에게만 다른 사람들 평균과 크게 다른 점수를 몰아준 경우.
 */
object PeerRating {
    val DEFAULT_CATEGORIES = listOf(
        Category("combat", "⚔️ 전투"), Category("build", "🏗️ 건축"), Category("survival", "⛏️ 생존·자원"),
        Category("teamwork", "🤝 협동·소통"), Category("passion", "🔥 열정(접속)"),
    )
    const val PRIOR_WEIGHT = 3.0
    const val TRIM_FROM = 6
    const val OUTLIER_GAP = 2.0
    const val OUTLIER_MIN_CATEGORIES = 3

    data class Category(val id: String, val label: String)
    /** 평가 한 건: rater → target, 항목 → 별(1~5, null = 잘 모름). */
    data class Rating(val rater: String, val target: String, val scores: Map<String, Int?>)
    data class Score(val value: Double, val n: Int) { val stars: Int get() = value.roundToInt().coerceIn(1, 5) }
    data class Outlier(val rater: String, val target: String, val categories: List<String>)

    fun mean(xs: List<Int>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val use = if (s.size >= TRIM_FROM) s.subList(1, s.size - 1) else s
        return use.average()
    }

    /** 대상 → 항목 → 점수. */
    fun aggregate(ratings: List<Rating>, categories: List<String>): Map<String, Map<String, Score>> {
        val mu = categories.associateWith { c -> ratings.mapNotNull { it.scores[c] }.let { if (it.isEmpty()) 3.0 else it.average() } }
        return ratings.groupBy { it.target }.mapValues { (_, rs) ->
            categories.associateWith { c ->
                val xs = rs.mapNotNull { it.scores[c] }
                val n = xs.size
                val m = if (n == 0) mu[c]!! else mean(xs)
                Score((n * m + PRIOR_WEIGHT * mu[c]!!) / (n + PRIOR_WEIGHT), n)
            }
        }
    }

    /** 대상별 전체 평균(항목 평균의 평균). */
    fun overall(agg: Map<String, Map<String, Score>>): Map<String, Double> = agg.mapValues { (_, m) -> m.values.map { it.value }.average() }

    fun outliers(ratings: List<Rating>, categories: List<String>): List<Outlier> {
        val out = ArrayList<Outlier>()
        for (r in ratings) {
            val others = ratings.filter { it.target == r.target && it.rater != r.rater }
            if (others.size < 2) continue
            val bad = categories.filter { c ->
                val mine = r.scores[c] ?: return@filter false
                val rest = others.mapNotNull { it.scores[c] }
                rest.size >= 2 && abs(mine - rest.average()) >= OUTLIER_GAP
            }
            if (bad.size >= OUTLIER_MIN_CATEGORIES) out += Outlier(r.rater, r.target, bad)
        }
        return out
    }

    fun starsLine(s: Score): String = "⭐".repeat(s.stars) + "☆".repeat(5 - s.stars) + "  " + "%.1f".format(s.value)

    /** 공개(관리자가 고를 때만): 항목별 상위 3명. 낮은 점수·순위는 내보내지 않는다. */
    fun top3(agg: Map<String, Map<String, Score>>, category: String): List<String> =
        agg.entries.filter { (it.value[category]?.n ?: 0) > 0 }.sortedByDescending { it.value[category]!!.value }.take(3).map { it.key }
}
