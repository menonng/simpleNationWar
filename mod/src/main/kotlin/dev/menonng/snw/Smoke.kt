package dev.menonng.snw

import dev.menonng.snw.chat.ChatRouting
import dev.menonng.snw.logic.chat.ChatRoute
import dev.menonng.snw.mixin.TeamMsgCommandInvoker
import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.commands.TeamMsgCommand
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.scores.PlayerTeam
import java.util.UUID

/**
 * `-Dsnw.smoke=true` 일 때만 도는 서버 기동 확인. CI 와 `./gradlew :mod:runServer` 가 쓴다.
 * 가짜 플레이어로 채팅 경로(기본 = 국가, /g = 전체)가 실제 이벤트 흐름에서 맞게 갈리는지도 본다.
 */
internal object Smoke {
    private var reachedAfterDefault = false

    fun run(server: MinecraftServer): List<String> {
        val failures = mutableListOf<String>()
        if (!ChatRouting.commandsRegistered(server)) failures += "/g·/전체 명령 미등록"
        if (!TeamMsgCommandInvoker::class.java.isAssignableFrom(TeamMsgCommand::class.java)) failures += "TeamMsgCommand 믹스인 미적용"

        // 기본 단계(couriersRoost 의 전체 채팅 처리) 뒤에 도는 탐침. 앞 단계에서 취소되면 불리지 않는다.
        val probe = Identifier.fromNamespaceAndPath(Snw.MOD_ID, "smoke_probe")
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(Event.DEFAULT_PHASE, probe)
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(probe, ServerMessageEvents.AllowChatMessage { _, _, _ -> reachedAfterDefault = true; true })
        val courier = FabricLoader.getInstance().isModLoaded("courierpost")

        val fake = SmokePlayer(server.overworld())
        val scoreboard = server.scoreboard
        val team = scoreboard.addPlayerTeam("snw_smoke")
        try {
            // 국가 없음 → 전체
            chat(server, fake, "smoke-no-team")
            if (ChatRouting.lastRoute != ChatRoute.GLOBAL) failures += "국가 없는 채팅이 전체로 가지 않음: ${ChatRouting.lastRoute}"

            scoreboard.addPlayerToTeam(fake.scoreboardName, team)
            // 국가 있음, 명령어 없이 → 국가 채팅, 뒤 단계(전체 처리)까지 가면 안 된다
            reachedAfterDefault = false
            chat(server, fake, "smoke-team")
            if (ChatRouting.lastRoute != ChatRoute.TEAM) failures += "기본 채팅이 국가 채팅으로 가지 않음: ${ChatRouting.lastRoute}"
            if (reachedAfterDefault) failures += "국가 채팅이 전체 채팅 단계까지 넘어감"

            // /g → 전체. couriersRoost 가 없으면 바닐라 방송까지, 있으면 전서구가 받아 취소한다
            reachedAfterDefault = false
            server.commands.performPrefixedCommand(fake.createCommandSourceStack(), "g smoke-global")
            if (ChatRouting.lastRoute != ChatRoute.GLOBAL) failures += "/g 가 전체 채팅으로 가지 않음: ${ChatRouting.lastRoute}"
            if (!courier && !reachedAfterDefault) failures += "/g 가 바닐라 전체 방송 단계에 닿지 않음"
            if (courier && reachedAfterDefault) failures += "/g 를 couriersRoost 가 받지 않음"
        } finally {
            scoreboard.removePlayerTeam(team)
        }
        Snw.log.info("[simpleNationWar] smoke: couriersRoost={}, 채팅 경로 확인 {}", courier, if (failures.isEmpty()) "통과" else "실패")
        return failures
    }

    /** Fabric 의 FakePlayer 는 getTeam() 이 늘 null 이라, 실제 플레이어처럼 팀을 돌려주게 한다. */
    private class SmokePlayer(level: ServerLevel) : FakePlayer(level, GameProfile(UUID.nameUUIDFromBytes("snw-smoke".toByteArray()), "snw_smoke")) {
        override fun getTeam(): PlayerTeam? = level().scoreboard.getPlayersTeam(scoreboardName)
    }

    private fun chat(server: MinecraftServer, player: FakePlayer, text: String) {
        server.playerList.broadcastChatMessage(PlayerChatMessage.system(text), player, ChatType.bind(ChatType.CHAT, player))
    }
}
