package dev.menonng.snw.mixin;

import dev.menonng.snw.project.Villagers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 영토 안 주민 번식 제한(nation.md 12절): 조건이 안 되면 하트가 떠도 아기가 생기지 않는다. */
@Mixin(Villager.class)
public abstract class VillagerBreedMixin {
    @Inject(method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;", at = @At("HEAD"), cancellable = true)
    private void snw$limit(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Villager> cir) {
        if (!Villagers.allowBreed((Villager) (Object) this)) cir.setReturnValue(null);
    }

    @Inject(method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;", at = @At("RETURN"))
    private void snw$count(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Villager> cir) {
        if (cir.getReturnValue() != null) Villagers.onBorn((Villager) (Object) this, cir.getReturnValue());
    }
}
