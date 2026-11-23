package dev.menonng.snw.logic.landmark

/**
 * 거점 점령 한 걸음(nation.md 8-2). 매초 부른다.
 * - [present] = 점령 구역 안 국가별 인원(잠수 제외). 동맹 국민은 인원에 함께 센다([allies]), 소유권은 한 국가에만.
 * - 앞선 국가 = 실제로 와 있는 국가 중 동맹 합산 인원이 가장 많은 국가(같으면 자기 인원이 많은 쪽).
 * - 분쟁(정지): 동맹이 아닌 다른 국가의 합산 인원이 같거나 많을 때, 또는 소유국이 앞선 국가와 같은 편일 때.
 * - 다른 두 국가가 서로 동맹이고 앞선 국가가 고립이면 속도 ×1.25.
 */
object Capture {
    data class Step(val leader: String?, val speed: Double) { val contested get() = speed == 0.0 }

    fun step(present: Map<String, Int>, nations: Collection<String>, allies: (String) -> String?, owner: String?, isolatedBonus: Double = 1.25): Step {
        fun group(n: String) = setOfNotNull(n, allies(n))
        val here = present.filterValues { it > 0 }
        if (here.isEmpty()) return Step(null, 0.0)
        val effective = nations.associateWith { n -> group(n).sumOf { here[it] ?: 0 } }
        val leader = here.keys.maxWithOrNull(compareBy({ effective[it] ?: 0 }, { here[it] ?: 0 }))!!
        val lead = effective[leader] ?: 0
        val rivals = effective.filterKeys { it != leader && it !in group(leader) }
        if (rivals.values.any { it >= lead }) return Step(leader, 0.0)
        if (owner != null && owner in group(leader)) return Step(leader, 0.0)
        val others = nations.filter { it != leader }
        val isolated = others.size == 2 && allies(others[0]) == others[1] && allies(leader) == null
        return Step(leader, if (isolated) isolatedBonus else 1.0)
    }
}
