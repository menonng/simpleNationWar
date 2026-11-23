package dev.menonng.snw.mixin;

import dev.menonng.snw.discord.DiscordLink;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/** 디스코드 연동 확인(docs/discord/README.md '접속 확인'): 바닐라 화이트리스트 자리에서 연동 안 된 계정을 코드와 함께 거부한다. */
@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void snw$discordGate(SocketAddress address, NameAndId who, CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() != null) return;
        Component deny = DiscordLink.loginDenied(who.id(), who.name(), ((PlayerList) (Object) this).isOp(who));
        if (deny != null) cir.setReturnValue(deny);
    }
}
