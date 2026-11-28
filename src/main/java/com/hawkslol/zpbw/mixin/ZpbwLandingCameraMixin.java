package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.visual.SmoothLandings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class ZpbwLandingCameraMixin {
    @Unique private float zpbw$alignmentPartialTick;

    @Inject(method = "alignWithEntity(F)V", at = @At("HEAD"), require = 1, allow = 1)
    private void zpbw$recordAlignmentTime(float partialTick, CallbackInfo ci) {
        zpbw$alignmentPartialTick = partialTick;
    }

    // Both natives assign this single position read to CameraRenderState.pos.
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"), require = 1, allow = 1)
    private Vec3 zpbw$smoothRenderedLanding(Vec3 position) {
        return SmoothLandings.renderPosition((Camera)(Object)this, position, zpbw$alignmentPartialTick);
    }
}
