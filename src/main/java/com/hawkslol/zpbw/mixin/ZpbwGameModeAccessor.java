package com.hawkslol.zpbw.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface ZpbwGameModeAccessor {
    @Invoker("ensureHasSentCarriedItem") void zpbw$syncCarriedItem();
    @Accessor("carriedIndex") int zpbw$getCarriedIndex();
    @Accessor("carriedIndex") void zpbw$setCarriedIndex(int value);
}
