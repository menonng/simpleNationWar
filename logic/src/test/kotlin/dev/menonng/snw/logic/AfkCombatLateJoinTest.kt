package dev.menonng.snw.logic

import dev.menonng.snw.logic.afk.Activity
import dev.menonng.snw.logic.afk.AfkTracker
import dev.menonng.snw.logic.combat.CombatTag
import dev.menonng.snw.logic.combat.QuitReason
import dev.menonng.snw.logic.combat.QuitVerdict
import dev.menonng.snw.logic.join.KitTier
import dev.menonng.snw.logic.join.LateJoin
import dev.menonng.snw.logic.join.NationLoad
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AfkCombatLateJoinTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val min = 60_000L

    @Test
    @DisplayName("잠수: 5분 입력 없음, 또는 시선·이동 없이 같은 동작만 15분. 잠수 시간은 활동으로 안 쌓임")
    fun case01() {
        val t = AfkTracker()
        t.onJoin(a, 0)
        assertFalse(t.isAfk(a, 4 * min))
        assertTrue(t.isAfk(a, 5 * min))
        t.onActivity(a, Activity.LOOK, 5 * min)
        assertFalse(t.isAfk(a, 6 * min))
        // 자동 클릭: 상호작용만 계속
        var now = 6 * min
        while (now < 21 * min) { t.onActivity(a, Activity.INTERACT, now); now += 30_000 }
        assertTrue(t.isAfk(a, 21 * min))
        // 활동 시간: 1시간 동안 움직인 사람 vs 1시간 잠수한 사람
        val u = AfkTracker()
        u.onJoin(a, 0); u.onJoin(b, 0)
        var s = 0L
        while (s < 60 * min) { u.onActivity(a, Activity.MOVE_INPUT, s); u.tick(a, s); u.tick(b, s); s += 1000 }
        assertTrue(u.isActiveMember(a, s))
        assertFalse(u.isActiveMember(b, s))
        assertEquals(5 * min, u.activeMillis(b, s)) // 처음 5분만 쌓이고 그 뒤로는 잠수
        // 72시간이 지나면 빠진다
        assertFalse(u.isActiveMember(a, s + 73 * 60 * min))
    }

    @Test
    @DisplayName("전투 중 로그아웃: 15초 안에 나가면 마지막으로 때린 사람의 PvP 킬, 서버 종료·강퇴는 처벌 없음")
    fun case02() {
        val c = CombatTag()
        c.onPvpHit(b, a, 0)
        assertTrue(c.isTagged(a, 14_999)); assertTrue(c.isTagged(b, 1000))
        assertEquals(QuitVerdict.PvpDeath(b), c.onDisconnect(a, QuitReason.QUIT, 10_000))
        c.onPvpHit(b, a, 20_000)
        assertEquals(QuitVerdict.None, c.onDisconnect(a, QuitReason.SERVER_STOP, 21_000))
        c.onPvpHit(b, a, 30_000)
        assertEquals(QuitVerdict.None, c.onDisconnect(a, QuitReason.QUIT, 45_000)) // 15초 지남
        c.onPvpHit(b, a, 50_000)
        assertEquals(QuitVerdict.PvpDeath(b), c.onDisconnect(a, QuitReason.CONNECTION_LOST, 51_000))
        // 때리기만 한 쪽이 나가면 킬 줄 사람이 없다
        c.onPvpHit(a, b, 60_000)
        assertEquals(QuitVerdict.None, c.onDisconnect(a, QuitReason.QUIT, 61_000))
    }

    @Test
    @DisplayName("좀비에게 맞은 지 10초 안에 나가면 재접속 보호 10초 없음(한 번)")
    fun case03() {
        val c = CombatTag()
        c.onZombieHit(a, 0)
        c.onDisconnect(a, QuitReason.QUIT, 5_000)
        assertFalse(c.grantJoinProtection(a))
        assertTrue(c.grantJoinProtection(a))
        c.onZombieHit(b, 0)
        c.onDisconnect(b, QuitReason.QUIT, 11_000)
        assertTrue(c.grantJoinProtection(b))
    }

    @Test
    @DisplayName("늦은 합류: 활동 국민이 적은 국가로, 3일째부터 날짜 비례 보급, 복귀자는 없음, 첫 30분 좀비 떼 제외")
    fun case04() {
        val ns = listOf(NationLoad("1국", 5, 7), NationLoad("2국", 4, 8), NationLoad("3국", 4, 6))
        assertEquals("3국", LateJoin.assign(ns, Random(0)))
        assertEquals(KitTier.NONE, LateJoin.tier(2, true))
        assertEquals(KitTier.STONE, LateJoin.tier(3, true))
        assertEquals(KitTier.IRON, LateJoin.tier(8, true))
        assertEquals(KitTier.IRON_ARMOR, LateJoin.tier(15, true))
        assertEquals(KitTier.NONE, LateJoin.tier(15, false))
        assertTrue(LateJoin.kit(KitTier.IRON_ARMOR).any { it.first == "minecraft:iron_chestplate" })
        assertTrue(LateJoin.kit(KitTier.STONE).none { it.first.startsWith("minecraft:iron") })
        assertTrue(LateJoin.inHordeGrace(29 * min)); assertFalse(LateJoin.inHordeGrace(30 * min))
    }
}
