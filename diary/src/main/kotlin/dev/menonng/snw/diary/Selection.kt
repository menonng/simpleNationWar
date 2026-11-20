package dev.menonng.snw.diary

/** 사건에 붙는 이야기 표지. 문장 틀을 고를 때도 쓴다. */
enum class Story {
    REVENGE,        // 며칠 안에 당한 상대를 되갚음
    RIVALRY,        // 같은 두 사람이 세 번 이상 맞붙음
    REPEAT_ACCIDENT,// 같은 사람이 같은 사고를 되풀이(웃긴 기록 동의 시만)
    FIRST_EVER,     // 서버에서 처음 일어난 종류
    SELF_RECOVERY,  // 자기 망자를 직접 되찾음
    MATE_RECOVERY,  // 자국민이 대신 되찾아 줌
    GROUP,          // 여럿이 함께한 일
    LONG_WAIT,      // 블러드문이 오래 없다가 옴
}

data class Item(
    val events: List<DiaryEvent>,
    val stories: Set<Story>,
    val score: Double,
) {
    val part: DayPart get() = events.first().part
    val kind: String get() = events.first()::class.simpleName ?: "?"
    val people: Set<String> get() = events.flatMap { peopleOf(it) }.map { it.id }.toSet()
}

fun peopleOf(e: DiaryEvent): List<PersonRef> = when (e) {
    is DiaryEvent.ZombieDeath -> listOf(e.victim)
    is DiaryEvent.AccidentDeath -> listOf(e.victim)
    is DiaryEvent.PvpKill -> listOf(e.killer, e.victim) + e.assists
    is DiaryEvent.RevenantRecovered -> listOf(e.owner, e.by).distinctBy { it.id }
    is DiaryEvent.AlphaKilled -> e.killers
    is DiaryEvent.NestDestroyed -> e.participants
    is DiaryEvent.BloodMoon -> e.participants
    is DiaryEvent.LandmarkCaptured -> emptyList()
    is DiaryEvent.Advancement -> listOf(e.person)
    is DiaryEvent.Starforce -> listOf(e.person)
    is DiaryEvent.VaccineShard -> listOf(e.person)
}

/**
 * 1) 이야기 표지 붙이기 2) 같은 종류 묶기 3) 중요도 점수 4) 제약(사람당 2건, 종류 다양성, 분량)을 지키며 고르기.
 */
class Selector(private val maxItems: Int = 6, private val maxPerPerson: Int = 2) {

    fun select(input: DayInput, memory: DiaryMemory): List<Item> {
        val today = input.dayNumber
        val tagged = input.events.mapNotNull { e -> tag(e, input, memory) }
        val grouped = group(tagged)
        val scored = grouped.map { it.copy(score = score(it, memory, today)) }.sortedByDescending { it.score }

        val chosen = mutableListOf<Item>()
        val perPerson = HashMap<String, Int>()
        val perKind = HashMap<String, Int>()
        for (item in scored) {
            if (chosen.size >= maxItems) break
            if (item.people.any { (perPerson[it] ?: 0) >= maxPerPerson }) continue
            if ((perKind[item.kind] ?: 0) >= 2 && item.score < 9.0) continue
            chosen += item
            item.people.forEach { perPerson.merge(it, 1, Int::plus) }
            perKind.merge(item.kind, 1, Int::plus)
        }
        return chosen
    }

