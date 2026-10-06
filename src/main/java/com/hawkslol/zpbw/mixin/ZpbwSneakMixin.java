package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.NoSneakDelay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class ZpbwSneakMixin {
    // runTick follows normal event polling, including frames with zero simulation ticks.
    @Inject(method="runTick", at=@At("HEAD"))
    private void zpbw$earlySneak(boolean render, CallbackInfo ci) { NoSneakDelay.frame(); }
    @Inject(method="tick",at=@At("TAIL"))
    private void zpbw$finishInputTick(CallbackInfo ci) { NoSneakDelay.finishTick(); }
}
