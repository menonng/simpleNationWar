package dev.menonng.snw.mixin;

import dev.menonng.snw.nickname.Nicknames;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 표시 이름(채팅 발신자·사망 메시지)을 커스텀 닉네임으로(nickname.md 2절). 채팅 서명은 내용에만 걸려 깨지지 않는다. */
@Mixin(Player.class)
public abstract class DisplayNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void snw$nickname(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(Nicknames.displayName((Player) (Object) this, cir.getReturnValue()));
    }
}
