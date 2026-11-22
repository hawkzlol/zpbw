package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import com.hawkslol.zpbw.NativeRotationResponseScope;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The separate native Rotate route sends Rot, not a position-teleport response. */
@Mixin(ClientPacketListener.class)
public abstract class ZpbwRotationMixin {
    @Shadow public abstract Connection getConnection();
    @Inject(method = "handleRotatePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void zpbw$rotationScope(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        ZpbwRuntime.beforeNativeRotation(getConnection(), packet);
    }
    @WrapOperation(method = "handleRotatePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 1)
    private void zpbw$originalRotationResponse(Connection connection, Packet<?> response, Operation<Void> original,
                                               ClientboundPlayerRotationPacket incoming) {
        NativeRotationResponseScope.Response scope = ZpbwRuntime.beforeNativeRotationResponse(connection, incoming, response);
        try { original.call(connection, response); }
        finally { if (scope != null) NativeRotationResponseScope.endResponse(scope); }
    }
    @WrapMethod(method = "handleRotatePlayer")
    private void zpbw$rotationFinally(ClientboundPlayerRotationPacket packet, Operation<Void> original) {
        try { original.call(packet); }
        finally { ZpbwRuntime.afterNativeRotation(getConnection(), packet); }
    }
}
