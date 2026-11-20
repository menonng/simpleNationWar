package dev.menonng.snw.logic.kda

import java.util.UUID

/** 피해 기록 한 건. 시간은 현실 시간(밀리초). */
data class DamageEntry(val attacker: UUID, val amount: Double, val timeMillis: Long)

data class DeathInput(
    val victim: UUID,
    /** 바닐라 처치 크레딧을 가진 플레이어. 좀비·환경 사망이면 null. */
    val killer: UUID?,
    val victimMaxHealth: Double,
    val damage: List<DamageEntry>,
    val timeMillis: Long,
)

data class DeathOutcome(
    val killCounted: Boolean,
    val teamKill: Boolean,
    val pvpDeath: Boolean,
    val assists: List<UUID>,
)

/** 두 플레이어가 같은 국가인가, 동맹인가. 국가 모드가 채워 준다. */
interface Allegiance {
    fun sameNation(a: UUID, b: UUID): Boolean
    fun allied(a: UUID, b: UUID): Boolean
}

/**
 * K/D/A 판정. docs/design/nation.md 'KDA' 1절.
 * D 는 모든 사망을 세고(호출하는 쪽에서 +1), 여기서는 K·A·PvP 사망 여부만 정한다.
 */
class KdaJudge(
    private val allegiance: Allegiance,
    private val assistWindowMillis: Long = 15_000,
    private val minAssistDamage: Double = 2.0,
    private val minAssistShare: Double = 0.10,
    private val repeatWindowMillis: Long = 10 * 60_000,
    private val repeatLimit: Int = 2,
) {
    private val recentKills = HashMap<Pair<UUID, UUID>, ArrayDeque<Long>>()

    fun judge(input: DeathInput): DeathOutcome {
        val killer = input.killer
        if (killer == null || killer == input.victim) return DeathOutcome(false, false, false, emptyList())
        if (allegiance.sameNation(killer, input.victim)) return DeathOutcome(false, teamKill = true, pvpDeath = true, assists = emptyList())

        val counted = registerKill(killer, input.victim, input.timeMillis)
        return DeathOutcome(counted, false, true, assists(input, killer))
    }

    private fun registerKill(killer: UUID, victim: UUID, now: Long): Boolean {
        val q = recentKills.getOrPut(killer to victim) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= repeatWindowMillis) q.removeFirst()
        q.addLast(now)
        return q.size <= repeatLimit
    }

    private fun assists(input: DeathInput, killer: UUID): List<UUID> {
        val threshold = minOf(minAssistDamage, input.victimMaxHealth * minAssistShare).let { maxOf(it, 0.0) }
        val byAttacker = input.damage
            .filter { input.timeMillis - it.timeMillis in 0..assistWindowMillis }
            .groupBy { it.attacker }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
        return byAttacker
            .filter { (attacker, total) ->
                attacker != killer && attacker != input.victim && total >= threshold &&
                    !allegiance.sameNation(attacker, input.victim) && !allegiance.allied(attacker, input.victim)
            }
            .keys.sortedBy { it.toString() }
    }
}

/** 표시용 계산. KDA = (K + A) / max(D, 1). */
data class KdaStats(val kills: Int, val deaths: Int, val assists: Int, val deathsPvp: Int, val teamKills: Int = 0) {
    val kda: Double get() = (kills + assists).toDouble() / maxOf(deaths, 1)
    val kdaPvp: Double get() = (kills + assists).toDouble() / maxOf(deathsPvp, 1)

    fun apply(outcome: DeathOutcome, role: Role): KdaStats = when (role) {
        Role.VICTIM -> copy(deaths = deaths + 1, deathsPvp = deathsPvp + if (outcome.pvpDeath) 1 else 0)
        Role.KILLER -> copy(kills = kills + if (outcome.killCounted) 1 else 0, teamKills = teamKills + if (outcome.teamKill) 1 else 0)
        Role.ASSIST -> copy(assists = assists + 1)
    }

    enum class Role { VICTIM, KILLER, ASSIST }

    companion object {
        fun format(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)
    }
}
