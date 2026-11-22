package dev.menonng.snw.logic

import dev.menonng.snw.logic.core.NationCore
import dev.menonng.snw.logic.core.NationCore.Attacker
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals

class NationCoreTest {
    private val hour = 60L * 60 * 1000

    @Test
    @DisplayName("코어 피해: 최대 2, 적대·몬스터만, 빈집 절반, 휴전 중 플레이어 불가")
    fun case01() {
        assertEquals(2.0, NationCore.damage(15.0, Attacker.HOSTILE, ownersOnline = 1))
        assertEquals(1.0, NationCore.damage(15.0, Attacker.HOSTILE, ownersOnline = 0))
        assertEquals(1.0, NationCore.damage(1.0, Attacker.MOB, ownersOnline = 3))
        assertEquals(0.0, NationCore.damage(9.0, Attacker.NEUTRAL, 1))
        assertEquals(0.0, NationCore.damage(9.0, Attacker.ALLY, 1))
        assertEquals(0.0, NationCore.damage(9.0, Attacker.OWN, 1))
        assertEquals(0.0, NationCore.damage(9.0, Attacker.HOSTILE, 1, truce = true))
        assertEquals(2.0, NationCore.damage(9.0, Attacker.MOB, 1, truce = true))
    }

    @Test
    @DisplayName("회복은 마지막으로 맞은 뒤 2시간부터 시간당 10, 최대 체력에서 멈춘다")
    fun case02() {
        assertEquals(90_000.0, NationCore.hpAt(90_000.0, 0, 2 * hour))
        assertEquals(90_010.0, NationCore.hpAt(90_000.0, 0, 3 * hour), 1e-9)
        assertEquals(90_220.0, NationCore.hpAt(90_000.0, 0, 24 * hour), 1e-9)
        assertEquals(100_000.0, NationCore.hpAt(99_999.0, 0, 30 * hour))
    }

    @Test
    @DisplayName("4명이 쉬지 않고 치면 약 2시간 10분, 빈집이면 두 배")
    fun case03() {
        val full = NationCore.secondsToBreak(NationCore.MAX_HP, 4, 1.6, ownersOnline = 1)
        assertEquals(7812.5, full, 1.0)
        assertEquals(full * 2, NationCore.secondsToBreak(NationCore.MAX_HP, 4, 1.6, ownersOnline = 0), 1.0)
    }
}
