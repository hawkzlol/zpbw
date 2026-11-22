package com.hawkslol.zpbw.nativebridge

import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RedStoneWireBlock

object NativeGameAccess {
    fun redstoneWire(block: Block) = block is RedStoneWireBlock
    private val flattenable = setOf(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL,
        Blocks.COARSE_DIRT, Blocks.MYCELIUM, Blocks.ROOTED_DIRT)
    fun shovelConsumes(stack: ItemStack, state: BlockState, face: Direction, aboveAir: Boolean): Boolean =
        stack.item is ShovelItem && face != Direction.DOWN &&
            ((state.block in flattenable && aboveAir) || (state.block is CampfireBlock && state.getValue(CampfireBlock.LIT)))
}
