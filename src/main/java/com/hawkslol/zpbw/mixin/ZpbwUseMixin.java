package com.hawkslol.zpbw.mixin;

import com.hawkslol.zpbw.ZpbwRuntime;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class ZpbwUseMixin {
    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$use(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> ci) {
        if (ZpbwRuntime.beforeUse(player, hand)) ci.setReturnValue(
            ZpbwRuntime.takeQueuedUse() ? InteractionResult.CONSUME : InteractionResult.FAIL);
    }
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$blockUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> ci) {
        InteractionResult routed = ZpbwRuntime.redirectShovelBlockUse(player, hand, hit);
        if (routed != null) { ci.setReturnValue(routed); return; }
        if (ZpbwRuntime.deferAction("block_use", () -> Minecraft.getInstance().gameMode.useItemOn(player, hand, hit))) {
            ci.setReturnValue(InteractionResult.CONSUME); return;
        }
    }
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$entityUse(Player player, Entity entity, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> ci) {
        if (ZpbwRuntime.deferAction("entity_use", () -> Minecraft.getInstance().gameMode.interact(player, entity, hit, hand))) {
            ci.setReturnValue(InteractionResult.CONSUME); return;
        }
    }
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$startDig(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> ci) {
        BlockPos fixed = pos.immutable();
        if (ZpbwRuntime.deferMining(fixed, false, () -> Minecraft.getInstance().gameMode.startDestroyBlock(fixed, face))) {
            ci.setReturnValue(true); return;
        }
    }
    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$continueDig(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> ci) {
        BlockPos fixed = pos.immutable();
        if (ZpbwRuntime.deferMining(fixed, true, () -> Minecraft.getInstance().gameMode.continueDestroyBlock(fixed, face))) {
            ci.setReturnValue(true); return;
        }
    }
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void zpbwCandidate$attack(Player player, Entity entity, CallbackInfo ci) {
        if (ZpbwRuntime.deferAction("attack", () -> Minecraft.getInstance().gameMode.attack(player, entity))) ci.cancel();
    }
}
