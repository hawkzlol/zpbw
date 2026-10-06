package com.hawkslol.zpbw.mixin;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ClientInput.class)
public interface ZpbwInputAccessor {
    @Accessor("moveVector") void zpbw$setMoveVector(Vec2 value);
}
