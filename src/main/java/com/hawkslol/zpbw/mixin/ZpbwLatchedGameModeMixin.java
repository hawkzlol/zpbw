package com.hawkslol.zpbw.mixin;
import com.hawkslol.zpbw.NoSneakDelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
/** Programmatic native interactions need the same secondary-action semantics as physical clicks. */
@Mixin(MultiPlayerGameMode.class)
public abstract class ZpbwLatchedGameModeMixin {
    @WrapMethod(method="interact")
    private InteractionResult zpbw$interact(Player actor,Entity entity,EntityHitResult hit,InteractionHand hand,Operation<InteractionResult> original) {
        LocalPlayer p=Minecraft.getInstance().player; Input previous=actor==p ? NoSneakDelay.beginClick() : null;
        try { return original.call(actor,entity,hit,hand); } finally { NoSneakDelay.endClick(p,previous); }
    }
    @WrapMethod(method="useItem")
    private InteractionResult zpbw$use(Player actor,InteractionHand hand,Operation<InteractionResult> original) {
        LocalPlayer p=Minecraft.getInstance().player; Input previous=actor==p ? NoSneakDelay.beginClick() : null;
        try { return original.call(actor,hand); } finally { NoSneakDelay.endClick(p,previous); }
    }
    @WrapMethod(method="useItemOn")
    private InteractionResult zpbw$block(LocalPlayer actor,InteractionHand hand,BlockHitResult hit,Operation<InteractionResult> original) {
        LocalPlayer p=Minecraft.getInstance().player; Input previous=actor==p ? NoSneakDelay.beginClick() : null;
        try { return original.call(actor,hand,hit); } finally { NoSneakDelay.endClick(p,previous); }
    }
}
