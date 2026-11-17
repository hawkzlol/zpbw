package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Failed-chain transport recovery only; ordinary movement and input sampling stay native. */
@Mixin(LocalPlayer.class)
public abstract class ZpbwRecoveryMotionMixin {
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void zpbw$beforeRecoveryPhysics(CallbackInfo ci) {
        ZpbwRuntime.beforeRecoveryPhysics((LocalPlayer)(Object)this);
    }
    @Inject(method = "aiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER))
    private void zpbw$sampledRecoveryInput(CallbackInfo ci) {
        ZpbwRuntime.recoveryInputSampled((LocalPlayer)(Object)this);
    }
    @Inject(method = "aiStep", at = @At("RETURN"))
    private void zpbw$afterRecoveryPhysics(CallbackInfo ci) {
        ZpbwRuntime.afterRecoveryPhysics((LocalPlayer)(Object)this);
    }
}
