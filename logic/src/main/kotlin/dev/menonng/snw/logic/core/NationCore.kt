package dev.menonng.snw.logic.core

/**
 * 국가 코어(신호기) 체력 규칙. docs/design/nation.md 5-1 (2026-11-22).
 * - 체력 100,000, 공격 한 번에 최대 2. 적대 국가 플레이어와 몬스터만 깎는다.
 * - 빈집: 자국민이 아무도 접속해 있지 않으면 피해 절반(몬스터 포함).
 * - 회복: 마지막으로 맞은 뒤 2시간이 지나면 시간당 10. 수리 없음.
 * - 세는 공격(A안): 쿨다운 90% 이상 찬 무기 공격만, 같은 플레이어는 0.5초에 한 번. 맨손·다른 물건은 0.
 * - 무기 내구도: 칠 때마다 반드시 닳는다(센 공격 2, 덜 찬 공격 1, 내구성 인첸트 무시).
 */
object NationCore {
    const val MAX_HP = 100_000.0
    const val MAX_HIT = 2.0
    const val EMPTY_HOUSE_FACTOR = 0.5
    const val REGEN_DELAY_MILLIS = 2L * 60 * 60 * 1000
    const val REGEN_PER_HOUR = 10.0
    private const val HOUR_MILLIS = 60.0 * 60 * 1000

    enum class Attacker { OWN, ALLY, NEUTRAL, HOSTILE, MOB }

    /** 플레이어가 코어를 친 수단. */
    enum class Weapon(val counts: Boolean) {
        SWORD(true), AXE(true), MACE(true), TRIDENT(true), SPEAR(true),
        ARROW(true), THROWN_TRIDENT(true),
        HAND(false), OTHER(false),
    }

    const val MIN_CHARGE = 0.9f
    const val PER_PLAYER_INTERVAL_TICKS = 10
    const val DURABILITY_COUNTED = 2
    const val DURABILITY_WEAK = 1

    data class PlayerHit(val raw: Double, val durabilityCost: Int)

    /**
     * 플레이어 공격 한 번 판정. [charge] = 공격 쿨다운 비율(근접) 또는 활 당김 비율(원거리, 다 당긴 화살 = 1).
     * [ticksSinceLastCounted] = 그 플레이어의 직전 '센 공격'에서 지난 틱. 반환: 코어에 넘길 원래 피해(상한 2 전)와 무기 내구도 소모.
     * 실제 피해는 이 raw 를 [damage] 에 넘겨 적대·빈집·휴전을 적용한다. 원거리 무기는 발사한 무기가 아니라 화살이라 내구도 0.
     */
    fun playerHit(weapon: Weapon, raw: Double, charge: Float, ticksSinceLastCounted: Int): PlayerHit {
        val melee = weapon != Weapon.ARROW && weapon != Weapon.THROWN_TRIDENT
        val counted = weapon.counts && charge >= MIN_CHARGE && ticksSinceLastCounted >= PER_PLAYER_INTERVAL_TICKS
        val cost = when {
            !melee || !weapon.counts -> 0
            counted -> DURABILITY_COUNTED
            else -> DURABILITY_WEAK
        }
        return PlayerHit(if (counted) raw else 0.0, cost)
    }

    /** 무기 한 자루로 깎을 수 있는 코어 체력(빈집이 아니고 매번 센 공격일 때). 철 검 250, 네더라이트 2,031. */
    fun hpPerWeapon(durability: Int): Double = (durability / DURABILITY_COUNTED) * MAX_HIT

    /** 한 번의 공격이 코어에 주는 피해. [truce] = 블러드문 휴전(플레이어의 코어 공격 금지, 몬스터는 그대로). */
    fun damage(raw: Double, attacker: Attacker, ownersOnline: Int, truce: Boolean = false): Double {
        val allowed = when (attacker) {
            Attacker.MOB -> true
            Attacker.HOSTILE -> !truce
            else -> false
        }
        if (!allowed || raw <= 0.0) return 0.0
        val hit = minOf(MAX_HIT, raw)
        return if (ownersOnline <= 0) hit * EMPTY_HOUSE_FACTOR else hit
    }

    /** [lastHit] 이후 [now] 까지의 자연 회복을 반영한 체력. [hpAtLastHit] = 마지막으로 맞은 직후 체력. */
    fun hpAt(hpAtLastHit: Double, lastHit: Long, now: Long): Double {
        val regenMillis = now - lastHit - REGEN_DELAY_MILLIS
        if (regenMillis <= 0) return hpAtLastHit
        return minOf(MAX_HP, hpAtLastHit + REGEN_PER_HOUR * regenMillis / HOUR_MILLIS)
    }

    /** 계속 칠 때 코어를 0 으로 만드는 데 걸리는 초. [hitsPerSecond] = 공격자 한 명의 초당 타수. */
    fun secondsToBreak(hp: Double, attackers: Int, hitsPerSecond: Double, ownersOnline: Int): Double {
        val perHit = damage(MAX_HIT, Attacker.HOSTILE, ownersOnline)
        val dps = attackers * hitsPerSecond * perHit
        return if (dps <= 0) Double.POSITIVE_INFINITY else hp / dps
    }
}
