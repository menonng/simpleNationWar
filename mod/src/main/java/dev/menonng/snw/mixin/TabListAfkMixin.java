package dev.menonng.snw.mixin;

import dev.menonng.snw.protect.Protection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 잠수 중인 사람은 탭 목록 이름 뒤에 회색 [잠수](nation.md 15절). */
@Mixin(ServerPlayer.class)
public abstract class TabListAfkMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void snw$afkSuffix(CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(Protection.tabName((ServerPlayer) (Object) this, cir.getReturnValue()));
    }
}
