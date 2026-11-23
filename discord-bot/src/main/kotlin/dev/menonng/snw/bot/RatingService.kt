package dev.menonng.snw.bot

import dev.menonng.snw.logic.discord.PeerRating
import dev.menonng.snw.logic.discord.Protocol

/**
 * 상호평가 라운드(docs/discord/peer-rating.md 1·4절). 대상 = 연동한 생존자 전원, 자기 자신은 뺀다.
 * 원자료(누가 누구에게 몇 점)는 DB 에만 두고, 화면에는 진행 수·본인 점수·(공개를 고르면) 항목별 상위 3명만 낸다.
 */
class RatingService(private val db: Db, private val hub: ModHub) {
    fun categories(ids: List<String>?): List<PeerRating.Category> {
        val all = PeerRating.DEFAULT_CATEGORIES
        val pick = ids?.mapNotNull { id -> all.firstOrNull { it.id == id.trim() || it.label.contains(id.trim()) } }?.takeIf { it.isNotEmpty() } ?: all
        return pick.take(5)
    }
    fun label(id: String) = PeerRating.DEFAULT_CATEGORIES.firstOrNull { it.id == id }?.label ?: id

    fun participants(): List<Db.User> = db.linkedUsers()

    /** 라운드 시작. 이미 열려 있으면 null. 게임 안 평가 창용으로 모드에 rating.request 를 보낸다. */
    fun start(categoryIds: List<String>?, hours: Int, now: Long = System.currentTimeMillis()): Db.Round? {
        if (db.openRound() != null) return null
        val cats = categories(categoryIds).map { it.id }
        val id = db.newRound(cats, now, now + hours * 3_600_000L)
        val users = participants()
        hub.send(Protocol.RATING_REQUEST, mapOf("round" to id, "deadline" to now + hours * 3_600_000L,
            "categories" to cats.map { mapOf("id" to it, "label" to label(it)) },
            "pairs" to users.filter { it.mcUuid != null }.map { u -> mapOf("rater" to u.mcUuid, "targets" to users.filter { it.discordId != u.discordId && it.mcUuid != null }.map { t -> mapOf("uuid" to t.mcUuid, "nickname" to t.nickname) }) }))
        return db.openRound()
    }

    fun targetsFor(rater: String): List<Db.User> = participants().filter { it.discordId != rater }
    fun progress(round: Db.Round, rater: String): Pair<Int, Int> {
        val targets = targetsFor(rater).map { it.discordId }.toSet()
        return db.ratedTargets(round.id, rater).count { it in targets } to targets.size
    }
    fun nextTarget(round: Db.Round, rater: String): Db.User? { val done = db.ratedTargets(round.id, rater); return targetsFor(rater).firstOrNull { it.discordId !in done } }

    /** 평가 제출(디스코드 모달 또는 게임 안 창). 마감 전까지 고칠 수 있다. 별 1~5, null = 잘 모름. */
    fun submit(rater: String, target: String, scores: Map<String, Int?>, now: Long = System.currentTimeMillis()): Boolean {
        val r = db.openRound() ?: return false
        if (now > r.deadline || rater == target) return false
        if (participants().none { it.discordId == target }) return false
        db.putRating(r.id, rater, target, scores.filterKeys { it in r.categories }.mapValues { (_, v) -> v?.takeIf { it in 1..5 } }, now)
        return true
    }

    /** 게임 안 창의 rating.submit(UUID 기준). */
    fun submitFromGame(raterUuid: String, targetUuid: String, scores: Map<String, Int?>): Boolean {
        val a = db.userByUuid(raterUuid) ?: return false; val b = db.userByUuid(targetUuid) ?: return false
        return submit(a.discordId, b.discordId, scores)
    }

    /** /평가 현황: 누가 몇 명 평가했는지만(점수 없음). */
    fun status(): List<Triple<String, Int, Int>> {
        val r = db.openRound() ?: return emptyList()
        return participants().map { u -> val (d, t) = progress(r, u.discordId); Triple(u.nickname, d, t) }
    }

    fun close(): Db.Round? { val r = db.openRound() ?: return null; db.setRoundStatus(r.id, "closed"); return r }

    fun aggregate(round: Db.Round) = PeerRating.aggregate(db.ratings(round.id), round.categories)

    /** 본인에게 보낼 결과 문장. */
    fun resultFor(round: Db.Round, discordId: String): String? {
        val agg = aggregate(round)[discordId] ?: return null
        return "상호평가 결과(라운드 ${round.id})\n" + round.categories.joinToString("\n") { c -> "${label(c)}  ${agg[c]?.let { PeerRating.starsLine(it) } ?: "-"}" } +
            "\n\n평가가 적게 모인 항목은 전체 평균 쪽으로 보정했습니다. 다른 사람의 점수와 누가 몇 점을 줬는지는 공개하지 않습니다."
    }

    /** 공개를 고를 때: 항목별 상위 3명(낮은 점수·순위는 내지 않는다). */
    fun publicSummary(round: Db.Round): String {
        val agg = aggregate(round)
        return "📊 상호평가 결과 — 항목별 상위 3명\n" + round.categories.joinToString("\n") { c ->
            "${label(c)}: " + PeerRating.top3(agg, c).mapNotNull { db.user(it)?.nickname }.joinToString(", ")
        }
    }

    /** 관리자 이상치 경고(원자료 점수는 보이지 않고 '누가 누구에게 몰아줬는지'만). */
    fun outlierReport(round: Db.Round): String {
        val o = PeerRating.outliers(db.ratings(round.id), round.categories)
        if (o.isEmpty()) return "이상치 없음"
        return o.joinToString("\n") { "⚠ ${db.user(it.rater)?.nickname} → ${db.user(it.target)?.nickname}: ${it.categories.joinToString(", ") { c -> label(c) }} 에서 다른 평가와 크게 다름" }
    }
}
