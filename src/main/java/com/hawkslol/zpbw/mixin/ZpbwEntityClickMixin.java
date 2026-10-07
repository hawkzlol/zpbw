package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.ZpbwRuntime;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Own the entire user click before ordinary startAttack observers open a tick-scoped window. */
// Apply this HEAD injection after ordinary mixins so it precedes their already-inserted callbacks.
@Mixin(value=Minecraft.class, priority=500)
public abstract class ZpbwEntityClickMixin {
    @Inject(method="startAttack",at=@At("HEAD"),cancellable=true)
    private void attack(CallbackInfoReturnable<Boolean> ci) {
        if (ZpbwRuntime.deferEntityClick(true) || ZpbwRuntime.deferBlockAttack(false, true)) ci.setReturnValue(false);
    }
    @Inject(method="continueAttack",at=@At("HEAD"),cancellable=true)
    private void continueAttack(boolean attackHeld, CallbackInfo ci) {
        if (ZpbwRuntime.deferBlockAttack(true, attackHeld)) ci.cancel();
    }
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void use(CallbackInfo ci) {
        if (ZpbwRuntime.deferEntityClick(false) || ZpbwRuntime.deferUseClick()) ci.cancel();
    }
}
