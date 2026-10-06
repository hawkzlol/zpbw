package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ZpbwInputDispatchMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void zpbw$queuedNativeInput(CallbackInfo ci) { ZpbwRuntime.dispatchWaitingInput(); }
    @Inject(method = "tick", at = @At("TAIL"))
    private void zpbw$endInputTick(CallbackInfo ci) { ZpbwRuntime.finishInputDispatchTick(); }
}
