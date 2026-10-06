package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.NoSneakDelay;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.ClientInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=KeyboardInput.class, priority=500)
public abstract class ZpbwLatchedInputMixin {
    @Inject(method="tick",at=@At("TAIL"),order=2000)
    private void zpbw$committedSample(CallbackInfo ci) { NoSneakDelay.applySample((ClientInput)(Object)this); }
}
