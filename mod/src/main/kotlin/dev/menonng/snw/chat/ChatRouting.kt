package dev.menonng.snw.chat

import com.mojang.brigadier.context.CommandContext
import dev.menonng.snw.Snw
import dev.menonng.snw.SnwConfig
import dev.menonng.snw.logic.chat.AloneHint
import dev.menonng.snw.logic.chat.ChatChannel
import dev.menonng.snw.logic.chat.ChatRoute
import dev.menonng.snw.logic.chat.ChatRouter
import dev.menonng.snw.mixin.TeamMsgCommandInvoker
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.MessageArgument
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * 채팅 경로: 명령어 없이 친 채팅은 국가(바닐라 팀) 채팅, 전체 채팅은 `/g <메시지>`(별칭 `/전체`)로만.
 *
 * couriersRoost 는 ALLOW_CHAT_MESSAGE 기본 단계에서 모든 채팅을 전체 전서구로 보내고 취소한다.
 * 그래서 그보다 앞선 단계에서 먼저 받아, 국가 채팅이면 바닐라 /teammsg 전송부를 직접 부른다.
 * couriersRoost 는 그 전송부에 믹스인을 걸어 두었으므로 국가 채팅도 전서구로 배달되고,
 * couriersRoost 가 없으면 바닐라 팀 채팅 그대로 나간다.
 */
object ChatRouting {
    const val GLOBAL_COMMAND = "g"
    const val GLOBAL_COMMAND_KO = "전체"
    private val PHASE: Identifier = Identifier.fromNamespaceAndPath(Snw.MOD_ID, "chat_routing")

    /** /g 처리 중에만 켠다. 채팅과 명령은 모두 서버 스레드에서 돈다. */
    private var globalCommand = false
    private val aloneHint = AloneHint()

    /** 마지막으로 고른 경로. 스모크 테스트가 읽는다. */
    internal var lastRoute: ChatRoute? = null
        private set

    fun register() {
        val event = ServerMessageEvents.ALLOW_CHAT_MESSAGE
        event.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        event.register(PHASE, ServerMessageEvents.AllowChatMessage { message, sender, _ -> onChat(message, sender) })

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            val node = dispatcher.register(
                Commands.literal(GLOBAL_COMMAND).then(Commands.argument("message", MessageArgument.message()).executes(::global)),
            )
            dispatcher.register(Commands.literal(GLOBAL_COMMAND_KO).redirect(node))
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            if (SnwConfig.chatDefault == ChatChannel.TEAM && player.team != null) {
                player.sendSystemMessage(hint("채팅은 국가 채팅으로 나갑니다. 전체 채팅은 /g <메시지>를 쓰십시오."))
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> aloneHint.forget(handler.player.uuid) }
    }

    /** 명령 트리에 /g·/전체 가 올라갔는지(스모크 테스트용). */
    fun commandsRegistered(server: MinecraftServer): Boolean {
        val root = server.commands.dispatcher.root
        return root.getChild(GLOBAL_COMMAND) != null && root.getChild(GLOBAL_COMMAND_KO) != null
    }

    private fun onChat(message: PlayerChatMessage, sender: ServerPlayer): Boolean {
        val team = sender.team
        val route = ChatRouter.route(globalCommand, team != null, SnwConfig.chatDefault)
        lastRoute = route
        if (route == ChatRoute.GLOBAL || team == null) return true

        // 바닐라 /teammsg 와 같은 수신자: 보낸 사람 + 같은 팀 접속자
        val receivers = sender.level().server.playerList.players.filter { it === sender || it.team === team }
        if (aloneHint.shouldShow(sender.uuid, receivers.size - 1, System.currentTimeMillis())) {
            sender.sendSystemMessage(hint("지금 접속한 국민이 없습니다. 전체 채팅은 /g <메시지>를 쓰십시오."))
        }
        TeamMsgCommandInvoker.snwSendTeamMessage(sender.createCommandSourceStack(), sender, team, receivers, message)
        return false
    }

    private fun global(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        MessageArgument.resolveChatMessage(ctx, "message") { message ->
            globalCommand = true
            try {
                // 일반 채팅과 같은 경로로 방송한다. couriersRoost 가 있으면 전서구 전체 배달(재사용 대기시간 포함).
                ctx.source.server.playerList.broadcastChatMessage(message, player, ChatType.bind(ChatType.CHAT, player))
            } finally {
                globalCommand = false
            }
        }
        return 1
    }

    private fun hint(text: String): Component = Component.literal(text).withStyle(ChatFormatting.GRAY)
}
