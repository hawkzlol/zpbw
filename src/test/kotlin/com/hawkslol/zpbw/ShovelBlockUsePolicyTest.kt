package com.hawkslol.zpbw

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import kotlin.test.*

class ShovelBlockUsePolicyTest {
    companion object { init {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
            .build(net.minecraft.data.registries.VanillaRegistries.createLookup()).forEach { it.apply() }
    } }
    private fun item(id: String = "ASPECT_OF_THE_VOID", merged: Boolean = true): ItemStack =
        ItemStack(Items.DIAMOND_SHOVEL).also { stack ->
            val attributes = CompoundTag().also { it.putString("id", id); it.putInt("ethermerge", if (merged) 1 else 0); it.putInt("tuned_transmission", 4) }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().also { it.put("ExtraAttributes", attributes) }))
        }
    @Test fun flattenableCoverageMatchesActualVanilla26_2Table() {
        val field = ShovelItem::class.java.getDeclaredField("FLATTENABLES").also { it.isAccessible = true }
        val vanillaBlocks = (field.get(null) as Map<*, *>).keys.filterIsInstance<net.minecraft.world.level.block.Block>()
        assertEquals(6, vanillaBlocks.size)
        for (block in vanillaBlocks) {
            assertTrue(ShovelBlockUsePolicy.routes(item(), true, block.defaultBlockState(), Direction.UP, true))
            assertTrue(ShovelBlockUsePolicy.routes(item(), true, block.defaultBlockState(), Direction.NORTH, true))
            assertFalse(ShovelBlockUsePolicy.routes(item(), true, block.defaultBlockState(), Direction.DOWN, true))
            assertFalse(ShovelBlockUsePolicy.routes(item(), true, block.defaultBlockState(), Direction.UP, false))
        }
    }
    @Test fun requiresGenuineArmedEtherwarpItemAndDoesNotStealOtherInteractions() {
        val grass = Blocks.GRASS_BLOCK.defaultBlockState()
        assertFalse(ShovelBlockUsePolicy.routes(item(), false, grass, Direction.UP, true))
        assertFalse(ShovelBlockUsePolicy.routes(item(merged=false), true, grass, Direction.UP, true))
        assertFalse(ShovelBlockUsePolicy.routes(ItemStack(Items.DIAMOND_SHOVEL), true, grass, Direction.UP, true))
        assertFalse(ShovelBlockUsePolicy.routes(item("OTHER"), true, grass, Direction.UP, true))
        assertFalse(ShovelBlockUsePolicy.routes(item(), true, Blocks.CHEST.defaultBlockState(), Direction.UP, true))
        assertFalse(ShovelBlockUsePolicy.routes(item(), true, Blocks.STONE.defaultBlockState(), Direction.UP, true))
    }
    @Test fun litCampfireConsumesShovelUseButUnlitDoesNot() {
        assertTrue(ShovelBlockUsePolicy.routes(item(), true, Blocks.CAMPFIRE.defaultBlockState(), Direction.UP, false))
        assertFalse(ShovelBlockUsePolicy.routes(item(), true, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), Direction.UP, true))
    }
    @Test fun nativePassStillConsumesOuterClickWithoutDoubleDispatch() {
        for (native in listOf(InteractionResult.PASS, InteractionResult.SUCCESS, InteractionResult.SUCCESS_SERVER,
            InteractionResult.CONSUME, InteractionResult.FAIL, InteractionResult.TRY_WITH_EMPTY_HAND)) {
            var uses = 0
            val outer = ShovelBlockUsePolicy.consumeRoutedUse { uses++; native }
            // Minecraft only falls through from a non-consuming, non-failing block result.
            if (!outer.consumesAction() && outer !is InteractionResult.Fail) uses++
            assertEquals(1, uses)
            if (native !is InteractionResult.Success && native !is InteractionResult.Fail) {
                assertSame(InteractionResult.CONSUME, outer)
                assertEquals(InteractionResult.SwingSource.NONE, (outer as InteractionResult.Success).swingSource())
            } else assertSame(native, outer, "Keep the actual result and swing source")
        }
    }
}