    private fun tag(e: DiaryEvent, input: DayInput, memory: DiaryMemory): Item? {
        val today = input.dayNumber
        val s = mutableSetOf<Story>()
        when (e) {
            is DiaryEvent.PvpKill -> {
                val recent = memory.recent(3, today).flatMap { it.pvp }
                if (recent.any { it.first == e.victim.id && it.second == e.killer.id }) s += Story.REVENGE
                val all = memory.days.flatMap { it.pvp } + input.events.filterIsInstance<DiaryEvent.PvpKill>().map { it.killer.id to it.victim.id }
                if (all.count { setOf(it.first, it.second) == setOf(e.killer.id, e.victim.id) } >= 3) s += Story.RIVALRY
                if (!memory.everHappened("pvp")) s += Story.FIRST_EVER
                if (e.assists.isNotEmpty()) s += Story.GROUP
            }
            is DiaryEvent.AccidentDeath -> {
                val todaySame = input.events.count { it is DiaryEvent.AccidentDeath && it.victim.id == e.victim.id && it.cause == e.cause }
                val pastSame = memory.recent(3, today).flatMap { it.accidents }.count { it.first == e.victim.id && it.second == e.cause }
                if (todaySame + pastSame >= 2) {
                    if (!e.victim.funnyOptIn) return null      // 동의하지 않은 사람의 반복 사고는 아예 쓰지 않는다
                    s += Story.REPEAT_ACCIDENT
                }
            }
            is DiaryEvent.RevenantRecovered -> s += if (e.by.id == e.owner.id) Story.SELF_RECOVERY else Story.MATE_RECOVERY
            is DiaryEvent.AlphaKilled -> {
                if (!memory.everHappened("alpha")) s += Story.FIRST_EVER
                if (e.killers.size >= 2) s += Story.GROUP
            }
            is DiaryEvent.BloodMoon -> {
                if (!memory.everHappened("bloodmoon")) s += Story.FIRST_EVER
                if ((memory.daysSinceBloodMoon(today) ?: 0) >= 3) s += Story.LONG_WAIT
                if (e.participants.size >= 2) s += Story.GROUP
            }
            is DiaryEvent.NestDestroyed -> if (e.participants.size >= 2) s += Story.GROUP
            is DiaryEvent.LandmarkCaptured -> if (!memory.everHappened("landmark")) s += Story.FIRST_EVER
            is DiaryEvent.Starforce -> if (e.star >= 9 && !memory.everHappened("star${e.star}")) s += Story.FIRST_EVER
            else -> Unit
        }
        return Item(listOf(e), s, 0.0)
    }

    /** 같은 때(DayPart) 좀비 사망 여럿, 도감 여럿은 한 문장으로, 같은 사람의 같은 사고 반복은 한 사건으로 묶는다. */
    private fun group(items: List<Item>): List<Item> {
        val out = mutableListOf<Item>()
        val repeats = items.filter { Story.REPEAT_ACCIDENT in it.stories }
        repeats.groupBy { (it.events.single() as DiaryEvent.AccidentDeath).let { e -> e.victim.id to e.cause } }
            .forEach { (_, g) -> out += Item(g.flatMap { it.events }, setOf(Story.REPEAT_ACCIDENT), 0.0) }
        val groupable = items.filter { it.stories.isEmpty() && it.events.single().let { e -> e is DiaryEvent.ZombieDeath || e is DiaryEvent.Advancement } }
        val rest = items - groupable.toSet() - repeats.toSet()
        groupable.groupBy { it.kind to (if (it.events.single() is DiaryEvent.ZombieDeath) it.part else DayPart.DAY) }
            .forEach { (_, g) -> out += if (g.size == 1) g.single() else Item(g.flatMap { it.events }, setOf(Story.GROUP), 0.0) }
        return out + rest
    }

    private fun score(item: Item, memory: DiaryMemory, today: Int): Double {
        val e = item.events.first()
        var s = when (e) {
            is DiaryEvent.BloodMoon -> 9.0
            is DiaryEvent.AlphaKilled -> 8.0
            is DiaryEvent.LandmarkCaptured -> 7.0
            is DiaryEvent.PvpKill -> 6.0
            is DiaryEvent.NestDestroyed -> 5.0
            is DiaryEvent.VaccineShard -> 5.0
            is DiaryEvent.Starforce -> 4.0 + (e.star - 8).coerceAtLeast(0)
            is DiaryEvent.ZombieDeath -> 3.0 + if (e.variant != null && e.variant != "좀비") 1.0 else 0.0
            is DiaryEvent.RevenantRecovered -> 3.0
            is DiaryEvent.Advancement -> if (e.rare) 5.0 else 2.5
            is DiaryEvent.AccidentDeath -> 2.0
        }
        if (Story.REVENGE in item.stories) s += 5.0
        if (Story.RIVALRY in item.stories) s += 2.0
        if (Story.FIRST_EVER in item.stories) s += 3.0
        if (Story.REPEAT_ACCIDENT in item.stories) s += 3.0
        if (Story.LONG_WAIT in item.stories) s += 1.0
        if (item.events.size > 1) s += 0.5 * item.events.size
        // 며칠 동안 일지에 안 나온 사람에게 조명을 조금 돌린다
        val unseen = item.people.count { memory.lastFeatured(it, today)?.let { d -> d >= 3 } ?: true }
        s += 0.7 * unseen.coerceAtMost(2)
        return s
    }
}
