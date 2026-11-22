package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.NoSneakDelay;
import com.hawkslol.zpbw.NativeDispatchScopes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(Minecraft.class)
public abstract class ZpbwLatchedClickMixin {
    @WrapMethod(method="startAttack")
    private boolean zpbw$attackInput(Operation<Boolean> original) {
        LocalPlayer p=Minecraft.getInstance().player; Input previous=null;
        NativeDispatchScopes.Action scope=NativeDispatchScopes.beginAction();
        try { previous=NoSneakDelay.beginClick(); return original.call(); }
        finally { try { NoSneakDelay.endClick(p,previous); } finally { NativeDispatchScopes.endAction(scope); } }
    }
    @WrapMethod(method="startUseItem")
    private void zpbw$useInput(Operation<Void> original) {
        LocalPlayer p=Minecraft.getInstance().player; Input previous=null;
        NativeDispatchScopes.Action scope=NativeDispatchScopes.beginAction();
        try { previous=NoSneakDelay.beginClick(); original.call(); }
        finally { try { NoSneakDelay.endClick(p,previous); } finally { NativeDispatchScopes.endAction(scope); } }
    }
    /** Held mining is a separate native occurrence. This scope only labels diagnostics. */
    @WrapMethod(method="continueAttack")
    private void zpbw$continueScope(boolean held, Operation<Void> original) {
        NativeDispatchScopes.Action scope=NativeDispatchScopes.beginAction();
        try { original.call(held); }
        finally { NativeDispatchScopes.endAction(scope); }
    }
}
