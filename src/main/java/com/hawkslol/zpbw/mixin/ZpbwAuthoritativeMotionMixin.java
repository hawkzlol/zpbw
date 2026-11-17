package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Optional;
import java.util.function.Consumer;

/** Runs at the original native motion application, after the packet's main-thread guard. */
@Mixin(ClientPacketListener.class)
public abstract class ZpbwAuthoritativeMotionMixin {
    @WrapOperation(method = "handleSetEntityMotion", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"))
    private void zpbw$nativeVelocity(Entity entity, Vec3 movement, Operation<Void> original) {
        Object token = ZpbwRuntime.beforeAuthoritativeMotion(entity);
        try { original.call(entity, movement); }
        finally { ZpbwRuntime.afterAuthoritativeMotion(entity, token); }
    }

    @WrapOperation(method = "handleExplosion", at = @At(value = "INVOKE",
        target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"))
    private void zpbw$nativeExplosion(Optional<Vec3> force, Consumer<Vec3> action, Operation<Void> original) {
        LocalPlayer player = Minecraft.getInstance().player;
        Object token = force.isPresent() ? ZpbwRuntime.beforeAuthoritativeMotion(player) : null;
        try { original.call(force, action); }
        finally { ZpbwRuntime.afterAuthoritativeMotion(player, token); }
    }
}
