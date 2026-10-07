package com.hawkslol.zpbw.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Rebase vanilla's delta sender after dropping speculative movement during recovery. */
@Mixin(LocalPlayer.class)
public interface ZpbwPlayerAccessor {
    @Accessor("autoJumpTime") int zpbw$getAutoJumpTime();
    @Accessor("xLast") void zpbw$setXLast(double value);
    @Accessor("yLast") void zpbw$setYLast(double value);
    @Accessor("zLast") void zpbw$setZLast(double value);
    @Accessor("yRotLast") void zpbw$setYRotLast(float value);
    @Accessor("xRotLast") void zpbw$setXRotLast(float value);
    @Accessor("lastOnGround") void zpbw$setLastOnGround(boolean value);
    @Accessor("lastHorizontalCollision") void zpbw$setLastHorizontalCollision(boolean value);
    @Accessor("positionReminder") void zpbw$setPositionReminder(int value);
    @Accessor("lastSentInput") void zpbw$setLastSentInput(net.minecraft.world.entity.player.Input value);
    @Accessor("wasSprinting") void zpbw$setWasSprinting(boolean value);
}
