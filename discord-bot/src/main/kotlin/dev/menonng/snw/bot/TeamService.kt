package dev.menonng.snw.bot

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.menonng.snw.logic.discord.PeerRating
import dev.menonng.snw.logic.discord.TeamDraw
import java.security.SecureRandom

/**
 * 국가 추첨식(peer-rating.md 5절): 팀장 → 시드 해시 공개 → 추첨(가중 + 재추첨 가드) → 한 명씩 공개 → 시드 공개 → 24시간 맞교환 → 확정.
 * 상태는 kv 'team' 에 JSON 으로 둔다(재시작해도 이어진다).
 */
class TeamService(private val db: Db, private val hub: ModHub) {
    data class State(var leaders: List<String> = emptyList(), var seed: String? = null, var teams: List<List<String>> = emptyList(),
                     var order: List<Pair<Int, List<String>>> = emptyList(), var passed: Boolean = false, var tries: Int = 0,
                     var swapUntil: Long = 0, var swapRequests: MutableMap<String, String> = mutableMapOf(), var confirmed: Boolean = false)
    private val gson = Gson()
    var state: State = db.get("team")?.let { runCatching { gson.fromJson(it, State::class.java) }.getOrNull() } ?: State(); private set
    private fun save() = db.put("team", gson.toJson(state))

    val NATIONS = listOf(Triple("n1", "1국", 0xFF0045), Triple("n2", "2국", 0x3355BB), Triple("n3", "3국", 0xFFCC11))

    fun setLeaders(ids: List<String>): String? {
        if (ids.size != 3 || ids.toSet().size != 3) return "팀장은 서로 다른 3명이어야 합니다."
        val users = db.linkedUsers().associateBy { it.discordId }
        if (ids.any { it !in users }) return "연동하지 않은 사람이 있습니다."
        // 예상 시간 상위 절반에서만(5-1)
        val sorted = users.values.sortedByDescending { TeamDraw.hoursOfBand(it.weeklyBand) }
        val half = sorted.take((sorted.size + 1) / 2).map { it.discordId }.toSet()
        if (ids.any { it !in half }) return "팀장은 예상 플레이 시간 상위 절반에서만 고를 수 있습니다."
        state = State(leaders = ids); save(); return null
    }

    fun input(): TeamDraw.Input {
        val users = db.linkedUsers()
        val round = db.lastClosedRound()
        val agg = round?.let { PeerRating.aggregate(db.ratings(it.id), it.categories) } ?: emptyMap()
        val ps = users.map { u ->
            val a = agg[u.discordId]
            TeamDraw.Player(u.discordId, a?.values?.map { it.value }?.average() ?: 3.0, TeamDraw.hoursOfBand(u.weeklyBand), a?.get("passion")?.value ?: 3.0)
        }
        return TeamDraw.Input(ps, state.leaders, db.buddies())
    }

    /** 추첨: 시드를 만들어 해시를 돌려준다(먼저 공개). 결과는 공개 순서대로 order 에. */
    fun draw(): Pair<String, TeamDraw.Result>? {
        if (state.leaders.size != 3 || state.confirmed) return null
        val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val res = TeamDraw.draw(input(), seed)
        state = state.copy(seed = seed, teams = res.teams, order = res.order, passed = res.passed, tries = res.tries,
            swapUntil = System.currentTimeMillis() + 24 * 3_600_000L, swapRequests = mutableMapOf())
        save()
        return TeamDraw.seedHash(seed) to res
    }

    /** 시험 정리용: 추첨 상태를 비운다. */
    fun reset() { state = State(); save() }

    fun nationOf(discordId: String): Int = state.teams.indexOfFirst { discordId in it }

    /** 맞교환 신청. 서로 신청하면 가드를 확인하고 바꾼다. 결과 문장. */
    fun requestSwap(from: String, to: String, now: Long = System.currentTimeMillis()): String {
        if (state.teams.isEmpty() || state.confirmed) return "교환할 추첨 결과가 없습니다."
        if (now > state.swapUntil) return "교환 창(24시간)이 끝났습니다."
        if (from in state.leaders || to in state.leaders) return "팀장은 교환할 수 없습니다."
        val a = nationOf(from); val b = nationOf(to)
        if (a < 0 || b < 0 || a == b) return "서로 다른 국가 사람끼리만 바꿀 수 있습니다."
        if (state.swapRequests[to] == from) {
            val next = TeamDraw.swap(state.teams, from, to, TeamDraw.prepare(input()))
                ?: return "바꾸면 국가 균형 기준을 넘어서 교환할 수 없습니다.".also { state.swapRequests.remove(to); save() }
            state.teams = next; state.swapRequests.remove(to); save()
            return "교환했습니다."
        }
        state.swapRequests[from] = to; save()
        return "상대에게 교환을 신청했습니다. 상대도 신청하면 바뀝니다."
    }

    /** 확정: 국가 배정을 모드에 보낸다(팀장 = 원수). 디스코드 역할·채널은 화면 쪽이 만든다. */
    fun confirm(): Map<String, Pair<String, String>>? {
        if (state.teams.isEmpty() || state.confirmed) return null
        val assign = LinkedHashMap<String, Pair<String, String>>()
        state.teams.forEachIndexed { i, t -> t.forEach { id -> assign[id] = NATIONS[i].first to (if (id in state.leaders) "LEADER" else "CITIZEN") } }
        hub.assignNations(assign, NATIONS.associate { it.first to it.second })
        state.confirmed = true; save()
        return assign
    }

    /** 공개 입력(재현용): 개인 점수 대신 순위 구간만. */
    fun publicInput(): String {
        val inp = input(); val n = inp.players.size
        val rank = inp.players.sortedByDescending { it.stars }.mapIndexed { i, p -> p.id to (i * 5 / maxOf(1, n) + 1) }.toMap()
        return inp.players.joinToString("\n") { p -> "${db.user(p.id)?.nickname}: 별점 구간 ${rank[p.id]}/5, 예상 시간 ${p.hours}h" + (if (p.id in inp.leaders) " (팀장)" else "") }
    }

    fun optimizeSummary(): String {
        val opts = TeamDraw.optimize(input(), "opt-" + System.currentTimeMillis())
        return opts.mapIndexed { k, (j, teams) -> "안 ${k + 1} (J=${"%.2f".format(j)}): " + teams.mapIndexed { i, t -> "${NATIONS[i].second} " + t.mapNotNull { db.user(it)?.nickname }.joinToString(",") }.joinToString(" / ") }.joinToString("\n")
    }

    @Suppress("unused") private val listType = object : TypeToken<List<List<String>>>() {}.type
}
