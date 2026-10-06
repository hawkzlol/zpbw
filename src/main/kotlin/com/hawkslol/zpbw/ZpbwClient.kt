package com.hawkslol.zpbw

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

class ZpbwClient : ClientModInitializer {
    override fun onInitializeClient() {
        ZpbwRuntime.initialize()
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> ZpbwRuntime.joined() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> ZpbwRuntime.reset() }
        ClientTickEvents.END_CLIENT_TICK.register { ZpbwRuntime.tick() }
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher, _ -> registerCommands(dispatcher) })
    }
    fun registerCommands(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(lit("zpbw")
            .executes { it.source.sendFeedback(ZpbwMessages.help { text -> Minecraft.getInstance().font.width(text) }); 1 }
            .then(lit("on").executes { it.source.sendFeedback(ZpbwMessages.selection(ZpbwRuntime.select(ZpbwRuntime.Mode.REPLAY))); 1 })
            .then(lit("off").executes { it.source.sendFeedback(ZpbwMessages.selection(ZpbwRuntime.select(ZpbwRuntime.Mode.OFF))); 1 })
            .then(sneakCommand("nosneakdelay")).then(sneakCommand("nsd"))
            .then(lit("timeout").then(RequiredArgumentBuilder.argument<FabricClientCommandSource, Int>("ticks", IntegerArgumentType.integer())
                .executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureTimeout(IntegerArgumentType.getInteger(it, "ticks")))); 1 }))
            .then(lit("logs").executes {
                val client = Minecraft.getInstance()
                val report = ZpbwRuntime.logReport()
                val copied = runCatching { client.keyboardHandler.clipboard = report; client.keyboardHandler.clipboard == report }.getOrDefault(false)
                it.source.sendFeedback(if (copied) ZpbwMessages.copiedLogs()
                    else ZpbwMessages.error("Could not copy ZPBW logs. Check clipboard access and try again."))
                if (copied) 1 else 0
            }))
    }
    private fun sneakCommand(name: String) = lit(name)
        .executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureNoSneakDelay())); 1 }
        .then(lit("on").executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureNoSneakDelay(true))); 1 })
        .then(lit("off").executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureNoSneakDelay(false))); 1 })
    private fun lit(name: String): LiteralArgumentBuilder<FabricClientCommandSource> = LiteralArgumentBuilder.literal(name)
    companion object {
        val version: String get() = FabricLoader.getInstance().getModContainer("zpbw").orElseThrow().metadata.version.friendlyString
    }
}
