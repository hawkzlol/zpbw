package com.hawkslol.zpbw.nativebridge

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/** SDL needs explicit focus for custom editors that do not use Minecraft's EditBox. */
internal object NativeTextFocus {
    fun set(client: Minecraft, screen: Screen, active: Boolean) {
        if (!active || client.gui.screen() === screen) client.onTextInputFocusChange(screen, active)
    }
}
