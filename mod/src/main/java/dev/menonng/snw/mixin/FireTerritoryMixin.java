package dev.menonng.snw.mixin;

import dev.menonng.snw.protect.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 영토 안에서는 불이 번지지 않는다(nation.md 6절 환경 피해). 불이 블록을 태우거나 옆으로 옮겨 붙는 단계만 막는다. */
@Mixin(FireBlock.class)
public abstract class FireTerritoryMixin {
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void snw$noBurnInTerritory(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        if (Protection.territoryProtectedFromGriefing(level, pos)) ci.cancel();
    }

    @Inject(method = "getIgniteOdds(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void snw$noSpreadInTerritory(LevelReader reader, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (reader instanceof Level level && Protection.territoryProtectedFromGriefing(level, pos)) cir.setReturnValue(0);
    }
}
