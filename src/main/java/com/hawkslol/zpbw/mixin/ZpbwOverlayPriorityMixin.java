package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.overlay.EtherwarpOverlay;
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
}
