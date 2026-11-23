package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.visual.SmoothLandings;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
public abstract class ZpbwLandingCameraMixin {
    // Both natives assign this single position read to CameraRenderState.pos.
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"), require = 1, allow = 1)
    private Vec3 zpbw$smoothRenderedLanding(Vec3 position) {
        return SmoothLandings.renderPosition((Camera)(Object)this, position);
    }
}
