package dev.menonng.snw.mixin;

import dev.menonng.snw.protect.Protection;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 영토 안에서는 엔더맨이 블록을 집어 가지 않는다(nation.md 6절). */
@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockMixin {
    @Shadow @Final private Enderman enderman;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void snw$noTakeInTerritory(CallbackInfoReturnable<Boolean> cir) {
        if (Protection.territoryProtectedFromGriefing(enderman.level(), enderman.blockPosition())) cir.setReturnValue(false);
    }
}
