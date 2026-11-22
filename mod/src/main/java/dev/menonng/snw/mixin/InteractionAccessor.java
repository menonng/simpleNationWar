package dev.menonng.snw.mixin;

import net.minecraft.world.entity.Interaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 국가 코어 판정용 상호작용 엔티티의 크기·반응 설정. */
@Mixin(Interaction.class)
public interface InteractionAccessor {
    @Invoker("setWidth") void snw$setWidth(float width);
    @Invoker("setHeight") void snw$setHeight(float height);
    @Invoker("setResponse") void snw$setResponse(boolean response);
}
