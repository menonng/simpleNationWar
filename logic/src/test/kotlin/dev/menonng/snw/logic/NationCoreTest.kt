package dev.menonng.snw.logic

import dev.menonng.snw.logic.core.NationCore
import dev.menonng.snw.logic.core.NationCore.Attacker
import dev.menonng.snw.logic.core.NationCore.Weapon
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

    @Test
    @DisplayName("A안: 쿨다운 90% 이상 무기 공격만, 0.5초에 한 번, 맨손 0, 내구도는 반드시 닳음")
    fun case04() {
        assertEquals(NationCore.PlayerHit(11.0, 2), NationCore.playerHit(Weapon.SWORD, 11.0, 1.0f, 20))
        assertEquals(NationCore.PlayerHit(0.0, 1), NationCore.playerHit(Weapon.SWORD, 11.0, 0.3f, 20))   // 막 클릭: 피해 0, 내구도 1
        assertEquals(NationCore.PlayerHit(0.0, 1), NationCore.playerHit(Weapon.AXE, 9.0, 1.0f, 5))       // 0.5초 안 두 번째
        assertEquals(NationCore.PlayerHit(0.0, 0), NationCore.playerHit(Weapon.HAND, 1.0, 1.0f, 20))
        assertEquals(NationCore.PlayerHit(0.0, 0), NationCore.playerHit(Weapon.OTHER, 1.0, 1.0f, 20))
        assertEquals(NationCore.PlayerHit(6.0, 0), NationCore.playerHit(Weapon.ARROW, 6.0, 1.0f, 10))
        assertEquals(NationCore.PlayerHit(0.0, 0), NationCore.playerHit(Weapon.ARROW, 3.0, 0.5f, 10))     // 덜 당긴 화살
        assertEquals(2.0, NationCore.damage(NationCore.playerHit(Weapon.SWORD, 11.0, 0.95f, 13).raw, Attacker.HOSTILE, 1))
        assertEquals(250.0, NationCore.hpPerWeapon(250)); assertEquals(2030.0, NationCore.hpPerWeapon(2031))
    }
}
