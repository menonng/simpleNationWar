package dev.menonng.snw.logic.combat

import java.util.UUID

/**
 * 전투 중 로그아웃. docs/design/nation.md '국가' 16절.
 *
 * - 다른 플레이어에게 맞거나 때리면 둘 다 15초 동안 '전투 중'. 맞을 때마다 다시 15초.
 * - 전투 중에 접속을 끊으면(직접 나가기·연결 끊김) 그 자리에서 쓰러진 것으로 처리한다:
 *   PvP 사망(바닐라 드랍, 이긴 쪽이 주울 수 있음), 킬은 마지막으로 때린 사람.
 * - 서버 종료·재시작, 운영자 강퇴는 처벌하지 않는다.
 * - 좀비에게 맞은 지 10초 안에 나가면 처벌은 없지만, 다시 들어올 때 zomApo 의 접속 보호(10초)를 주지 않는다
 *   (바닐라 로딩 무적은 그대로라 로딩하다 죽지는 않는다).
 */
enum class QuitReason { QUIT, CONNECTION_LOST, KICKED_BY_ADMIN, SERVER_STOP }

sealed interface QuitVerdict {
    data object None : QuitVerdict
    /** 그 자리에서 PvP 사망으로 처리. */
    data class PvpDeath(val killer: UUID) : QuitVerdict
}

class CombatTag(
    private val pvpWindowMillis: Long = 15_000L,
    private val zombieWindowMillis: Long = 10_000L,
) {
    private class Tag(var until: Long, var lastAttacker: UUID?)

    private val tags = HashMap<UUID, Tag>()
    private val lastZombieHit = HashMap<UUID, Long>()
    private val zombieEscapes = HashSet<UUID>()

    fun onPvpHit(attacker: UUID, victim: UUID, now: Long) {
        if (attacker == victim) return
        tags.getOrPut(victim) { Tag(0, null) }.apply { until = now + pvpWindowMillis; lastAttacker = attacker }
        tags.getOrPut(attacker) { Tag(0, null) }.apply { until = maxOf(until, now + pvpWindowMillis) }
    }

    fun onZombieHit(player: UUID, now: Long) { lastZombieHit[player] = now }

    fun isTagged(player: UUID, now: Long): Boolean = (tags[player]?.until ?: 0) > now

    /** 남은 전투 시간(밀리초). 액션바 표시용. */
    fun remaining(player: UUID, now: Long): Long = maxOf(0, (tags[player]?.until ?: 0) - now)

    fun onDeath(player: UUID) { tags.remove(player) }

    fun onDisconnect(player: UUID, reason: QuitReason, now: Long): QuitVerdict {
        val punishable = reason == QuitReason.QUIT || reason == QuitReason.CONNECTION_LOST
        val zombieHit = lastZombieHit[player]
        if (punishable && zombieHit != null && now - zombieHit < zombieWindowMillis) zombieEscapes += player
        val tag = tags.remove(player)
        if (!punishable || tag == null || tag.until <= now) return QuitVerdict.None
        // 내가 때리기만 했고 맞은 적이 없으면 킬을 줄 사람이 없다 → 처벌 없음
        return tag.lastAttacker?.let { QuitVerdict.PvpDeath(it) } ?: QuitVerdict.None
    }

    /** 다시 들어올 때 zomApo 접속 보호(10초)를 줄지. 한 번 묻고 나면 기록을 지운다. */
    fun grantJoinProtection(player: UUID): Boolean = !zombieEscapes.remove(player)
}
