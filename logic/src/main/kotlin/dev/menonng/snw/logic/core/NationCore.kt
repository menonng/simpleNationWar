package dev.menonng.snw.logic.core

/**
 * 국가 코어(신호기) 체력 규칙. docs/design/nation.md 5-1 (2026-11-22).
 * - 체력 100,000, 공격 한 번에 최대 2. 적대 국가 플레이어와 몬스터만 깎는다.
 * - 빈집: 자국민이 아무도 접속해 있지 않으면 피해 절반(몬스터 포함).
 * - 회복: 마지막으로 맞은 뒤 2시간이 지나면 시간당 10. 수리 없음.
 */
object NationCore {
    const val MAX_HP = 100_000.0
    const val MAX_HIT = 2.0
    const val EMPTY_HOUSE_FACTOR = 0.5
    const val REGEN_DELAY_MILLIS = 2L * 60 * 60 * 1000
    const val REGEN_PER_HOUR = 10.0
    private const val HOUR_MILLIS = 60.0 * 60 * 1000

    enum class Attacker { OWN, ALLY, NEUTRAL, HOSTILE, MOB }

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
