package dev.menonng.snw.logic.chat

/** 명령어 없이 친 채팅이 기본으로 어디로 가는가. */
enum class ChatChannel { TEAM, GLOBAL }

enum class ChatRoute {
    /** 그대로 전체 채팅으로 넘긴다(couriersRoost 가 있으면 전서구 전체 배달). */
    GLOBAL,
    /** 바닐라 /teammsg 전송부로 국가 팀원에게만 보낸다(couriersRoost 가 있으면 전서구 팀 배달). */
    TEAM,
}

/**
 * 채팅 경로 판단. docs/design/commands.md 1절 '채팅'.
 * 기본은 국가(바닐라 팀) 채팅이고, 전체 채팅은 `/g` 명령으로만 나간다.
 */
object ChatRouter {
    fun route(globalCommand: Boolean, hasTeam: Boolean, defaultChannel: ChatChannel = ChatChannel.TEAM): ChatRoute = when {
        globalCommand -> ChatRoute.GLOBAL
        defaultChannel == ChatChannel.GLOBAL -> ChatRoute.GLOBAL
        !hasTeam -> ChatRoute.GLOBAL          // 아직 국가가 없는 사람(팀 확정 전·운영자)은 전체로
        else -> ChatRoute.TEAM
    }
}

/** 국가 채팅을 쳤는데 받을 국민이 접속해 있지 않을 때의 안내. 같은 사람에게 너무 자주 띄우지 않는다. */
class AloneHint(private val cooldownMillis: Long = 5 * 60_000L) {
    private val lastShown = HashMap<Any, Long>()

    fun shouldShow(player: Any, othersOnline: Int, now: Long): Boolean {
        if (othersOnline > 0) return false
        val last = lastShown[player]
        if (last != null && now - last < cooldownMillis) return false
        lastShown[player] = now
        return true
    }

    fun forget(player: Any) { lastShown.remove(player) }
}
