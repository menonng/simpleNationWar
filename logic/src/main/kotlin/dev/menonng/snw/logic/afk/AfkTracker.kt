package dev.menonng.snw.logic.afk

import java.util.UUID

/**
 * 잠수 판정. docs/design/nation.md '국가' 15절.
 *
 * - 5분 동안 아무 입력이 없으면 잠수.
 * - 입력 = 시선 돌리기(1° 이상), 이동 키 상태 변화, 채팅·명령, 블록 설치·파괴, 아이템 사용, 공격.
 *   물살·탈것에 떠밀려 움직이는 것은 입력이 아니다.
 * - 시선·이동 키 변화 없이 같은 동작만 15분 넘게 이어지면(자동 클릭) 역시 잠수.
 * - 잠수가 아닌 시간만 '활동 시간'으로 쌓는다(1시간 단위 칸, 최근 7일. 감염도는 7일, 이적 창은 72시간을 본다).
 *   감염도의 활동 국민 수, 블러드문 발동 인원, 좀비 떼 대상, 이적 창 판정, 수면 판정이 이 값을 쓴다.
 */
enum class Activity(val looksOrMoves: Boolean) {
    LOOK(true), MOVE_INPUT(true),
    CHAT(false), COMMAND(false), INTERACT(false), ATTACK(false), BLOCK(false),
}

class AfkTracker(
    private val afkAfterMillis: Long = 5 * 60_000L,
    private val repetitiveAfterMillis: Long = 15 * 60_000L,
    private val windowHours: Int = INFECTION_HOURS,
) {
    private class State(var lastAny: Long, var lastLookOrMove: Long) {
        val hourly = HashMap<Long, Long>() // 시간 칸 번호 → 활동 밀리초
    }

    private val states = HashMap<UUID, State>()

    fun onJoin(player: UUID, now: Long) {
        val s = states.getOrPut(player) { State(now, now) }
        s.lastAny = now; s.lastLookOrMove = now
    }

    fun onActivity(player: UUID, kind: Activity, now: Long) {
        val s = states.getOrPut(player) { State(now, now) }
        s.lastAny = now
        if (kind.looksOrMoves) s.lastLookOrMove = now
    }

    fun isAfk(player: UUID, now: Long): Boolean {
        val s = states[player] ?: return false
        return now - s.lastAny >= afkAfterMillis || now - s.lastLookOrMove >= repetitiveAfterMillis
    }

    /** 1초마다 접속 중인 사람에게 부른다. 잠수가 아니면 [stepMillis] 만큼 활동 시간에 더한다. */
    fun tick(player: UUID, now: Long, stepMillis: Long = 1000L) {
        val s = states[player] ?: return
        if (isAfk(player, now)) return
        val hour = Math.floorDiv(now, 3_600_000L)
        s.hourly.merge(hour, stepMillis, Long::plus)
        val oldest = hour - windowHours
        s.hourly.keys.removeIf { it <= oldest }
    }

    /** 최근 [hours] 시간 동안의 활동 시간(밀리초). */
    fun activeMillis(player: UUID, now: Long, hours: Int = windowHours): Long {
        val s = states[player] ?: return 0
        val oldest = Math.floorDiv(now, 3_600_000L) - minOf(hours, windowHours)
        return s.hourly.filterKeys { it > oldest }.values.sum()
    }

    /** '활동 국민': 최근 [hours] 시간 활동 1시간 이상. 감염도(N7, zomApo)는 7일(기본), 이적 창은 [TRANSFER_HOURS]. */
    fun isActiveMember(player: UUID, now: Long, hours: Int = INFECTION_HOURS): Boolean = activeMillis(player, now, hours) >= 3_600_000L

    fun onQuit(player: UUID) { /* 활동 기록은 남긴다(7일 창). 다음 접속 때 onJoin 으로 입력 시각만 새로 */ }

    companion object {
        /** 감염도 국가 열기의 활동 국민 창(2026-11-22: 72시간 → 최근 7일). */
        const val INFECTION_HOURS = 168
        /** 이적 창 판정의 활동 국민 창. */
        const val TRANSFER_HOURS = 72
    }
}
