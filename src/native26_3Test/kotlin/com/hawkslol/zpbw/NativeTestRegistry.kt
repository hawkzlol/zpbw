package com.hawkslol.zpbw

import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.MappedRegistry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.tags.TagLoader
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/** Offline registry fixture binds the actual released native tag data, not fake item names. */
object NativeTestRegistry {
    private fun values(path: String): List<String> = javaClass.classLoader.getResourceAsStream(path).use {
        checkNotNull(it, { path })
        JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject.getAsJsonArray("values").map { value -> value.asString }
    }
    fun flattenableBlocks(): List<Block> = values("data/minecraft/tags/block/turns_into_dirt_path.json")
        .map { BuiltInRegistries.BLOCK.getValue(Identifier.parse(it))!! }
    fun initialize() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap()
        val blocks: List<Holder<Block>> = flattenableBlocks().map { it.builtInRegistryHolder() }
        BuiltInRegistries.BLOCK.prepareTagReload(TagLoader.LoadResult(Registries.BLOCK,
            mapOf(BlockTags.TURNS_INTO_DIRT_PATH to blocks))).apply()
        val shovels: List<Holder<Item>> = values("data/minecraft/tags/item/shovels.json")
            .map { BuiltInRegistries.ITEM.getValue(Identifier.parse(it))!!.builtInRegistryHolder() }
        BuiltInRegistries.ITEM.prepareTagReload(TagLoader.LoadResult(Registries.ITEM,
            mapOf(ItemTags.DOUSES_CAMPFIRES to shovels))).apply()
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
            .build(net.minecraft.data.registries.VanillaRegistries.createWorldLookup()).forEach { it.apply() }
    }
}
