package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.NoSneakDelay;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
@Mixin(LocalPlayer.class)
public abstract class ZpbwSneakSampleMixin {
    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void zpbw$deduplicate(ClientPacketListener listener,Packet<?> packet,Operation<Void> original) {
        if (!NoSneakDelay.suppressIdenticalNativeInput(packet)) original.call(listener,packet);
    }
}
