package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.NoSneakDelay;
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
        LocalPlayer p=Minecraft.getInstance().player; Input previous=NoSneakDelay.beginClick();
        try { return original.call(); } finally { NoSneakDelay.endClick(p,previous); }
    }
    @WrapMethod(method="startUseItem")
    private void zpbw$useInput(Operation<Void> original) {
        LocalPlayer p=Minecraft.getInstance().player; Input previous=NoSneakDelay.beginClick();
        try { original.call(); } finally { NoSneakDelay.endClick(p,previous); }
    }
}
