package com.hawkslol.zpbw.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Minecraft.class)
public interface ZpbwMinecraftAccessor {
    @Invoker("startAttack") boolean zpbw$startAttack();
    @Invoker("continueAttack") void zpbw$continueAttack(boolean attackHeld);
    @Invoker("startUseItem") void zpbw$startUseItem();
    @Invoker("pick") void zpbw$pick(float partialTick);
    @Accessor("hitResult") HitResult zpbw$getHitResult();
    @Accessor("hitResult") void zpbw$setHitResult(HitResult hit);
    @Accessor("missTime") int zpbw$getMissTime();
    @Accessor("rightClickDelay") int zpbw$getRightClickDelay();
    @Accessor("rightClickDelay") void zpbw$setRightClickDelay(int delay);
}
