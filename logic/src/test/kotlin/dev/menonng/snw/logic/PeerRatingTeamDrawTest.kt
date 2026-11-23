package dev.menonng.snw.logic

import dev.menonng.snw.logic.discord.PeerRating
import dev.menonng.snw.logic.discord.PeerRating.Rating
import dev.menonng.snw.logic.discord.TeamDraw
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PeerRatingTeamDrawTest {
    private val cats = PeerRating.DEFAULT_CATEGORIES.map { it.id }

    @Test fun trimmedMeanAndShrink() {
        assertEquals(3.0, PeerRating.mean(listOf(1, 3, 5)))
        assertEquals(3.5, PeerRating.mean(listOf(1, 3, 3, 4, 4, 5)))      // 6개부터 최고·최저 버림
        val rs = listOf(Rating("a", "t", mapOf("combat" to 5)), Rating("b", "u", mapOf("combat" to 1)), Rating("c", "u", mapOf("combat" to 3)))
        val agg = PeerRating.aggregate(rs, listOf("combat"))
        val mu = 3.0
        assertEquals((1 * 5 + 3 * mu) / 4, agg["t"]!!["combat"]!!.value, 1e-9)        // 표본 1개는 평균 쪽으로
        assertEquals(1, agg["t"]!!["combat"]!!.n)
    }

    @Test fun unknownIsSkippedAndOutlierFlagged() {
        val rs = (1..5).map { Rating("r$it", "t", cats.associateWith { 4 }) } + Rating("bad", "t", cats.associateWith { 1 }) +
            Rating("x", "t", cats.associateWith { null })
        val agg = PeerRating.aggregate(rs, cats)
        assertEquals(6, agg["t"]!!["combat"]!!.n)
        val o = PeerRating.outliers(rs, cats)
        assertEquals(listOf("bad"), o.map { it.rater })
        assertTrue(PeerRating.starsLine(agg["t"]!!["combat"]!!).startsWith("⭐"))
    }

    private fun sample(n: Int, seed: Int): TeamDraw.Input {
        val r = Random(seed)
        val ps = (1..n).map { TeamDraw.Player("p$it", 2.0 + r.nextDouble() * 2.5, listOf(2.0, 5.0, 10.0, 18.0)[r.nextInt(4)], 1.0 + r.nextDouble() * 4) }
        return TeamDraw.Input(ps, listOf("p1", "p2", "p3"), listOf("p4" to "p5", "p5" to "p4", "p6" to "p7"))
    }

    @Test fun drawIsReproducibleAndBalanced() {
        val inp = sample(20, 1)
        val a = TeamDraw.draw(inp, "seed-abc"); val b = TeamDraw.draw(inp, "seed-abc")
        assertEquals(a.teams, b.teams)                                     // 같은 시드 → 같은 결과
        assertNotEquals(a.teams, TeamDraw.draw(inp, "seed-xyz").teams)
        assertEquals(20, a.teams.sumOf { it.size }); assertEquals(20, a.teams.flatten().toSet().size)
        assertTrue(a.metrics.sizeGap <= 1)
        listOf("p1", "p2", "p3").forEach { l -> assertEquals(1, a.teams.count { l in it }) }
        assertEquals(3, a.teams.count { t -> listOf("p1", "p2", "p3").any { it in t } })   // 팀장은 국가마다 한 명
        val passed = (1..20).count { TeamDraw.draw(sample(20, it), "s$it").passed }
        assertTrue(passed >= 18, "가드 통과 $passed/20")
    }

    @Test fun mutualBuddiesStayTogether() {
        val inp = sample(20, 2)
        val r = TeamDraw.draw(inp, "seed-buddy")
        assertTrue(r.teams.any { "p4" in it && "p5" in it }, "서로 지목한 단짝은 같은 국가")
        assertEquals(0, r.metrics.brokenBuddies)
        assertEquals(64, TeamDraw.seedHash("x").length)
    }

    @Test fun swapRespectsGuardAndOptimizeRuns() {
        val inp = sample(18, 3)
        val p = TeamDraw.prepare(inp)
        val r = TeamDraw.draw(inp, "seed-swap")
        val a = r.teams[0].first { it !in inp.leaders }; val b = r.teams[1].first { it !in inp.leaders }
        val s = TeamDraw.swap(r.teams, a, b, p)
        if (s != null) assertTrue(TeamDraw.passes(TeamDraw.metrics(s, p)))
        val opt = TeamDraw.optimize(inp, "opt", restarts = 3, steps = 800)
        assertTrue(opt.isNotEmpty())
        assertEquals(10.0, TeamDraw.hoursOfBand("7~14h"))
    }
}
