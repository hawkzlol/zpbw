package com.hawkslol.zpbw

import com.hawkslol.zpbw.geometry.EtherwarpPredictor
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import com.hawkslol.zpbw.nativebridge.NativeGameAccess
import net.minecraft.world.level.block.state.BlockState

/** Native shovel actions that consume a block click before Minecraft reaches ordinary item use. */
object ShovelBlockUsePolicy {
    fun routes(stack: ItemStack, sneaking: Boolean, state: BlockState, face: Direction, aboveAir: Boolean): Boolean =
        EtherwarpPredictor.qualifiesForBlockUse(stack, sneaking) &&
            NativeGameAccess.shovelConsumes(stack, state, face, aboveAir)

    /** The enclosing block click must not fall through and send a second item-use packet. */
    fun consumeRoutedUse(use: () -> InteractionResult): InteractionResult {
        val result = use()
        return if (result is InteractionResult.Success || result is InteractionResult.Fail) result else InteractionResult.CONSUME
    }
}
