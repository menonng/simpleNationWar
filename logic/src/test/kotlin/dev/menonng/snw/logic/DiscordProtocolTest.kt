package dev.menonng.snw.logic

import dev.menonng.snw.logic.discord.AlertText
import dev.menonng.snw.logic.discord.Dedup
import dev.menonng.snw.logic.discord.EventMapper
import dev.menonng.snw.logic.discord.LinkCodeBook
import dev.menonng.snw.logic.discord.Protocol
import dev.menonng.snw.logic.discord.Ulid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordProtocolTest {
    @Test fun ulidSortsByTime() {
        val a = Ulid.next(1_000L); val b = Ulid.next(2_000L)
        assertEquals(26, a.length)
        assertTrue(a < b)
        assertNotEquals(Ulid.next(5L), Ulid.next(5L))
    }

    @Test fun versionAndBackoff() {
        assertTrue(Protocol.compatible("1")); assertTrue(Protocol.compatible("1.2")); assertFalse(Protocol.compatible("2")); assertFalse(Protocol.compatible(null))
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L), (0..7).map { Protocol.backoffSeconds(it) })
        assertTrue(Protocol.needsAck(Protocol.DEATH)); assertFalse(Protocol.needsAck(Protocol.JOIN))
    }

    @Test fun dedupForgetsOldest() {
        val d = Dedup(2)
        assertTrue(d.firstTime("a")); assertFalse(d.firstTime("a"))
        d.firstTime("b"); d.firstTime("c")
        assertTrue(d.firstTime("a"))
    }

    @Test fun linkCodesReuseAndExpire() {
        val book = LinkCodeBook(ttlMillis = 1000, random = java.util.Random(1))
        val c1 = book.issue("u1", "steve", 0)
        assertEquals(c1, book.issue("u1", "steve", 500))              // 10분(여기선 1초) 안에는 같은 코드
        assertEquals(6, c1.code.length)
        assertNull(book.redeem(c1.code, 1500))                         // 만료
        val c2 = book.issue("u1", "steve", 2000)
        assertEquals("u1", book.redeem(c2.code, 2100)?.uuid)
        assertNull(book.redeem(c2.code, 2100))                         // 한 번만
    }

    @Test fun mapperDropsCoordinates() {
        assertNull(EventMapper.map("landmark_chest", mapOf("x" to 1)))
        val ev = EventMapper.map("weird_thing", mapOf("x" to 5, "nation" to "1국"))!!
        assertEquals(Protocol.SERVER_EVENT, ev.type)
        @Suppress("UNCHECKED_CAST") val inner = ev.data["data"] as Map<String, Any?>
        assertFalse("x" in inner); assertEquals("1국", inner["nation"])
        assertEquals(Protocol.VACCINE, EventMapper.map("vaccine_shard", mapOf("player" to "u", "source" to "nest"))!!.type)
        assertEquals("destroyed", EventMapper.map("nest_destroyed", mapOf("nation" to "n1"))!!.data["event"])
    }

    @Test fun alertTexts() {
        val names = mapOf("k" to "철수", "v" to "영희", "a" to "민수")
        val line = AlertText.render(Protocol.DEATH, mapOf("pvp" to true, "killer" to "k", "victim" to "v", "assists" to listOf("a")), names::get)
        assertEquals("⚔ 철수 → 영희 (어시스트: 민수)", line)
        assertNull(AlertText.render(Protocol.DEATH, mapOf("pvp" to false, "victim" to "v"), names::get))
        assertEquals("🧪 백신 조각을 누군가 주웠습니다 (서버 전체 4개)", AlertText.render(Protocol.VACCINE, mapOf("total" to 4), names::get))
        assertEquals("🧟 누군가 님이 망자가 되었습니다", AlertText.render(Protocol.REVENANT, mapOf("owner" to "zz"), names::get))
        assertTrue(AlertText.render(Protocol.ENDING, mapOf("result" to "salvation", "shards" to 7), names::get)!!.contains("구원"))
    }
}
