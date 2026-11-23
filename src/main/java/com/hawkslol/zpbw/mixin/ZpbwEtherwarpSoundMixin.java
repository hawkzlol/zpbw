package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.overlay.EtherwarpAudio;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ZpbwEtherwarpSoundMixin {
    @Inject(method = "handleSoundEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER), cancellable = true, require = 1, allow = 1)
    private void zpbw$localEtherwarpPlayback(ClientboundSoundPacket packet, CallbackInfo ci) {
        if (EtherwarpAudio.replace((ClientPacketListener)(Object)this, packet)) ci.cancel();
    }
}
