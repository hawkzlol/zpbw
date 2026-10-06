package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ZpbwSourceTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void zpbw$queuedPhysicsInput(CallbackInfo ci) {
        ZpbwRuntime.beginQueuedPhysicsTick((LocalPlayer)(Object)this);
    }
    @Inject(method = "tick", at = @At("RETURN"))
    private void zpbw$restoreVisibleLook(CallbackInfo ci) {
        ZpbwRuntime.finishQueuedPhysicsTick((LocalPlayer)(Object)this);
    }
    // Vanilla derives this tick's crouching physics from the previous input before sampling
    // the current keys. Restore only that history after obsolete movement was discarded.
    @ModifyExpressionValue(method = "aiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;isShiftKeyDown()Z", ordinal = 0))
    private boolean zpbw$previousPhysicsSneak(boolean original) {
        return ZpbwRuntime.previousSneakForPhysics((LocalPlayer)(Object)this, original);
    }
    @Inject(method = "aiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/ClientInput;tick()V"))
    private void zpbw$finishPreviousPhysicsInput(CallbackInfo ci) {
        // Also clear the one-shot history if flying, swimming or collision skipped the read.
        ZpbwRuntime.finishPreviousPhysicsInput();
    }
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void zpbw$sourceRotation(CallbackInfo ci) {
        ZpbwRuntime.sourceTick((LocalPlayer)(Object)this);
    }
    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void zpbw$restoreLocalCollision(CallbackInfo ci) {
        ZpbwRuntime.sourceTickFinished((LocalPlayer)(Object)this);
    }
}
