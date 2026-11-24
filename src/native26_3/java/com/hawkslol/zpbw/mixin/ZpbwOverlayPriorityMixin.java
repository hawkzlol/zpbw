package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.overlay.EtherwarpOverlay;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class ZpbwOverlayPriorityMixin {
    @Inject(method = "submitFeatures", at = @At("HEAD"), require = 1, allow = 1)
    private void zpbw$beginOverlayFrame(CallbackInfo ci) {
        EtherwarpOverlay.resetPriorityFrame();
    }

    // 26.3 gates the final pass on vanilla gizmos, ignoring custom submissions.
    // Request it only after this frame actually submitted our unoccluded overlay.
    @ModifyReturnValue(method = "frameHasAlwaysOnTopGizmos()Z", at = @At("RETURN"), require = 1, allow = 1)
    private boolean zpbw$includeOverlayPass(boolean original) {
        return original || EtherwarpOverlay.requestsTopPriorityPass();
    }
}
