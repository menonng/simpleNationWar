package dev.menonng.snw.diary

/**
 * 며칠에 걸친 이야기와 말투 반복을 추적하는 기억. 봇이 날마다 저장했다가 다음 날 다시 넘긴다.
 * 사람은 불투명한 [PersonRef.id] 로만 기억하고, 닉네임은 저장하지 않는다.
 */
data class DayFacts(
    val dayNumber: Int,
    val pvp: List<Pair<String, String>> = emptyList(),
    val accidents: List<Pair<String, Accident>> = emptyList(),
    val featured: Set<String> = emptySet(),
    val alphaKills: Int = 0,
    val bloodMoon: Boolean = false,
    val landmarkCaptures: Int = 0,
    val topNation: String? = null,
    val firsts: Set<String> = emptySet(),
)

data class DiaryMemory(
    val days: List<DayFacts> = emptyList(),
    /** 문장 틀 id → 마지막으로 쓴 날. */
    val phraseLastUsed: Map<String, Int> = emptyMap(),
) {
    fun recent(n: Int, today: Int): List<DayFacts> = days.filter { today - it.dayNumber in 1..n }

    fun everHappened(first: String): Boolean = days.any { first in it.firsts }

    fun daysSinceBloodMoon(today: Int): Int? = days.filter { it.bloodMoon }.maxOfOrNull { today - it.dayNumber }

    fun lastFeatured(personId: String, today: Int): Int? =
        days.filter { personId in it.featured }.maxOfOrNull { today - it.dayNumber }

    fun remember(facts: DayFacts, usedPhrases: Collection<String>, keepDays: Int = 7): DiaryMemory = copy(
        days = (days + facts).filter { facts.dayNumber - it.dayNumber < keepDays },
        phraseLastUsed = phraseLastUsed + usedPhrases.associateWith { facts.dayNumber },
    )
}
