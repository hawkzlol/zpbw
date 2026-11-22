package com.hawkslol.zpbw.nativebridge

import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockTransformers
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RedstoneWireBlock

object NativeGameAccess {
    fun redstoneWire(block: Block) = block is RedstoneWireBlock
    fun shovelConsumes(stack: ItemStack, state: BlockState, face: Direction, aboveAir: Boolean): Boolean {
        val flatten = stack.get(DataComponents.BLOCK_TRANSFORMER)?.`is`(BlockTransformers.SHOVEL) == true &&
            face != Direction.DOWN && state.`is`(BlockTags.TURNS_INTO_DIRT_PATH) && aboveAir
        // Native26.3 moved dousing into the campfire block handler, including DOWN.
        val douse = stack.`is`(ItemTags.DOUSES_CAMPFIRES) && state.block is CampfireBlock && state.getValue(CampfireBlock.LIT)
        return flatten || douse
    }
}
