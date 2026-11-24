package dev.menonng.snw.bot

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.menonng.snw.logic.discord.Protocol
import dev.menonng.snw.logic.discord.Ulid
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonFlowTest {
    private val front = object : Front {
        override fun alert(text: String) {}
        override fun serverStatus(online: Boolean) {}
        override fun linked(discordId: String, nickname: String) {}
    }

    private fun setup(n: Int): Triple<Db, ModHub, MutableList<String>> {
        val db = Db("jdbc:sqlite:${Files.createTempFile("season", ".db")}")
        val hub = ModHub(db, front)
        val sent = mutableListOf<String>()
        hub.onConnect { sent += it }
        val bands = listOf("~3h", "3~7h", "7~14h", "14h~")
        val names = listOf("가람","나래","다온","라온","마루","바다","사랑","아라","자람","차오","카이","타래","파랑","하늘","가온","나무","다솜","로아")
        for (i in 1..n) {
            val uuid = "00000000-0000-0000-0000-%012d".format(i)
            db.upsertUser(Db.User("d$i", uuid, "mc$i", names[i - 1], true, false, null, bands[i % 4], 0L, 1L))
        }
        return Triple(db, hub, sent)
    }

    @Test fun ratingRoundEndToEnd() {
        val (db, hub, sent) = setup(8)
        val rs = RatingService(db, hub)
        val round = rs.start(null, 48)
        assertNotNull(round)
        assertNull(rs.start(null, 48), "라운드는 하나만")
        fun lastInfo() = sent.map { JsonParser.parseString(it).asJsonObject }.last { it["type"].asString == Protocol.RATING_INFO }["data"].asJsonObject.getAsJsonArray("players")
            .associate { it.asJsonObject["uuid"].asString to it.asJsonObject }
        val first = lastInfo()
        assertEquals(8, first.size)
        assertEquals(7, first["00000000-0000-0000-0000-000000000001"]!!.getAsJsonObject("open")["total"].asInt)   // 자기 자신 제외
        assertTrue(first.values.none { it.has("result") }, "공개 전에는 점수가 가지 않는다")

        // 디스코드 모달 제출: d1 은 모두 4점, 나머지는 d2 에게 5점
        for (i in 2..8) assertTrue(rs.submit("d1", "d$i", round!!.categories.associateWith { 4 }))
        assertTrue(!rs.submit("d1", "d1", mapOf("combat" to 5)), "자기 자신은 평가할 수 없다")
        assertEquals(7 to 7, rs.progress(round!!, "d1"))
        assertNull(rs.nextTarget(round, "d1"))
        for (i in 3..8) rs.submit("d$i", "d2", round.categories.associateWith { if (it == "build") null else 5 })

        assertEquals(7, lastInfo()["00000000-0000-0000-0000-000000000001"]!!.getAsJsonObject("open")["done"].asInt)   // 디스코드 평가가 게임 진행에 반영
        assertTrue(Protocol.RATING_INFO !in Protocol.NEEDS_ACK)

        val st = rs.status(); assertEquals(8, st.size); assertTrue(st.none { it.toString().contains("4.") }, "현황에 점수가 나오지 않는다")
        val closed = rs.close()!!
        val res = rs.resultFor(closed, "d2")!!
        assertTrue(res.contains("⭐")); assertTrue(res.contains("공개하지 않습니다"))
        assertTrue(rs.publicSummary(closed).contains(db.user("d2")!!.nickname), "공개 요약에 상위자")
        assertTrue(lastInfo().values.none { it.has("result") }, "마감만으로는 점수가 가지 않는다(결과 공개 뒤에만)")
        rs.release(closed)
        val after = lastInfo()
        val mine = after["00000000-0000-0000-0000-000000000002"]!!.getAsJsonObject("result")
        assertEquals(5, mine.getAsJsonArray("categories").size())
        assertTrue(mine.toString().contains("value"))
        assertTrue(!after["00000000-0000-0000-0000-000000000002"]!!.has("open"), "마감 뒤에는 진행 칸이 없다")
    }

    @Test fun teamDrawSwapConfirm() {
        val (db, hub, sent) = setup(15)
        val ts = TeamService(db, hub)
        // 팀장은 예상 시간 상위 절반에서만
        val sorted = db.linkedUsers().sortedByDescending { dev.menonng.snw.logic.discord.TeamDraw.hoursOfBand(it.weeklyBand) }
        val low = sorted.last().discordId
        assertNotNull(ts.setLeaders(listOf(sorted[0].discordId, sorted[1].discordId, low)))
        assertNull(ts.setLeaders(sorted.take(3).map { it.discordId }))
        db.setBuddy("d5", "d6"); db.setBuddy("d6", "d5")
        val (hash, res) = ts.draw()!!
        assertEquals(64, hash.length)
        assertEquals(15, res.teams.flatten().toSet().size)
        assertTrue(res.teams.any { "d5" in it && "d6" in it } || sorted.take(3).any { it.discordId in setOf("d5", "d6") })
        // 교환: 한쪽 신청 → 대기, 양쪽 → 가드 확인
        val a = ts.state.teams[0].first { it !in ts.state.leaders }; val b = ts.state.teams[1].first { it !in ts.state.leaders }
        assertTrue(ts.requestSwap(a, b).startsWith("상대에게"))
        val r2 = ts.requestSwap(b, a)
        assertTrue(r2 == "교환했습니다." || r2.contains("균형"), r2)
        val assign = ts.confirm()!!
        assertEquals(15, assign.size)
        assertEquals(3, assign.values.count { it.second == "LEADER" })
        val msg = sent.map { JsonParser.parseString(it).asJsonObject }.first { it["type"].asString == Protocol.NATION_ASSIGN }
        assertEquals(15, msg["data"].asJsonObject.getAsJsonArray("assignments").size())
        assertNull(ts.confirm(), "두 번 확정하지 않는다")
        assertTrue(ts.publicInput().contains("구간"))
        // 재시작해도 상태 유지
        assertTrue(TeamService(db, hub).state.confirmed)
    }
}
