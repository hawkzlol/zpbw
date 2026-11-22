package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ZpbwSourceTickMixin {
    @WrapMethod(method = "tick")
    private void zpbw$queuedPhysicsInput(Operation<Void> original) {
        ZpbwRuntime.beginQueuedPhysicsTick((LocalPlayer)(Object)this);
        try { original.call(); }
        finally { ZpbwRuntime.finishQueuedPhysicsTick((LocalPlayer)(Object)this); }
    }
    // Released26.3 sends after entity ticking. Reapply the same queued look through
    // that actual native phase; visual look is restored even on exceptional return.
    @WrapMethod(method = "sendChanges")
    private void zpbw$queuedSendInput(Operation<Void> original) {
        ZpbwRuntime.beginQueuedPhysicsTick((LocalPlayer)(Object)this);
        try { original.call(); }
        finally { ZpbwRuntime.finishQueuedPhysicsTick((LocalPlayer)(Object)this); }
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
    @WrapMethod(method = "sendPosition")
    private void zpbw$sourceRotation(Operation<Void> original) {
        ZpbwRuntime.sourceTick((LocalPlayer)(Object)this);
        try { original.call(); }
        finally { ZpbwRuntime.sourceTickFinished((LocalPlayer)(Object)this); }
    }
}
