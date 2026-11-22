package dev.menonng.snw.mixin;

import dev.menonng.snw.core.CoreSystem;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 폭발은 한 번 터질 때마다 코어에 2(nation.md 5-1). 코어 구조물 블록은 부서지지 않는다(CoreSystem 이 되돌린다). */
@Mixin(ServerExplosion.class)
public abstract class ExplosionCoreMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void snw$explodeNearCore(CallbackInfoReturnable<Integer> cir) {
        ServerExplosion self = (ServerExplosion) (Object) this;
        CoreSystem.onExplosion(self.level(), self.center(), self.radius(), self.getIndirectSourceEntity());
    }
}
