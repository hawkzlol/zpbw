package com.hawkslol.zpbw

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale

/** Read only the current session's visible area and the loaded center column of the current room. */
internal object EtherwarpLocations {
    private val floor = Regex("\\b(?:catacombs|dungeon)\\s*\\(([EFM][1-7]?|E)\\)", RegexOption.IGNORE_CASE)
    private val locations = setOf("mineshaft", "glacite mineshaft", "glacite mineshafts", "jungle temple", "dojo")
    private val nextBosses = setOf("storm", "goldor", "necron", "wither king")
    private val cores = setOf(-1358669872, 1628378960, -1989128497, 997920335, 1128554492,
        -625336388, 2089453469, 632136981, 307825200, -267112000, -591423781)
    private val legacy by lazy {
        checkNotNull(javaClass.getResourceAsStream("/assets/zpbw/location/dungeon-block-ids.tsv"))
            .bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.associate {
                    val (key, id) = it.split('\t')
                    key to id.toInt()
                }
            }
    }
    private var level: ClientLevel? = null
    private var connection: ClientPacketListener? = null
    private var player: LocalPlayer? = null
    private var sampledAt = Int.MIN_VALUE
    private var area = Area()
    private var maxor = false
    private var column: BlockPos? = null
    private var columnTick = Int.MIN_VALUE
    private var blockedRoom = false

    internal data class Area(val blocked: Boolean = false, val dungeon: Boolean = false, val floorSeven: Boolean = false)

    private fun label(value: String) = SkyblockSignals.clean(value).lowercase(Locale.ROOT)

    internal fun classify(signals: SkyblockDetector.Signals): Area {
        if (!signals.skyblock) return Area()
        val sidebar = signals.sidebar.flatMap { it.lines() }.map(::label)
        val sidebarAreas = sidebar.mapNotNull {
            it.takeIf { line -> line.startsWith("⏣") || line.startsWith("ф") }
                ?.drop(1)?.trim()
        }
        val labels = (signals.tab.flatMap { it.lines() } + signals.sidebar).map(::label)
        val tabAreas = labels.filter { it.startsWith("area:") || it.startsWith("map:") }
            .map { it.substringAfter(':').trim() }
        val names = sidebarAreas + tabAreas
        val floors = sidebar.mapNotNull { floor.find(it)?.groupValues?.get(1) }
        val dungeon = floors.isNotEmpty() || names.any { it == "dungeon" || it == "the catacombs" }
        val ownIsland = names.any { it == "your island" }
        val otherIsland = sidebarAreas.any { it.endsWith("'s island") || it.endsWith("’s island") } ||
            names.any { it == "guest island" } ||
            (names.any { it == "private island" } && sidebar.any { it == "guest" || it.endsWith(" guest") })
        return Area(names.any { it in locations } || (otherIsland && !ownIsland), dungeon,
            floors.any { it.equals("F7", true) || it.equals("M7", true) })
    }

    internal fun roomCenter(x: Int, z: Int) = BlockPos(-185 + Math.floorDiv(x + 200, 32) * 32, 0,
        -185 + Math.floorDiv(z + 200, 32) * 32)

    internal fun restrictedCore(hash: Int) = hash in cores

    fun reset() {
        level = null
        connection = null
        player = null
        sampledAt = Int.MIN_VALUE
        columnTick = Int.MIN_VALUE
        column = null
        area = Area()
        maxor = false
        blockedRoom = false
    }

    private fun bind(client: Minecraft): LocalPlayer? {
        if (level !== client.level || connection !== client.connection || player !== client.player) {
            reset()
            level = client.level
            connection = client.connection
            player = client.player
        }
        return player?.takeIf { level != null && connection != null }
    }

    fun onMessage(client: Minecraft, message: String) {
        val line = label(message)
        if (!line.startsWith("[boss] ")) return
        if (bind(client) == null) return
        val speaker = line.substringAfter("[boss] ").substringBefore(':')
        if (speaker == "maxor") maxor = true
        else if (speaker in nextBosses) maxor = false
    }

    fun restricted(client: Minecraft): Boolean {
        val subject = bind(client) ?: return false
        val world = level ?: return false
        val tick = subject.tickCount
        if (sampledAt == Int.MIN_VALUE || tick - sampledAt >= 5 || tick < sampledAt) {
            sampledAt = tick
            area = SkyblockDetector.read(client)?.let(::classify) ?: Area()
        }
        if (area.blocked) return true
        if (!area.dungeon) {
            column = null
            maxor = false
            return false
        }
        if (area.floorSeven && maxor) return true
        val pos = subject.blockPosition()
        if (pos.x !in -200..-9 || pos.z !in -200..-9) return false
        val center = roomCenter(pos.x, pos.z)
        if (!world.hasChunk(center.x shr 4, center.z shr 4)) {
            column = null
            return false
        }
        if (center != column || tick - columnTick >= 10) {
            column = center
            columnTick = tick
            var hash = 0
            val cursor = BlockPos.MutableBlockPos()
            for (y in 140 downTo 12) {
                val state = world.getBlockState(cursor.set(center.x, y, center.z))
                val id = legacyId(state) ?: continue
                for (digit in id.toString()) hash = hash * 31 + digit.code
            }
            blockedRoom = restrictedCore(hash)
        }
        return blockedRoom
    }

    private fun legacyId(state: BlockState): Int? {
        if (state.block == Blocks.IRON_BARS || state.block == Blocks.CHEST) return 0
        val fluid = state.fluidState
        if (fluid.`is`(FluidTags.WATER)) return if (fluid.isSource) 9 else 8
        if (fluid.`is`(FluidTags.LAVA)) return if (fluid.isSource) 11 else 10
        val key = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        return legacy[if (state.block is SlabBlock) "$key[type=${state.getValue(SlabBlock.TYPE).serializedName}]" else key]
    }
}
