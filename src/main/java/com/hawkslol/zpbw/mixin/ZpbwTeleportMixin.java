package com.hawkslol.zpbw.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.hawkslol.zpbw.ZpbwRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ZpbwTeleportMixin {
    @Shadow public abstract Connection getConnection();
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void zpbwCandidate$before(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (ZpbwRuntime.beforeGenuine(getConnection(), packet)) ci.cancel();
    }
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 2)
    private void zpbwCandidate$nativePair(Connection connection, Packet<?> response, Operation<Void> original, ClientboundPlayerPositionPacket genuine) {
        original.call(connection, response);
        ZpbwRuntime.nativeResponse(connection, response, genuine);
    }
    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void zpbwCandidate$after(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ZpbwRuntime.afterGenuine(getConnection(), packet);
    }
}
