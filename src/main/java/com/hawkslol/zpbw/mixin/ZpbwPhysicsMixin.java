package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe actual vanilla-applied self physics changes, never predict buffs or alter server packets. */
@Mixin(ClientPacketListener.class)
public abstract class ZpbwPhysicsMixin {
    @Inject(method = {"handleUpdateAttributes", "handleUpdateMobEffect", "handleRemoveMobEffect"}, at = @At("HEAD"))
    private void zpbw$beforePhysics(CallbackInfo ci) { ZpbwRuntime.beforePhysicsUpdate(); }
    @Inject(method = {"handleUpdateAttributes", "handleUpdateMobEffect", "handleRemoveMobEffect"}, at = @At("RETURN"))
    private void zpbw$afterPhysics(CallbackInfo ci) { ZpbwRuntime.afterPhysicsUpdate(); }
}
