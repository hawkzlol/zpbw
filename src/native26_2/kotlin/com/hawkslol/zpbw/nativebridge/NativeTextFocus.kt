package com.hawkslol.zpbw.nativebridge

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/** GLFW delivers committed characters without starting an explicit text-input session. */
internal object NativeTextFocus {
    @Suppress("UNUSED_PARAMETER")
    fun set(client: Minecraft, screen: Screen, active: Boolean) = Unit
}
