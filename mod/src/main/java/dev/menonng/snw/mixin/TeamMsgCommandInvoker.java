package dev.menonng.snw.mixin;

import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.TeamMsgCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 바닐라 /teammsg 의 전송부를 그대로 부른다. 서명된 원래 메시지가 유지되고,
 * couriersRoost 가 있으면 이 메서드에 건 믹스인이 전서구 배달로 바꾼다.
 */
@Mixin(TeamMsgCommand.class)
public interface TeamMsgCommandInvoker {
    @Invoker("sendMessage")
    static void snwSendTeamMessage(CommandSourceStack source, Entity entity, PlayerTeam team, List<ServerPlayer> receivers, PlayerChatMessage message) {
        throw new AssertionError();
    }
}
