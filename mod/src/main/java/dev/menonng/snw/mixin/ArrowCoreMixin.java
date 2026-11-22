package dev.menonng.snw.mixin;

import dev.menonng.snw.core.CoreSystem;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 화살·삼지창이 국가 코어(신호기)에 박히면 코어 공격으로 센다(nation.md 5-1, 다 당긴 화살만). */
@Mixin(AbstractArrow.class)
public abstract class ArrowCoreMixin {
    @Inject(method = "onHitBlock", at = @At("HEAD"))
    private void snw$hitCore(BlockHitResult hit, CallbackInfo ci) {
        CoreSystem.onProjectileHit((AbstractArrow) (Object) this, hit.getBlockPos());
    }
}
