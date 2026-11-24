package dev.menonng.snw.bot

import dev.menonng.snw.logic.discord.TeamDraw
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 시험 모드(config testMode)의 가짜 멤버 도구. 디스코드 계정이 아니라 DB 에만 있는 멤버라 DM·역할은 건너뛴다(id 가 test- 로 시작).
 * 운영자 한 명이 실제 디스코드 화면(DM·모달·공지)으로 평가·추첨 흐름을 끝까지 볼 수 있게 한다.
 */
class TestKit(private val db: Db, private val ratings: RatingService, private val teams: TeamService) {
    companion object {
        const val PREFIX = "test-"
        const val MAX = 20
        fun isDummy(id: String) = id.startsWith(PREFIX)
        fun idOf(n: Int) = PREFIX + "%02d".format(n)
        private val BANDS = listOf("~3h", "3~7h", "7~14h", "14h~")
        private const val SELF_MARK = "test.self."
    }

    fun dummies(): List<Db.User> = db.allUsers().filter { isDummy(it.discordId) }.sortedBy { it.discordId }

    /** 가짜 멤버를 n 명이 되게 채운다(이미 있으면 그대로). 만든 수. */
    fun addDummies(n: Int, now: Long = System.currentTimeMillis(), rnd: Random = Random.Default): Int {
        var made = 0
        for (i in 1..n.coerceIn(1, MAX)) {
            if (db.user(idOf(i)) != null) continue
            db.upsertUser(Db.User(idOf(i), null, null, "더미%02d".format(i), true, false, null, BANDS[rnd.nextInt(BANDS.size)], now, now))
            made++
        }
        if (made > 0) ratings.pushInfo()
        return made
    }

    /** 운영자 본인을 참가자로(게임 연동 없이). 이미 연동했으면 그대로. 새로 만든/바꾼 경우 true. */
    fun linkSelf(id: String, now: Long = System.currentTimeMillis()): Boolean {
        val u = db.user(id)
        if (u?.linkedAt != null) return false
        db.upsertUser(u?.copy(linkedAt = now) ?: Db.User(id, null, null, uniqueNick("테스터"), true, false, null, "14h~", now, now))
        db.put(SELF_MARK + id, if (u == null) "created" else "linked")
        return true
    }
    private fun uniqueNick(base: String): String = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { db.userByNickname(it) == null }

    /**
     * 열린 라운드에서 가짜 멤버들이 모두를(운영자 포함) 평가한다. 사람마다 숨은 실력이 있고 거기에 흔들림을 더하며, 15% 는 '잘 모름'.
     * 더미01 은 한 사람에게 일부러 1점을 몰아줘서 이상치 경고를 시험한다. 제출 수(평가 대상 수).
     */
    fun fillRatings(rnd: Random = Random.Default, now: Long = System.currentTimeMillis()): Int? {
        val r = db.openRound() ?: return null
        val people = ratings.participants()
        val skill = people.associate { p -> p.discordId to r.categories.associateWith { 2.0 + rnd.nextDouble() * 3.0 } }
        val victim = people.filter { !isDummy(it.discordId) }.ifEmpty { people }.maxByOrNull { skill[it.discordId]!!.values.average() }?.discordId
        var n = 0
        for (rater in people.filter { isDummy(it.discordId) }) for (t in people) {
            if (t.discordId == rater.discordId) continue
            val scores = r.categories.associateWith { c ->
                when {
                    rater.discordId == idOf(1) && t.discordId == victim -> 1
                    rnd.nextDouble() < 0.15 -> null
                    else -> (skill[t.discordId]!![c]!! + (rnd.nextDouble() - 0.5) * 1.4).roundToInt().coerceIn(1, 5)
                }
            }
            db.putRating(r.id, rater.discordId, t.discordId, scores, now); n++
        }
        ratings.pushInfo()
        return n
    }

    /** 가짜 멤버끼리 서로 지목한 단짝 두 쌍. */
    fun pairBuddies(): List<Pair<String, String>> {
        val d = dummies().map { it.discordId }
        val pairs = d.chunked(2).filter { it.size == 2 }.take(2).map { it[0] to it[1] }
        for ((a, b) in pairs) { db.setBuddy(a, b); db.setBuddy(b, a) }
        return pairs
    }

    /** 예상 시간 상위 절반에서 팀장 3명을 고른다. withSelf 면 운영자를 포함(상위 절반일 때). 오류 문장 또는 null. */
    fun autoLeaders(selfId: String?, rnd: Random = Random.Default): String? {
        val users = db.linkedUsers().sortedByDescending { TeamDraw.hoursOfBand(it.weeklyBand) }
        val half = users.take((users.size + 1) / 2).map { it.discordId }
        if (half.size < 3) return "참가자가 너무 적습니다(6명 이상 필요)."
        val pick = mutableListOf<String>()
        if (selfId != null) { if (selfId !in half) return "운영자가 예상 시간 상위 절반에 없습니다."; pick += selfId }
        pick += half.filter { it !in pick }.shuffled(rnd).take(3 - pick.size)
        return teams.setLeaders(pick)
    }

    /** 가짜 멤버 n 번과 운영자의 맞교환(양쪽 신청을 한 번에). 결과 문장. */
    fun swapWith(n: Int, selfId: String): String {
        val dummy = idOf(n)
        if (db.user(dummy) == null) return "더미%02d 가 없습니다.".format(n)
        val first = teams.requestSwap(dummy, selfId)
        if (!first.startsWith("상대에게")) return first
        return teams.requestSwap(selfId, dummy)
    }

    /** 마감을 지금부터 minutes 분 뒤로 당긴다(마감 12시간 전 독촉 → 자동 마감까지 시험). */
    fun pullDeadline(minutes: Int, now: Long = System.currentTimeMillis()): Db.Round? {
        val r = db.openRound() ?: return null
        db.setDeadline(r.id, now + minutes.coerceIn(1, 24 * 60) * 60_000L)
        ratings.pushInfo()
        return db.openRound()
    }

    /** 가짜 멤버와 그 평가·단짝, 시험으로 만든 운영자 참가 기록, 가짜 멤버가 낀 추첨 상태를 지운다. 요약 문장. */
    fun cleanup(selfId: String?): String {
        val ds = dummies().map { it.discordId }
        val teamHadDummy = (teams.state.leaders + teams.state.teams.flatten()).any { isDummy(it) }
        ds.forEach { db.deleteUser(it) }
        var self = ""
        if (selfId != null) when (db.get(SELF_MARK + selfId)) {
            "created" -> { db.deleteUser(selfId); self = ", 운영자 시험 참가 기록 삭제" }
            "linked" -> { db.user(selfId)?.let { db.upsertUser(it.copy(linkedAt = null)) }; self = ", 운영자 시험 연동 해제" }
        }
        if (selfId != null) db.put(SELF_MARK + selfId, "")
        if (teamHadDummy) teams.reset()
        ratings.pushInfo()
        return "가짜 멤버 ${ds.size}명과 그 평가·단짝 삭제" + self + (if (teamHadDummy) ", 추첨 상태 초기화" else "")
    }
}
