package dev.menonng.snw.bot

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestKitTest {
    private val front = object : Front {
        override fun alert(text: String) {}
        override fun serverStatus(online: Boolean) {}
        override fun linked(discordId: String, nickname: String) {}
    }

    @Test fun soloOperatorRunsWholeSeasonFlow() {
        val db = Db("jdbc:sqlite:${Files.createTempFile("kit", ".db")}")
        val hub = ModHub(db, front)
        val rs = RatingService(db, hub)
        val ts = TeamService(db, hub)
        val kit = TestKit(db, rs, ts)
        val me = "123456789012345678"
        val rnd = Random(7)

        assertTrue(kit.linkSelf(me)); assertTrue(!kit.linkSelf(me))
        assertEquals(11, kit.addDummies(11, rnd = rnd)); assertEquals(0, kit.addDummies(11, rnd = rnd))
        assertEquals(12, rs.participants().size)

        assertNull(kit.fillRatings(rnd), "열린 라운드가 없으면 못 채운다")
        val round = rs.start(null, 48)!!
        assertEquals(11 * 11, kit.fillRatings(rnd))
        assertEquals(0 to 11, rs.progress(round, me), "운영자 본인 평가는 직접(모달) 한다")
        assertEquals(11 to 11, rs.progress(round, TestKit.idOf(3)))

        val pulled = kit.pullDeadline(2)!!
        assertTrue(pulled.deadline - System.currentTimeMillis() in 60_000..121_000 && !pulled.reminded)

        val closed = rs.close()!!
        assertNotNull(rs.resultFor(closed, me), "운영자도 결과를 받는다")
        assertTrue(rs.outlierReport(closed).contains("더미01"), "더미01 의 몰아주기가 이상치로 잡힌다")

        assertEquals(2, kit.pairBuddies().size)
        assertNull(kit.autoLeaders(me, rnd))
        assertTrue(me in ts.state.leaders)
        assertNotNull(ts.draw())
        assertEquals(12, ts.state.teams.flatten().size)
        val other = (1..11).first { ts.nationOf(TestKit.idOf(it)) != ts.nationOf(me) && TestKit.idOf(it) !in ts.state.leaders }
        assertEquals("팀장은 교환할 수 없습니다.", kit.swapWith(other, me))

        val summary = kit.cleanup(me)
        assertTrue(summary.contains("11명") && summary.contains("추첨 상태 초기화"), summary)
        assertTrue(db.allUsers().isEmpty(), "시험으로 만든 운영자 기록까지 지운다")
        assertTrue(db.ratings(closed.id).isEmpty() && db.buddies().isEmpty() && ts.state.teams.isEmpty())
    }

    @Test fun cleanupKeepsRealMembersAndOnlyUnlinksSelf() {
        val db = Db("jdbc:sqlite:${Files.createTempFile("kit", ".db")}")
        val hub = ModHub(db, front)
        val rs = RatingService(db, hub); val ts = TeamService(db, hub); val kit = TestKit(db, rs, ts)
        db.upsertUser(Db.User("111", "00000000-0000-0000-0000-000000000001", "mc1", "가람", true, false, null, "3~7h", 0L, 1L))
        db.upsertUser(Db.User("222", null, null, "나래", true, false, null, null, 0L, null))     // 등록만 한 운영자
        assertTrue(kit.linkSelf("222"))
        kit.addDummies(5)
        kit.cleanup("222")
        assertEquals(setOf("111", "222"), db.allUsers().map { it.discordId }.toSet())
        assertNull(db.user("222")!!.linkedAt, "원래 등록 기록은 남기고 시험 연동만 푼다")
        assertTrue(TestKit.isDummy(TestKit.idOf(1)) && !TestKit.isDummy("111"))
    }
}
