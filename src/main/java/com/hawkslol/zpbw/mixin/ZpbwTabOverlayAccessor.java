package com.hawkslol.zpbw.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the server-provided tab text without depending on another mod's location state. */
@Mixin(PlayerTabOverlay.class)
public interface ZpbwTabOverlayAccessor {
    @Accessor("header") Component zpbw$getHeader();
    @Accessor("footer") Component zpbw$getFooter();
}
