package com.hawkslol.zpbw

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.level.block.Block

object NativeTestRegistry {
    fun initialize() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap()
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
            .build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach { it.apply() }
    }
    fun flattenableBlocks(): List<Block> {
        val field = ShovelItem::class.java.getDeclaredField("FLATTENABLES").also { it.isAccessible = true }
        return (field.get(null) as Map<*, *>).keys.filterIsInstance<Block>()
    }
}
