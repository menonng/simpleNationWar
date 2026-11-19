package dev.menonng.snw.logic

import dev.menonng.snw.logic.chat.AloneHint
import dev.menonng.snw.logic.chat.ChatChannel
import dev.menonng.snw.logic.chat.ChatRoute
import dev.menonng.snw.logic.chat.ChatRouter
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatRouterTest {
    @Test
    @DisplayName("명령어 없이 친 채팅은 국가 채팅")
    fun case01() {
        assertEquals(ChatRoute.TEAM, ChatRouter.route(globalCommand = false, hasTeam = true))
    }

    @Test
    @DisplayName("/g 로 친 것만 전체 채팅")
    fun case02() {
        assertEquals(ChatRoute.GLOBAL, ChatRouter.route(globalCommand = true, hasTeam = true))
        assertEquals(ChatRoute.GLOBAL, ChatRouter.route(globalCommand = true, hasTeam = false))
    }

    @Test
    @DisplayName("국가가 없거나 설정이 전체 기본이면 전체 채팅")
    fun case03() {
        assertEquals(ChatRoute.GLOBAL, ChatRouter.route(globalCommand = false, hasTeam = false))
        assertEquals(ChatRoute.GLOBAL, ChatRouter.route(false, true, ChatChannel.GLOBAL))
    }

    @Test
    @DisplayName("혼자일 때 안내는 5분에 한 번")
    fun case04() {
        val hint = AloneHint()
        assertFalse(hint.shouldShow("a", othersOnline = 2, now = 0))
        assertTrue(hint.shouldShow("a", othersOnline = 0, now = 0))
        assertFalse(hint.shouldShow("a", othersOnline = 0, now = 60_000))
        assertTrue(hint.shouldShow("b", othersOnline = 0, now = 60_000))
        assertTrue(hint.shouldShow("a", othersOnline = 0, now = 5 * 60_000))
    }
}
