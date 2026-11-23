package com.hawkslol.zpbw

import com.hawkslol.zpbw.gui.ClickGuiScreen
import com.hawkslol.zpbw.overlay.EtherwarpOverlay
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientPacketListener
import org.slf4j.LoggerFactory

class ZpbwClient : ClientModInitializer {
    private data class MenuRequest(val connection: ClientPacketListener?, val origin: Screen?)
    private var menuRequest: MenuRequest? = null

    override fun onInitializeClient() {
        ZpbwRuntime.initialize()
        EtherwarpOverlay.register()
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) EtherwarpLocations.onMessage(Minecraft.getInstance(), message.string)
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> ZpbwRuntime.joined() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            menuRequest = null
            EtherwarpOverlay.clear()
            ZpbwRuntime.reset()
        }
        ClientTickEvents.END_CLIENT_TICK.register { ZpbwRuntime.tick() }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val request = menuRequest
            menuRequest = null
            // Chat closes itself after submission. Open after that native close on both versions.
            val current = client.gui.screen()
            if (request != null && request.connection === client.connection &&
                (current === request.origin || (current == null && request.origin is ChatScreen))) {
                client.gui.setScreen(ClickGuiScreen())
            }
        }
        ClientCommandRegistrationCallback.EVENT.register(ClientCommandRegistrationCallback { dispatcher, _ -> registerCommands(dispatcher) })
    }
    fun registerCommands(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(lit("zpbw")
            .executes {
                val client = Minecraft.getInstance()
                menuRequest = MenuRequest(client.connection, client.gui.screen())
                1
            }
            .then(lit("help").executes { it.source.sendFeedback(ZpbwMessages.help { text -> Minecraft.getInstance().font.width(text) }); 1 })
            .then(lit("on").executes { it.source.sendFeedback(ZpbwMessages.selection(ZpbwRuntime.select(ZpbwRuntime.Mode.REPLAY))); 1 })
            .then(lit("off").executes { it.source.sendFeedback(ZpbwMessages.selection(ZpbwRuntime.select(ZpbwRuntime.Mode.OFF))); 1 })
            .then(sneakCommand("nosneakdelay")).then(sneakCommand("nsd"))
            .then(lit("chainmax")
                .executes { it.source.sendFeedback(ZpbwMessages.plain("Current chain maximum: ${ZpbwRuntime.chainMaximum()}.")); 1 }
                .then(RequiredArgumentBuilder.argument<FabricClientCommandSource, Int>("value", IntegerArgumentType.integer())
                    .executes {
                        if (ZpbwRuntime.configureChainMaximum(IntegerArgumentType.getInteger(it, "value"))) {
                            it.source.sendFeedback(ZpbwMessages.plain("Set chain maximum to ${ZpbwRuntime.chainMaximum()}.")); 1
                        } else {
                            it.source.sendFeedback(ZpbwMessages.error("Could not save chain maximum. Previous value retained.")); 0
                        }
                    }))
            .then(lit("timeout").then(RequiredArgumentBuilder.argument<FabricClientCommandSource, Int>("ticks", IntegerArgumentType.integer())
                .executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureTimeout(IntegerArgumentType.getInteger(it, "ticks")))); 1 }))
            .then(lit("logs").executes {
                val client = Minecraft.getInstance()
                val connection = client.connection
                val source = it.source
                copyLogs(client) { copied ->
                    if (client.connection === connection) {
                        source.sendFeedback(if (copied) ZpbwMessages.copiedLogs() else ZpbwMessages.clipboardFallback())
                    }
                }
                1
            }))
    }
    private fun sneakCommand(name: String) = lit(name)
        .executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureNoSneakDelay())); 1 }
        .then(lit("on").executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureNoSneakDelay(true))); 1 })
        .then(lit("off").executes { it.source.sendFeedback(ZpbwMessages.plain(ZpbwRuntime.configureNoSneakDelay(false))); 1 })
    private fun lit(name: String): LiteralArgumentBuilder<FabricClientCommandSource> = LiteralArgumentBuilder.literal(name)
    companion object {
        val version: String get() = FabricLoader.getInstance().getModContainer("zpbw").orElseThrow().metadata.version.friendlyString

        internal fun copyLogs(client: Minecraft, onFinished: (Boolean) -> Unit) {
            ClipboardReport.copyOrLog(ZpbwRuntime.logReport(),
                { client.keyboardHandler.clipboard = it },
                { client.keyboardHandler.clipboard },
                { LoggerFactory.getLogger("ZPBW").info("ZPBW console troubleshooting export:\n{}", it) },
                ClipboardReport::copyThroughDesktop).thenAccept { copied ->
                    client.execute { onFinished(copied) }
                }
        }
    }
}
