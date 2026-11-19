package dev.menonng.snw.diary

import dev.menonng.snw.logic.nickname.NicknameValidator
import dev.menonng.snw.logic.text.KoreanJosa
import java.util.Locale
import kotlin.random.Random

/**
 * 생존일지 작가. 외부 서비스를 부르지 않는 규칙 기반 생성기다.
 *
 * 1. [Selector] 가 사건에 이야기 표지를 붙이고, 묶고, 중요도 순으로 고른다
 * 2. 문단 계획: 여는 말 → 이른 시간 → 늦은 시간 → 블러드문 → 국가 소식·맺음말
 * 3. 사건마다 목소리에 맞는 문장 틀 후보를 모두 채워 보고, 앞 문장과의 반복·최근 사용·AI 상투어 벌점이 가장 낮은 것을 고른다
 * 4. 전체 글을 다시 검사해서 벌점이 0 이 될 때까지(최대 6번) 다른 난수로 다시 쓴다
 *
 * 같은 날짜·같은 입력이면 항상 같은 글이 나온다(날짜와 [salt] 로 난수를 정한다).
 */
class DiaryWriter(
    private val voice: Voice,
    private val salt: String,
    private val selector: Selector = Selector(),
    private val nicknameValidator: NicknameValidator = NicknameValidator(),
    private val maxLength: Int = 1500,
) {
    data class Output(val diary: Diary, val memory: DiaryMemory, val usedPhrases: List<String>, val problems: List<String>)

    private class Draft(val diary: Diary, val used: List<String>, val penalty: Int, val problems: List<String>)

    fun write(input: DayInput, memory: DiaryMemory): Output {
        val items = selector.select(input, memory)
        var best: Draft? = null
        for (attempt in 0 until 6) {
            val draft = compose(input, memory, items, Random((salt + "|" + input.date + "|" + attempt).hashCode()))
            if (best == null || draft.penalty < best.penalty) best = draft
            if (draft.penalty == 0) break
        }
        val chosen = best!!
        return Output(chosen.diary, memory.remember(factsOf(input, items), chosen.used), chosen.used, chosen.problems)
    }

    // ---------------------------------------------------------------- 문단 계획

    private fun compose(input: DayInput, memory: DiaryMemory, items: List<Item>, rnd: Random): Draft {
        val ctx = Ctx(memory, input.dayNumber, rnd)
        val paragraphs = mutableListOf<List<String>>()

        val head = mutableListOf(opener(input.summary, ctx))
        if (input.summary.fullMoons > 0 && items.none { it.events.first() is DiaryEvent.BloodMoon }) {
            head += ctx.say("fullmoon", mapOf("n" to input.summary.fullMoons.toString()), head)
        }
        paragraphs.add(head)

        val big = items.filter { it.events.first() is DiaryEvent.BloodMoon }
        val normal = items - big.toSet()
        if (items.isEmpty()) {
            paragraphs.add(listOf(ctx.say("quiet", emptyMap(), emptyList())))
        } else {
            val early = normal.filter { it.part.ordinal <= DayPart.DAY.ordinal }
            val late = normal.filter { it.part.ordinal > DayPart.DAY.ordinal }
            for (group in listOf(early, late)) {
                if (group.isEmpty()) continue
                val lines = mutableListOf<String>()
                for (item in group.sortedWith(compareBy<Item> { it.part.ordinal }.thenByDescending { it.score })) {
                    lines += sentence(item, ctx, lines)
                }
                paragraphs.add(lines)
            }
            for (item in big) paragraphs.add(listOf(sentence(item, ctx, emptyList())))
        }

        val tail = mutableListOf<String>()
        topNation(input.summary)?.let { tail += ctx.say("nation.top", mapOf("nation" to it), tail) }
        val bloodMoonToday = items.any { it.events.first() is DiaryEvent.BloodMoon }
        val since = memory.daysSinceBloodMoon(input.dayNumber)
        if (!bloodMoonToday && (since == null && input.dayNumber >= 4 || (since ?: 0) >= 3)) {
            tail += ctx.say("closer.nobloodmoon", emptyMap(), tail)
        }
        val shardToday = items.any { it.events.first() is DiaryEvent.VaccineShard }
        val shards = input.summary.vaccineShardsTotal
        if (shards != null && shards > 0 && shardToday) tail += ctx.say("closer.shards", mapOf("n" to shards.toString()), tail)
        if (tail.size < 2) tail += ctx.say("closer.next", mapOf("b" to fmt(input.summary.serverInfectionAfter)), tail)
        paragraphs.add(tail)

        val dense = paragraphs.filter { it.isNotEmpty() }
        val title = "생존일지 · ${input.dayNumber}일째 (${input.date.monthValue}월 ${input.date.dayOfMonth}일)"
        val diary = Diary(title, dense.map { it.joinToString(" ") })
        val report = StyleLinter.check(dense.flatten(), voice)
        val lengthPenalty = if (diary.text().length > maxLength) 20 else 0
        return Draft(diary, ctx.used, report.penalty + lengthPenalty, report.problems)
    }

    private fun opener(s: DaySummary, ctx: Ctx): String {
        val d = s.serverInfectionAfter - s.serverInfectionBefore
        val id = when {
            d >= 0.15 -> "opener.rise"
            d <= -0.15 -> "opener.fall"
            else -> "opener.flat"
        }
        return ctx.say(id, mapOf("a" to fmt(s.serverInfectionBefore), "b" to fmt(s.serverInfectionAfter), "d" to fmt(kotlin.math.abs(d))), emptyList())
    }

    private fun topNation(s: DaySummary): String? {
        if (s.nationScores.isEmpty()) return null
        val max = s.nationScores.values.max()
        if (max <= 0.0) return null
        val tops = s.nationScores.filterValues { it == max }.keys
        return tops.singleOrNull()
    }

    // ---------------------------------------------------------------- 사건 → 문장

    private fun sentence(item: Item, ctx: Ctx, previous: List<String>): String {
        val e = item.events.first()
        val st = item.stories
        return when (e) {
            is DiaryEvent.ZombieDeath -> if (item.events.size > 1) {
                ctx.say("zombie.group", mapOf("part" to e.part.label, "victims" to names(item.events.map { (it as DiaryEvent.ZombieDeath).victim })), previous)
            } else {
                val id = when (e.variant) {
                    "러너" -> "zombie.runner"
                    "자폭 좀비" -> "zombie.bomber"
                    "잠복자" -> "zombie.lurker"
                    else -> "zombie.single"
                }
                ctx.say(id, mapOf("part" to e.part.label, "victim" to name(e.victim), "variant" to (e.variant ?: "좀비"), "zone" to zone(e.zone)), previous)
            }
            is DiaryEvent.AccidentDeath -> {
                val (plainVerbs, politeVerb) = Lexicon.accidentVerbs.getValue(e.cause)
                val past = repeatCount(e, ctx) - 1
                val vars = mapOf(
                    "victim" to name(e.victim), "zone" to zone(e.zone),
                    "accidentVerb" to plainVerbs[ctx.rnd.nextInt(plainVerbs.size)], "accidentVerbPolite" to politeVerb,
                    "count" to (past + item.events.size).toString(), "countWord" to countWord(item.events.size),
                )
                val id = when {
                    Story.REPEAT_ACCIDENT !in st -> "accident"
                    past == 0 -> "accident.today"
                    else -> "accident.repeat"
                }
                ctx.say(id, vars, previous)
            }
            is DiaryEvent.PvpKill -> {
                val id = when {
                    Story.FIRST_EVER in st -> "pvp.first"
                    Story.REVENGE in st -> "pvp.revenge"
                    Story.RIVALRY in st -> "pvp.rivalry"
                    else -> "pvp"
                }
                ctx.say(id, mapOf("killer" to name(e.killer), "victim" to name(e.victim), "assists" to e.assists.takeIf { it.isNotEmpty() }?.let { names(it) }, "zone" to zone(e.zone)), previous)
            }
            is DiaryEvent.RevenantRecovered ->
                ctx.say(if (Story.SELF_RECOVERY in st) "revenant.self" else "revenant.mate", mapOf("owner" to name(e.owner), "by" to name(e.by)), previous)
            is DiaryEvent.RevenantLooted -> ctx.say("revenant.looted", mapOf("owner" to name(e.owner), "by" to name(e.by)), previous)
            is DiaryEvent.AlphaKilled ->
                ctx.say(if (Story.FIRST_EVER in st) "alpha.first" else "alpha", mapOf("killers" to names(e.killers), "zone" to zone(e.zone)), previous)
            is DiaryEvent.NestDestroyed -> ctx.say("nest", mapOf("participants" to names(e.participants), "zone" to zone(e.zone)), previous)
            is DiaryEvent.BloodMoon -> {
                val lines = mutableListOf<String>()
                if (Story.FIRST_EVER in st) lines += ctx.say("bloodmoon.first", emptyMap(), previous)
                else if (Story.LONG_WAIT in st) lines += ctx.say("bloodmoon.wait", emptyMap(), previous)
                val vars = mapOf("participants" to names(e.participants).ifEmpty { null }, "target" to e.target)
                val after = if (lines.isEmpty()) "" else ".after"
                lines += ctx.say((if (e.defended) "bloodmoon.won" else "bloodmoon.lost") + after, vars, previous + lines)
                lines.joinToString(" ")
            }
            is DiaryEvent.LandmarkCaptured ->
                ctx.say(if (Story.FIRST_EVER in st) "landmark.first" else "landmark", mapOf("nation" to e.nation, "landmark" to e.landmark, "from" to e.from), previous)
            is DiaryEvent.Advancement -> if (item.events.size > 1) {
                val list = item.events.map { it as DiaryEvent.Advancement }.joinToString(", ") { "${name(it.person)}의 '${it.title}'" }
                ctx.say("advancement.group", mapOf("list" to list), previous)
            } else {
                ctx.say(if (e.rare) "advancement.rare" else "advancement", mapOf("person" to name(e.person), "title" to "'${e.title}'"), previous)
            }
            is DiaryEvent.Starforce ->
                ctx.say(if (Story.FIRST_EVER in st) "starforce.first" else "starforce", mapOf("person" to name(e.person), "item" to e.item, "star" to e.star.toString()), previous)
            is DiaryEvent.VaccineShard -> ctx.say("shard", mapOf("person" to name(e.person)), previous)
        }
    }

    private fun countWord(n: Int): String = when (n) {
        1 -> "한 번"
        2 -> "두 번"
        3 -> "세 번"
        4 -> "네 번"
        else -> "${n}번"
    }

    private fun repeatCount(e: DiaryEvent.AccidentDeath, ctx: Ctx): Int {
        val past = ctx.memory.recent(3, ctx.today).flatMap { it.accidents }.count { it.first == e.victim.id && it.second == e.cause }
        return past + 1
    }

    // ---------------------------------------------------------------- 이름·장소 (개인정보 보호)

    /** 동의하지 않았거나, 닉네임 자체가 개인정보 검사에서 거절되면 익명으로. */
    private fun name(p: PersonRef): String =
        if (p.showName && nicknameValidator.check(p.nickname).verdict != NicknameValidator.Verdict.REJECT) p.nickname else ANONYMOUS

    private fun names(people: List<PersonRef>): String {
        val unique = people.distinctBy { it.id }
        val shown = unique.map { name(it) }.filter { it != ANONYMOUS }
        val hidden = unique.size - shown.size
        val parts = shown + listOfNotNull(anonymousCount(hidden))
        return KoreanJosa.join(parts)
    }

    private fun anonymousCount(n: Int): String? = when (n) {
        0 -> null
        1 -> ANONYMOUS
        2 -> "생존자 둘"
        3 -> "생존자 셋"
        4 -> "생존자 넷"
        else -> "생존자 ${n}명"
    }

    private fun zone(z: Zone): String? = when (z) {
        is Zone.Territory -> "${z.nation} 영토"
        Zone.Camp -> "생존자 캠프"
        is Zone.Wilderness -> z.direction?.let { "$it 황야" } ?: "황야"
        is Zone.Landmark -> z.name
        Zone.Nest -> "감염 둥지 근처"
        Zone.Nether -> "네더"
        Zone.End -> "엔드"
        Zone.Unknown -> null
    }

    // ---------------------------------------------------------------- 문장 고르기

    private inner class Ctx(val memory: DiaryMemory, val today: Int, val rnd: Random) {
        val used = mutableListOf<String>()
        /** 문단을 넘어서도 앞 문장과 겹치지 않게, 지금까지 쓴 문장 전체. */
        val history = mutableListOf<String>()

        fun say(id: String, vars: Map<String, String?>, previous: List<String>): String {
            val phrase = Lexicon[id]
            val candidates = phrase.forVoice(voice).withIndex()
                .filter { (_, t) -> TemplateRenderer.requires(t).all { !vars[it].isNullOrEmpty() } }
            check(candidates.isNotEmpty()) { "채울 수 있는 틀이 없음: $id $vars" }
            val scored = candidates.map { (i, t) ->
                val text = TemplateRenderer.render(t, vars, rnd)
                val key = "$id#$i"
                val lint = StyleLinter.check(history.takeLast(2) + text, voice).penalty
                val ago = memory.phraseLastUsed[key]?.let { today - it }
                val recency = when {
                    ago == null -> 0.0
                    ago <= 2 -> 4.0
                    ago <= 4 -> 2.0
                    else -> 0.0
                }
                val repeatedToday = if (key in used) 3.0 else 0.0
                Triple(key, text, lint + recency + repeatedToday + rnd.nextDouble() * 1.5)
            }
            val best = scored.minBy { it.third }
            used += best.first
            history += best.second
            return best.second
        }
    }

    private fun factsOf(input: DayInput, items: List<Item>): DayFacts {
        val ev = input.events
        val firsts = buildSet {
            if (ev.any { it is DiaryEvent.PvpKill }) add("pvp")
            if (ev.any { it is DiaryEvent.AlphaKilled }) add("alpha")
            if (ev.any { it is DiaryEvent.BloodMoon }) add("bloodmoon")
            if (ev.any { it is DiaryEvent.LandmarkCaptured }) add("landmark")
            ev.filterIsInstance<DiaryEvent.Starforce>().forEach { add("star${it.star}") }
        }
        return DayFacts(
            dayNumber = input.dayNumber,
            pvp = ev.filterIsInstance<DiaryEvent.PvpKill>().map { it.killer.id to it.victim.id },
            accidents = ev.filterIsInstance<DiaryEvent.AccidentDeath>().map { it.victim.id to it.cause },
            featured = items.flatMap { it.people }.toSet(),
            alphaKills = ev.count { it is DiaryEvent.AlphaKilled },
            bloodMoon = ev.any { it is DiaryEvent.BloodMoon },
            landmarkCaptures = ev.count { it is DiaryEvent.LandmarkCaptured },
            topNation = topNation(input.summary),
            firsts = firsts,
        )
    }

    companion object {
        const val ANONYMOUS = "한 생존자"
        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    }
}
