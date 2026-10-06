package com.hawkslol.zpbw

import com.hawkslol.zpbw.geometry.EtherwarpPredictor
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.state.BlockState

/** Native shovel actions that consume a block click before Minecraft reaches ordinary item use. */
object ShovelBlockUsePolicy {
    private val flattenable = setOf(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL,
        Blocks.COARSE_DIRT, Blocks.MYCELIUM, Blocks.ROOTED_DIRT)

    fun routes(stack: ItemStack, sneaking: Boolean, state: BlockState, face: Direction, aboveAir: Boolean): Boolean =
        stack.item is ShovelItem && EtherwarpPredictor.qualifiesForBlockUse(stack, sneaking) &&
            face != Direction.DOWN && ((state.block in flattenable && aboveAir) ||
            (state.block is CampfireBlock && state.getValue(CampfireBlock.LIT)))

    /** The enclosing block click must not fall through and send a second item-use packet. */
    fun consumeRoutedUse(use: () -> InteractionResult): InteractionResult {
        val result = use()
        return if (result is InteractionResult.Success || result is InteractionResult.Fail) result else InteractionResult.CONSUME
    }
}
