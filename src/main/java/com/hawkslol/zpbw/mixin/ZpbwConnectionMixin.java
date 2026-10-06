package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ZpbwConnectionMixin {
    // Runs once on receipt, before vanilla schedules packet handling on the client thread.
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void zpbwCandidate$received(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ZpbwRuntime.received((Connection)(Object)this, packet);
    }
    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void zpbwCandidate$closed(ChannelHandlerContext context, CallbackInfo ci) {
        ZpbwRuntime.channelClosed((Connection)(Object)this);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$retain(Packet<?> packet, CallbackInfo ci) {
        if (ZpbwRuntime.capture((Connection)(Object)this, packet)) ci.cancel();
    }
    @WrapOperation(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V"))
    private void zpbwCandidate$sourceCut(Connection connection, Packet<?> packet, ChannelFutureListener listener, Operation<Void> original) {
        original.call(connection, packet, listener);
        ZpbwRuntime.afterSend(connection, packet); // Cancelled HEAD never reaches this delegate.
    }
    @Inject(method = "doSendPacket", at = @At("TAIL"))
    private void zpbwCandidate$admitted(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        ZpbwRuntime.admitted((Connection)(Object)this, packet);
    }
}
