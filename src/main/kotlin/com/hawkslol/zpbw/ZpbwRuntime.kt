package com.hawkslol.zpbw

import com.hawkslol.zpbw.geometry.EtherwarpPredictor
import com.hawkslol.zpbw.mixin.ZpbwPlayerAccessor
import com.hawkslol.zpbw.mixin.ZpbwGameModeAccessor
import com.hawkslol.zpbw.mixin.ZpbwMinecraftAccessor
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.item.ItemStack
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundPongPacket
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.phys.Vec3

/** User-owned prediction only. No automatic use, guessed response, proxy, or heartbeat ownership. */
object ZpbwRuntime {
    enum class Mode { OFF, OBSERVE, REPLAY }
    private val mc get() = Minecraft.getInstance()
    private sealed interface Held {
        data class Wire(val packet: Packet<*>) : Held
        data class Action(val name: String, val run: Runnable) : Held
    }
    private val chain = ChainLedger<Pending, Held>()
    private val externalOwnership = PredictionOwnership()
    private var queuedUse = false
    private var physicsBefore: Pair<LocalPlayer, List<Double>>? = null
    private var physicsRebases = 0
    private val warpTimeouts = ServerTickTimeouts<Connection>()
    private var timeoutTicks = 20
    private var firstInstall = true
    private val firstInstallNotice = FirstInstallNotice()
    private val updateChecker by lazy { UpdateChecker(ZpbwClient.version) }
    private val updateNotification = UpdateNotification()
    private var updateResultLogged = false
    private var updateNoticeShown = false
    private var mode = Mode.OFF
    private var rayAttempt = 0L
    private var generation = 0L
    @Volatile private var epoch = 0L
    private var observed = 0
    private var replayed = 0
    private var failed = 0
    private var motionCorrections = 0
    private var configErrors = 0
    private var realTeleports = 0
    @Volatile private var connected: Connection? = null
    private var world: ClientLevel? = null
    private var sessionPlayer: LocalPlayer? = null
    private var handling: Handling? = null
    private var bypass = false
    private var replayingUseClick = false
    private var replayUseInput: net.minecraft.world.entity.player.Input? = null
    private var lastNotice = 0L
    private var recoveringUntil = 0L
    private var sourceCollision: Triple<LocalPlayer, Boolean, Boolean>? = null
    private var sentRotation: Pair<Float, Float>? = null
    private var sentCollision: Pair<Boolean, Boolean>? = null
    private val movementInputs = MovementInputHistory()
    private val transmittedPosition = TransmittedPositionHistory()
    private data class WaitingInput(val name: String, val warp: Boolean, val stream: Connection,
        val player: LocalPlayer, val level: ClientLevel, val slot: Int, val item: ItemStack,
        val offhand: ItemStack, val yaw: Float, val pitch: Float, val position: Vec3,
        val startedAt: Long, val ticks: Int, val action: Runnable)
    private val waitingInputs = ArrayDeque<WaitingInput>()
    private var dispatchingInput: WaitingInput? = null
    private var inputDispatchCancelled = false
    private var inputDispatchedThisTick = false
    private var queuedTickAim: Pair<Float, Float>? = null
    private data class VisibleLook(val player: LocalPlayer, val yaw: Float, val pitch: Float,
                                   val oldYaw: Float, val oldPitch: Float)
    private var visibleLook: VisibleLook? = null
    private val teleportIds = SessionTeleportIdHistory()
    private val events = ArrayDeque<String>()
    private val observations = ArrayDeque<Observation>()
    private val log by lazy { ZpbwLog() }
    private val settingsStore by lazy { ZpbwSettingsStore(FabricLoader.getInstance().gameDir.resolve("config/zpbw")) }

    private data class Pending(val generation: Long, val stream: Connection, val player: LocalPlayer,
        val level: ClientLevel, val target: Vec3, var source: PositionMoveRotation,
        var sourcePose: Pose, val started: Long, val dependent: Boolean,
        var use: ServerboundUseItemPacket? = null, val queuedInput: Boolean = false)
    private data class Observation(val stream: Connection, val target: Vec3,
        val source: PositionMoveRotation, val started: Long, var use: ServerboundUseItemPacket? = null)
    private data class Handling(val stream: Connection, val packet: ClientboundPlayerPositionPacket,
        val pending: Pending?, val advanced: Pose?, val recovery: Boolean, var invalid: Boolean = false,
        val preserveAim: Pair<Float, Float>? = null)
    private data class Pose(val move: PositionMoveRotation, val old: Vec3, val yawOld: Float, val pitchOld: Float,
        val ground: Boolean, val horizontal: Boolean, val vertical: Boolean, val below: Boolean) {
        fun restore(player: LocalPlayer) {
            player.setPos(move.position()); player.deltaMovement = move.deltaMovement()
            player.yRot = move.yRot(); player.xRot = move.xRot()
            player.setOldPosAndRot(old, yawOld, pitchOld)
            player.setOnGround(ground); player.horizontalCollision = horizontal
            player.verticalCollision = vertical; player.verticalCollisionBelow = below
        }
        fun at(position: Vec3) = copy(move = PositionMoveRotation(position, Vec3.ZERO, move.yRot(), move.xRot()), old = position,
            ground = false, horizontal = false, vertical = false, below = false)
        companion object {
            fun of(player: LocalPlayer) = Pose(PositionMoveRotation.of(player), player.oldPosition(), player.yRotO, player.xRotO,
                player.onGround(), player.horizontalCollision, player.verticalCollision, player.verticalCollisionBelow)
        }
    }

    fun initialize() {
        try {
            val settings = settingsStore.load()
            timeoutTicks = settings.timeoutTicks
            firstInstall = settings.firstInstall
            NoSneakDelay.configure(settings.noSneakDelay)
            mode = if (settings.enabled) Mode.REPLAY else Mode.OFF
        } catch (failure: Exception) {
            firstInstall = false // An unreadable existing config is not a fresh installation.
            configErrors++; event("CONFIG_READ_FAILED type=${failure.javaClass.simpleName}")
        }
        event("INITIALIZED version=1.0.0 enabled=${mode == Mode.REPLAY} orderedActions=true chainmax=${chain.maximum} observe=automatic timeoutTicks=$timeoutTicks")
        updateChecker.start()
    }
    fun select(requested: Mode): String {
        check(mc.isSameThread)
        if (requested == Mode.OFF) {
            cancelWaitingInputs("disabled")
            mode = requested; event("MODE OFF pending=${chain.count}")
            if (!saveSettings()) return "Disabled, but config could not be saved."
            return "Disabled." // Existing pending predictions still settle or fall back without kicking.
        }
        if (requested == Mode.OBSERVE && chain.count > 0) return "Use /zpbw off first; let the current chain settle."
        observations.clear(); mode = requested; event("MODE $requested")
        if (!saveSettings()) return "Setting changed, but config could not be saved."
        return if (requested == Mode.REPLAY) "Enabled."
            else "Observation active. Vanilla gameplay remains unchanged."
    }
    fun configureTimeout(ticks: Int): String {
        check(mc.isSameThread)
        val normalized = ticks.coerceIn(2, 20)
        timeoutTicks = normalized
        event("TIMEOUT_SETTING ticks=$normalized")
        val message = "Set Blinkwarp timeout to $normalized ticks."
        return if (saveSettings()) message else "$message Config could not be saved."
    }

    /** Netty receive boundary only: no player, journal, chat or packet-send access here. */
    @JvmStatic fun received(connection: Connection, packet: Packet<*>) {
        WarpTimeoutPackets.receive(warpTimeouts, connection, packet).forEach { expiry ->
            mc.execute { expire(connection, expiry) }
        }
    }

    private fun expire(connection: Connection, expiry: ServerTickTimeouts.Expiry) {
        attach()
        if (!warpTimeouts.claim(expiry)) return
        val pending = chain.head?.value
        if (connected === connection && pending != null && identity(pending)) {
            event("TIMEOUT generation=${expiry.generation} ticks=${expiry.ticks}")
            recover("server_tick_timeout", timeout = expiry.ticks)
        }
    }
    private fun drainTimeouts() {
        val connection = connected ?: return
        warpTimeouts.expiredTickets().forEach { expire(connection, it) }
    }

    @JvmStatic fun channelClosed(connection: Connection) {
        warpTimeouts.suspend(connection)
        externalOwnership.invalidateConnection(connection)
    }

    /** No state handoff is allowed while either transport or already-committed input is owned. */
    fun acquireExternalPrediction(owner: Any?): Boolean {
        if (!mc.isSameThread || owner == null) return false
        attach()
        refreshExternalSession()
        if (mc.connection?.hasClientLoaded() != true) return false
        val acquired = externalOwnership.tryAcquire(owner, true, PredictionOwnership.DrainState(
            pending = chain.count, retained = chain.size, actions = chain.hasActions || queuedUse || waitingInputs.isNotEmpty() || dispatchingInput != null,
            handling = handling != null, replaying = bypass || replayingUseClick || replayUseInput != null,
            sourcePhysics = sourceCollision != null || physicsBefore != null || movementInputs.hasRebase ||
                inputDispatchedThisTick || queuedTickAim != null || visibleLook != null,
            recovery = System.nanoTime() < recoveringUntil,
            inputCommitted = NoSneakDelay.hasCommitment()))
        if (acquired) observations.clear() // Do not attribute external uses to an old passive ray.
        return acquired
    }
    fun ownsExternalPrediction(owner: Any?): Boolean {
        if (!mc.isSameThread || owner == null) return false
        refreshExternalSession()
        return externalOwnership.owns(owner, true)
    }
    fun releaseExternalPrediction(owner: Any?): Boolean {
        if (!mc.isSameThread || owner == null) return false
        refreshExternalSession()
        return externalOwnership.release(owner, true)
    }
    private fun refreshExternalSession() {
        val stream = mc.connection?.connection?.takeIf { it.isConnected }
        externalOwnership.bind(stream, mc.level, mc.player)
    }
    fun externalPredictionActive(): Boolean {
        if (mc.isSameThread) refreshExternalSession()
        return externalOwnership.active()
    }

    fun configureNoSneakDelay(enabled: Boolean? = null): String {
        check(mc.isSameThread)
        NoSneakDelay.configure(enabled ?: !NoSneakDelay.enabled)
        val message = "${if (NoSneakDelay.enabled) "Enabled" else "Disabled"} NoSneakDelay."
        event("NSD_MODE enabled=${NoSneakDelay.enabled}")
        return if (saveSettings()) message else "$message Config could not be saved."
    }
    fun canSendSneakEarly() = !externalPredictionActive() && chain.count == 0 && handling == null && !bypass && System.nanoTime() >= recoveringUntil
    fun sneakEvent(detail: String) = event("NSD_$detail pending=${chain.count}")

    /** Route armed shovel actions and valid Etherwarp block clicks into the native item ability. */
    @JvmStatic fun redirectShovelBlockUse(player: LocalPlayer, hand: InteractionHand,
                                         hit: net.minecraft.world.phys.BlockHitResult): net.minecraft.world.InteractionResult? {
        if (externalPredictionActive()) return null
        if ((bypass && !replayingUseClick) || !mc.isSameThread || mode != Mode.REPLAY || player !== mc.player ||
            hand != InteractionHand.MAIN_HAND) return null
        val level = mc.level ?: return null
        val game = mc.gameMode ?: return null
        if (game.isSpectator || player.cooldowns.isOnCooldown(player.mainHandItem)) return null
        val sneaking = NoSneakDelay.sneakForPrediction() ?: (player.isShiftKeyDown || mc.options.keyShift.isDown)
        val shovelRoute = ShovelBlockUsePolicy.routes(player.mainHandItem, sneaking, level.getBlockState(hit.blockPos),
                hit.direction, level.getBlockState(hit.blockPos.above()).isAir)
        val warpRoute = (chain.count > 0 || sneaking) && EtherwarpPredictor.qualifiesForBlockUse(player.mainHandItem, sneaking) &&
            EtherwarpPredictor.predictCurrentEtherwarpForRotation(player.yRot, player.xRot, chain.tail?.value?.target,
                sneakingOverride = NoSneakDelay.sneakForPrediction())?.succeeded == true
        if (!shovelRoute && !warpRoute) return null
        if (warpRoute) event("ETHERWARP_BLOCK_USE_ROUTED pending=${chain.count}")
        event("SHOVEL_BLOCK_USE_ROUTED hand=$hand block=${hit.blockPos} face=${hit.direction}")
        return ShovelBlockUsePolicy.consumeRoutedUse { game.useItem(player, hand) }
    }

    @JvmStatic fun beforeUse(player: Player, hand: InteractionHand): Boolean {
        if (externalPredictionActive()) return false
        queuedUse = false
        if (bypass || !mc.isSameThread || player !== mc.player) return false
        if (hand != InteractionHand.MAIN_HAND) {
            queuedUse = deferAction("offhand_use") { mc.gameMode!!.useItem(player, hand) }
            return queuedUse
        }
        attach()
        val local = mc.player ?: return false
        val level = mc.level ?: return false
        val stream = mc.connection?.connection ?: return false
        if (!stream.isConnected || local.isPassenger ||
            local.isFallFlying || local.isInWater || local.isInLava || !local.isAlive || mc.gui.screen() != null) {
            recover("use_outside_envelope"); return false
        }
        if (mode != Mode.REPLAY && chain.count > 0) recover("vanilla_use_after_off")
        if (mode == Mode.REPLAY && System.nanoTime() < recoveringUntil) {
            event("USE_VANILLA_RECOVERY_GRACE")
            return false // Let already-dispatched dependent server teleports settle before predicting again.
        }
        // Server has not received held intermediate displacement. Dependent rays start at the
        // preceding predicted landing, never at an unsent advanced client position.
        val anchor = if (mode == Mode.REPLAY) chain.tail?.value?.target else null
        val predicted = EtherwarpPredictor.predictCurrentEtherwarpForRotation(local.yRot, local.xRot, anchor,
            sneakingOverride = NoSneakDelay.sneakForPrediction())
        if (predicted != null) {
            val attempt = ++rayAttempt
            predicted.diagnostics.forEach { event("PREDICTION_RAY attempt=$attempt nextGeneration=${generation + 1} pending=${chain.count} $it") }
        }
        val unshifted = predicted?.takeIf { it.succeeded }?.landingFeetPosition?.add(0.0, 0.05, 0.0)
        if (unshifted == null && dispatchingInput?.warp == true) {
            cancelInputDispatch("queued_target_changed")
            notice(ZpbwMessages.queueFailure("The target changed."))
            queuedUse = true
            return true
        }
        if (dispatchingInput == null && (waitingInputs.isNotEmpty() || inputDispatchedThisTick)) {
            queuedUse = queueInput(if (unshifted != null) "warp_use" else "use", unshifted != null) { mc.gameMode!!.useItem(local, hand) }
            return true
        }
        if (unshifted == null) {
            event("USE_NO_PREDICTION pending=${chain.count}")
            if (FastActionPolicy.keepPendingOnVanillaUse(chain.count)) {
                queuedUse = deferAction("use") { mc.gameMode!!.useItem(local, hand) }
                return queuedUse
            }
            recover("invalid_dependent_prediction")
            return false // Invalid real uses remain vanilla, not manufactured successful teleports.
        }
        if (mode == Mode.REPLAY && dispatchingInput == null && (chain.hasConfirmedPrefix || waitingInputs.isNotEmpty())) {
            queuedUse = queueInput("warp_use", true) { mc.gameMode!!.useItem(local, hand) }
            return true
        }
        if (mode == Mode.REPLAY && !chain.canAdd) {
            event("USE_BACKPRESSURE pending=${chain.count} max=${chain.maximum} sourceCut=${chain.tail?.cut}")
            notice(ZpbwMessages.waiting(chain.count, chain.maximum))
            return true
        }
        if (mode != Mode.REPLAY) {
            while (observations.size >= 32) observations.removeFirst()
            observations.addLast(Observation(stream, unshifted, PositionMoveRotation.of(local), System.nanoTime()))
            event("OBSERVE_USE_PREPARED target=${xyz(unshifted)}")
            return false
        }
        val target = unshifted
        // Explicit coalescing before the next real use: original inputs/commands/natural TickEnds
        // pass in order; obsolete intermediate XYZ must not replay at a later destination.
        forwardEnvelope(stream, chain.coalesce(), "next_use")
        val sourcePose = Pose.of(local).let { if (anchor == null) it else it.at(anchor) }
        generation++
        if (chain.count == 0) epoch++ // New independent chain, not an old scheduled callback's owner.
        chain.add(generation, Pending(generation, stream, local, level, target, sourcePose.move,
            sourcePose, System.nanoTime(), anchor != null, queuedInput = dispatchingInput != null))
        val budget = dispatchingInput
        if (!warpTimeouts.startAt(stream, generation, budget?.ticks ?: timeoutTicks, budget?.startedAt)) {
            recover(if (budget == null) "session_transition" else "server_tick_timeout", timeout = budget?.ticks)
            return budget != null
        }
        event("PREPARED generation=$generation pending=${chain.count} anchor=${xyz(sourcePose.move.position())} target=${xyz(target)}")
        return false // Actual vanilla use/sequence passes BEFORE previous confirmations.
    }

    @JvmStatic fun capture(connection: Connection, packet: Packet<*>): Boolean {
        if (externalPredictionActive()) return false
        if (bypass || handling != null || connection !== connected) return false
        if (!mc.isSameThread) {
            if (packet is ServerboundPongPacket || packet is ServerboundKeepAlivePacket) return false
            val owner = epoch
            mc.execute { if (epoch == owner && connected === connection && chain.count > 0) recover("off_thread_gameplay") }
            return false // Never swallow off-thread packets or take heartbeat ownership.
        }
        val tail = chain.tail ?: return false
        if (!identity(tail.value)) { recover("session_changed"); return false }
        if (packet is ServerboundUseItemPacket && tail.value.use == null && !tail.cut) return false
        if (packet is ServerboundPongPacket || packet is ServerboundKeepAlivePacket ||
            packet is ServerboundChatPacket || packet is ServerboundChatCommandPacket ||
            packet is ServerboundChatCommandSignedPacket || packet is ServerboundPingRequestPacket) return false
        if (!tail.cut && !tail.value.dependent) return false
        if (!tail.cut && packet is ServerboundMovePlayerPacket && !packet.hasPosition()) return false
        if (!tail.cut && packet is ServerboundClientTickEndPacket) {
            forwardEnvelope(connection, chain.coalesce(), "source_tick")
            return false // Exactly this original source TickEnd passes once before local prediction.
        }
        val fastAction = tail.cut && FastActionPolicy.retains(packet)
        if (fastAction || packet is ServerboundMovePlayerPacket || packet is ServerboundClientTickEndPacket ||
            packet is ServerboundPlayerInputPacket || packet is ServerboundPlayerCommandPacket || packet is ServerboundSwingPacket) {
            try {
                chain.retain(Held.Wire(packet), action = fastAction)
                if (fastAction) event("FAST_ACTION_QUEUED kind=${kind(packet)} pending=${chain.count}")
                event("RETAIN generation=${tail.generation} kind=${kind(packet)} size=${chain.size}")
                return true
            } catch (_: IllegalStateException) {
                recover("packet_buffer_limit")
                return packet is ServerboundMovePlayerPacket || (fastAction && FastActionPolicy.worldAction(packet))
            }
        }
        recover("unsupported_outgoing_${packet.javaClass.simpleName}")
        return false
    }

    /** Only reached after the real send delegate; cancelled capture never reaches it. */
    @JvmStatic fun afterSend(connection: Connection, packet: Packet<*>) {
        if (!mc.isSameThread || connection !== connected) return
        // Include bypass replay and genuine native responses, but never retained packets.
        if (packet is ServerboundMovePlayerPacket && packet.hasRotation())
            sentRotation = packet.getYRot(0f) to packet.getXRot(0f)
        // A teleport response is not a simulation tick and does not replace the previous
        // regular ground/collision history. Include original replay/source status packets.
        if (packet is ServerboundMovePlayerPacket && handling == null)
            sentCollision = packet.isOnGround to packet.horizontalCollision()
        if (packet is ServerboundPlayerInputPacket) movementInputs.input(packet.input.shift())
        if (packet is ServerboundMovePlayerPacket && packet.hasPosition())
            movementInputs.position(teleportResponse = handling != null)
        if (packet is ServerboundMovePlayerPacket && packet.hasPosition())
            transmittedPosition.position(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0), regular = handling == null)
        if (packet is ServerboundClientTickEndPacket) transmittedPosition.tickEnd()
        if (externalPredictionActive()) return
        if (bypass || handling != null) return
        if (packet is ServerboundUseItemPacket) {
            if (dispatchingInput != null) queuedTickAim = packet.yRot to packet.xRot
            event("USE_REQUESTED sequence=${packet.sequence} yaw=${packet.yRot} pitch=${packet.xRot} pending=${chain.count}")
            val tail = chain.tail
            if (tail != null && !tail.cut && tail.value.use == null) tail.value.use = packet
            else observations.lastOrNull { it.stream === connection && it.use == null }?.use = packet
        }
        val tail = chain.tail ?: return
        val p = tail.value
        if (packet is ServerboundMovePlayerPacket && !packet.hasPosition() && !tail.cut) {
            event("SOURCE_STATE_REQUESTED generation=${p.generation} ${kind(packet)}")
            if (p.dependent) {
                // This real source movement updates the server's collision state, even before
                // any teleport confirms. Recovery must not resume the older grounded snapshot.
                val rollback = chain.head!!.value
                rollback.sourcePose = rollback.sourcePose.copy(ground = packet.isOnGround,
                    horizontal = packet.horizontalCollision(), vertical = packet.isOnGround, below = packet.isOnGround)
            }
        }
        if (packet !is ServerboundClientTickEndPacket || tail.cut || p.use == null || !identity(p)) return
        if (!p.dependent) {
            p.sourcePose = Pose.of(p.player)
            if (p.queuedInput) p.sourcePose = p.sourcePose.copy(move = PositionMoveRotation(
                p.sourcePose.move.position(), p.sourcePose.move.deltaMovement(), p.use!!.yRot, p.use!!.xRot))
            p.source = p.sourcePose.move
        }
        chain.cut(tail.generation)
        event("SOURCE_TICK_END_REQUESTED generation=${p.generation}")
        p.player.setPos(p.target); p.player.deltaMovement = Vec3.ZERO; p.player.setOldPosAndRot()
        prepareReplayLook(p.player)
        if (p.dependent) {
            // Position changes and discarded speculative ticks do not advance collision history.
            // Resume the same source state that the server received before the genuine teleport.
            p.player.setOnGround(p.sourcePose.ground); p.player.horizontalCollision = p.sourcePose.horizontal
            p.player.verticalCollision = p.sourcePose.vertical; p.player.verticalCollisionBelow = p.sourcePose.below
        }
        // Independent source tick remains unchanged; no invented movement or TickEnd.
        event("LOCAL_PREDICTED generation=${p.generation} use=${p.use!!.sequence} elapsedUs=${(System.nanoTime()-p.started)/1000} pending=${chain.count}")
    }

    /** Coalesce XYZ in a dependent use's original source tick, retaining native look selection.
     * Position-only speculation must not advance the transmitted collision history. Native
     * sendPosition chooses its one appropriate packet; no extra packet or tick is constructed. */
    @JvmStatic fun sourceTick(player: LocalPlayer) {
        if (externalPredictionActive()) return
        if (!mc.isSameThread || player !== sessionPlayer) return
        val tail = chain.tail ?: return
        val p = tail.value
        if (!mc.isSameThread || handling != null || tail.cut || !p.dependent || p.use == null || player !== p.player || !identity(p)) return
        // Native click animations/input belong before this tick's movement. Waiting until
        // TickEnd would move a chained block-click swing after Rot (Grim PacketOrderO).
        forwardEnvelope(p.stream, chain.coalesce(), "source_before_move")
        val sender = player as ZpbwPlayerAccessor
        sender.`zpbw$setXLast`(player.x); sender.`zpbw$setYLast`(player.y); sender.`zpbw$setZLast`(player.z)
        sender.`zpbw$setPositionReminder`(0)
        // Compare against the look actually sent, not a withheld tick's sender cache.
        // Unchanged straight chains must not manufacture redundant look updates.
        sender.`zpbw$setYRotLast`(sentRotation?.first ?: Math.nextUp(player.yRot))
        sender.`zpbw$setXRotLast`(sentRotation?.second ?: player.xRot)
        val collision = sentCollision ?: chain.head!!.value.sourcePose.let { it.ground to it.horizontal }
        sender.`zpbw$setLastOnGround`(collision.first)
        sender.`zpbw$setLastHorizontalCollision`(collision.second)
        sourceCollision = Triple(player, player.onGround(), player.horizontalCollision)
        // Neither an unsent landing nor an unsent departure can change the source state.
        // Native teleport responses also preserve that prior simulation state.
        player.setOnGround(collision.first); player.horizontalCollision = collision.second
        p.sourcePose = p.sourcePose.copy(ground = collision.first, horizontal = collision.second,
            vertical = collision.first, below = collision.first)
        event("SOURCE_XYZ_COALESCED generation=${p.generation}")
    }
    @JvmStatic fun sourceTickFinished(player: LocalPlayer) {
        if (externalPredictionActive()) return
        val saved = sourceCollision ?: return
        sourceCollision = null
        if (player === saved.first) { player.setOnGround(saved.second); player.horizontalCollision = saved.third }
    }
    /** The queued native click, its physics and its movement report share one input sample.
     * Restore both current and interpolated view before rendering; never override only the wire look. */
    @JvmStatic fun beginQueuedPhysicsTick(player: LocalPlayer) {
        if (!mc.isSameThread || player !== sessionPlayer || externalPredictionActive()) return
        val aim = queuedTickAim ?: return
        check(visibleLook == null)
        visibleLook = VisibleLook(player, player.yRot, player.xRot, player.yRotO, player.xRotO)
        player.yRot = aim.first; player.xRot = aim.second
        event("QUEUED_SOURCE_INPUT yaw=${aim.first} pitch=${aim.second} viewYaw=${visibleLook!!.yaw} viewPitch=${visibleLook!!.pitch}")
    }
    @JvmStatic fun finishQueuedPhysicsTick(player: LocalPlayer) {
        val view = visibleLook ?: return
        if (player !== view.player) return
        player.yRot = view.yaw; player.xRot = view.pitch
        player.yRotO = view.oldYaw; player.xRotO = view.oldPitch
        visibleLook = null
    }

    /** Every genuine handler runs. Mismatches restore authoritative interpretation, never kick. */
    @JvmStatic fun beforeGenuine(connection: Connection, packet: ClientboundPlayerPositionPacket): Boolean {
        attach(connection)
        drainTimeouts() // Enforce the Netty deadline even if the packet queue runs before tasks.
        val duplicate = teleportIds.record(packet.id())
        realTeleports++
        if (externalPredictionActive()) return false // The external owner and vanilla own this handler.
        event("TELEPORT id=${packet.id()} duplicate=$duplicate raw=${xyz(packet.change().position())} flags=${packet.relatives()} pending=${chain.count} velocity=${xyz(packet.change().deltaMovement())} yaw=${packet.change().yRot()} pitch=${packet.change().xRot()}")
        val entry = chain.head
        val p = entry?.value
        if (p == null || connection !== p.stream) {
            cancelWaitingInputs("unrelated_teleport")
            val observation = observations.firstOrNull { it.stream === connection && it.use != null }
            if (observation != null) {
                val absolute = PositionMoveRotation.calculateAbsolute(observation.source, packet.change(), packet.relatives())
                val match = matches(observation.source, observation.target, absolute, packet)
                if (match) observed++
                event("OBSERVE_RESULT id=${packet.id()} exact=$match waitUs=${(System.nanoTime()-observation.started)/1000} actual=${xyz(absolute.position())}")
                observations.removeFirst()
            }
            handling = Handling(connection, packet, null, null, System.nanoTime() < recoveringUntil)
            return false
        }
        val absolute = PositionMoveRotation.calculateAbsolute(p.source, packet.change(), packet.relatives())
        val decision = ConfirmationPolicy.inspect(p.source, p.target, absolute, packet.relatives())
        val exact = decision.canReplay
        val identityValid = identity(p)
        warpTimeouts.cancel(p.generation) // Also covers main-thread/direct handler delivery.
        event("CONFIRMATION_CHECK generation=${p.generation} id=${packet.id()} positionMatch=${decision.positionMatches} zeroVelocity=${decision.zeroVelocity} rotationMatch=${decision.rotationMatches} finite=${decision.finite} supportedFlags=${decision.supportedFlags} sourceYaw=${p.source.yRot()} sourcePitch=${p.source.xRot()} actualYaw=${absolute.yRot()} actualPitch=${absolute.xRot()} actualVelocity=${xyz(absolute.deltaMovement())} reason=${decision.reason}")
        if (identityValid && entry.cut && !duplicate && decision.motionCorrection) {
            val aim = p.player.yRot to p.player.xRot
            // Position was right, but replaying zero-velocity speculation would override real
            // server physics. Let the complete native handler apply that motion and cancel only
            // dependent speculation; preserve the user's camera after the genuine response.
            // The native handler preserves ground/collision state. Seed it from the actual
            // source-tick state, not the speculative chain's later grounded landing.
            recover(decision.reason, actual = absolute.position(), positionConfirmed = true)
            p.player.yRot = p.source.yRot(); p.player.xRot = p.source.xRot()
            handling = Handling(connection, packet, null, null, true, preserveAim = aim)
            return false
        }
        if (!identityValid || !entry.cut || duplicate || !exact) {
            event("MISMATCH generation=${p.generation} id=${packet.id()} exact=$exact cut=${entry.cut} distance=${absolute.position().distanceTo(p.target)} duplicate=$duplicate identity=$identityValid")
            val reason = when {
                !identityValid -> "session_changed"
                !entry.cut -> "confirmation_before_source_tick"
                duplicate -> "duplicate_confirmation"
                else -> decision.reason
            }
            recover(reason, actual = absolute.position())
            handling = Handling(connection, packet, null, null, true)
            return false
        }
        chain.genuine(p.generation, packet.id())
        handling = Handling(connection, packet, p, Pose.of(p.player), false)
        p.player.yRot = p.source.yRot(); p.player.xRot = p.source.xRot()
        event("GENUINE_MATCH generation=${p.generation} id=${packet.id()} waitUs=${(System.nanoTime()-p.started)/1000}")
        return false // Vanilla owns its genuine Accept and PosRot, always.
    }

    @JvmStatic fun nativeResponse(connection: Connection, response: Packet<*>, genuine: ClientboundPlayerPositionPacket) {
        if (externalPredictionActive()) return
        val h = handling ?: return
        if (connection !== h.stream || genuine !== h.packet) return
        event("NATIVE_RESPONSE id=${genuine.id()} kind=${kind(response)}")
        val p = h.pending ?: return
        try {
            when (response) {
                is ServerboundAcceptTeleportationPacket -> { check(response.id == genuine.id()); chain.response(response.id, true) }
                is ServerboundMovePlayerPacket.PosRot -> {
                    check(Vec3(response.getX(0.0), response.getY(0.0), response.getZ(0.0)).distanceToSqr(p.target) <= 1e-14)
                    check(!response.isOnGround && !response.horizontalCollision())
                    chain.response(genuine.id(), false)
                }
                else -> error("unexpected_native_response")
            }
        } catch (_: IllegalStateException) { h.invalid = true }
    }

    @JvmStatic fun afterGenuine(connection: Connection, packet: ClientboundPlayerPositionPacket) {
        if (externalPredictionActive()) return
        val h = handling ?: return
        if (connection !== h.stream || packet !== h.packet) return
        handling = null
        val p = h.pending
        if (p != null) {
            if (!identity(p) || h.invalid) {
                recover("native_response_or_session_contract", restore = false, actual = mc.player?.position())
                mc.player?.let(::rebaseSender)
                return
            }
            try {
                val held = chain.settle(p.generation, packet.id())
                if (chain.count == 0) epoch++
                h.advanced!!.restore(p.player)
                bypass = true
                try { for (original in held) when (original) {
                    is Held.Wire -> p.stream.send(original.packet)
                    is Held.Action -> { original.run.run(); event("FAST_ACTION_EXECUTED kind=${original.name}") }
                } } finally { bypass = false }
                replayed++
                event("SETTLED generation=${p.generation} id=${packet.id()} replayed=${held.size} pending=${chain.count} pose=${xyz(p.player.position())}")
            } catch (_: RuntimeException) { recover("settle_failed", restore = false, actual = mc.player?.position()); mc.player?.let(::rebaseSender) }
        } else if (h.recovery) {
            mc.player?.let { player ->
                // Preserve vanilla's first post-teleport physics. Include the authoritative
                // position in the next natural movement so a stationary ground transition
                // above the destination surface is not a positionless claim.
                h.preserveAim?.let { (yaw, pitch) -> player.yRot = yaw; player.xRot = pitch }
                rebaseSender(player, forceLook = h.preserveAim != null)
                (player as ZpbwPlayerAccessor).`zpbw$setPositionReminder`(20)
            }
            event("RECOVERY_AUTHORITATIVE id=${packet.id()} pose=${mc.player?.position()?.let(::xyz)}")
        }
        event("VANILLA_HANDLER_COMPLETE id=${packet.id()} pose=${mc.player?.position()?.let(::xyz)}")
        drainTimeouts() // A blocked successor expiry can now recover after this real response.
    }

    fun tick() {
        attach()
        val update = updateChecker.snapshot()
        if (update != null && !updateResultLogged) {
            updateResultLogged = true
            event("UPDATE_CHECK_COMPLETE status=${update.status} version=${update.version ?: "none"}")
        }
        val inSkyblock = (firstInstall || (!updateNoticeShown && update?.status == UpdateCheckStatus.AVAILABLE)) &&
            mc.player != null && mc.connection != null && SkyblockDetector.inSkyblock(mc)
        var welcomeShown = false
        if (firstInstall && firstInstallNotice.poll(firstInstall, mc.level,
                inSkyblock, System.nanoTime())) {
            mc.gui.chatListener().handleSystemMessage(ZpbwMessages.welcome(ZpbwClient.version) { mc.font.width(it) }, false)
            firstInstall = false
            if (!saveSettings()) event("FIRST_INSTALL_SAVE_FAILED")
            event("FIRST_INSTALL_SHOWN version=${ZpbwClient.version}")
            welcomeShown = true
        }
        updateNotification.poll(update, firstInstall || welcomeShown, mc.level, inSkyblock, System.nanoTime())?.let {
            updateNoticeShown = true
            mc.gui.chatListener().handleSystemMessage(ZpbwMessages.updateAvailable(ZpbwClient.version,
                it.version!!, it.url!!) { text -> mc.font.width(text) }, false)
            event("UPDATE_NOTICE_SHOWN version=${it.version}")
        }
        val p = chain.head?.value
        if (p != null && (!identity(p) || !p.player.isAlive || p.player.isPassenger)) recover("player_lifecycle_changed")
        drainTimeouts()
        expireWaitingInputs()
        // Minecraft does not call handleKeybinds while a screen/overlay is open.
        // Retire stale world clicks here, so closing the UI cannot revive an old action.
        if (waitingInputs.isNotEmpty() && (mc.gui.screen() != null || mc.gui.overlay() != null)) {
            cancelWaitingInputs("input_context_changed")
            notice(ZpbwMessages.queueFailure("A screen was opened."))
        }
    }
    @JvmStatic fun takeQueuedUse(): Boolean = queuedUse.also { queuedUse = false }

    private fun queueInput(name: String, warp: Boolean, action: Runnable): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val stream = mc.connection?.connection ?: return false
        val stamp = warpTimeouts.sample(stream) ?: return false
        if (waitingInputs.size >= 32 || (warp && chain.count + waitingInputs.count { it.warp } >= chain.maximum)) {
            notice(ZpbwMessages.waiting(chain.count, chain.maximum))
            event("INPUT_BACKPRESSURE kind=$name pending=${chain.count} queued=${waitingInputs.size}")
            return true
        }
        waitingInputs.addLast(WaitingInput(name, warp, stream, player, level, player.inventory.selectedSlot,
            player.mainHandItem.copy(), player.offhandItem.copy(), player.yRot, player.xRot, player.position(),
            stamp, timeoutTicks, action))
        event("INPUT_QUEUED kind=$name pending=${chain.count} queued=${waitingInputs.size} deadlineTicks=$timeoutTicks")
        return true
    }
    private fun cancelWaitingInputs(reason: String) {
        if (waitingInputs.isNotEmpty()) event("INPUTS_CANCELLED reason=$reason count=${waitingInputs.size}")
        waitingInputs.clear()
    }
    private fun cancelInputDispatch(reason: String) {
        if (dispatchingInput == null) return
        inputDispatchCancelled = true
        inputDispatchedThisTick = false
        queuedTickAim = null
        cancelWaitingInputs(reason)
        notice(ZpbwMessages.queueFailure("The target or player state changed."))
    }
    private fun expireWaitingInputs() {
        // Settings may change between clicks: a later short deadline must not wait for
        // an earlier long one to reach the queue head.
        val expired = waitingInputs.firstOrNull {
            val elapsed = warpTimeouts.sample(it.stream)?.minus(it.startedAt)
            elapsed == null || (it.warp && elapsed >= it.ticks)
        } ?: return
        if (warpTimeouts.sample(expired.stream) == null) { cancelWaitingInputs("session_changed"); return }
        cancelWaitingInputs("server_tick_timeout")
        if (expired.warp) {
            failed++
            mc.gui.chatListener().handleSystemMessage(ZpbwMessages.timeout(expired.ticks), false)
        }
    }
    /** Before native keybind handling, never from the teleport replay/bypass stack. */
    @JvmStatic fun dispatchWaitingInput() {
        if (!mc.isSameThread || externalPredictionActive()) return
        attach(); drainTimeouts(); expireWaitingInputs()
        if (chain.count != 0 || handling != null || bypass || dispatchingInput != null || inputDispatchedThisTick) return
        val next = waitingInputs.firstOrNull() ?: return
        val player = next.player
        val mobile = next.name in setOf("warp_use", "warp_click", "use", "use_click", "offhand_use", "entity_attack_click", "entity_use_click")
        if (mc.player !== player || mc.level !== next.level || mc.connection?.connection !== next.stream ||
            !next.stream.isConnected || !player.isAlive || player.isPassenger || mc.gui.screen() != null ||
            player.isHandsBusy || mc.gameMode?.isSpectator != false ||
            !ItemStack.isSameItemSameComponents(next.item, player.inventory.getItem(next.slot)) ||
            !ItemStack.isSameItemSameComponents(next.offhand, player.offhandItem) ||
            (!mobile && player.position().distanceToSqr(next.position) > 1e-10) ||
            (next.warp && (player.isFallFlying || player.isInWater || player.isInLava ||
                !(NoSneakDelay.sneakForPrediction() ?: player.isShiftKeyDown) || player.cooldowns.isOnCooldown(player.inventory.getItem(next.slot))))) {
            cancelWaitingInputs("context_changed"); notice(ZpbwMessages.queueFailure("Player state or held item changed.")); return
        }
        waitingInputs.removeFirst()
        val slot = player.inventory.selectedSlot
        val aim = player.yRot to player.xRot
        dispatchingInput = next
        inputDispatchCancelled = false
        inputDispatchedThisTick = true
        queuedTickAim = next.yaw to next.pitch
        try {
            player.inventory.setSelectedSlot(next.slot)
            player.yRot = next.yaw; player.xRot = next.pitch
            next.action.run()
            event("${if (inputDispatchCancelled) "INPUT_CANCELLED" else "INPUT_DISPATCHED"} kind=${next.name} waitedTicks=${warpTimeouts.sample(next.stream)?.minus(next.startedAt)} remaining=${waitingInputs.size}")
        } catch (_: RuntimeException) {
            cancelWaitingInputs("dispatch_failed")
            recover("input_dispatch_failed")
        } finally {
            player.inventory.setSelectedSlot(slot)
            player.yRot = aim.first; player.xRot = aim.second
            dispatchingInput = null
        }
    }
    @JvmStatic fun finishInputDispatchTick() { inputDispatchedThisTick = false; queuedTickAim = null }

    /** A held click has no result yet. Resume vanilla's complete hand/block/item decision once,
     * rather than returning a synthetic SUCCESS from its first block-use step. */
    @JvmStatic fun deferUseClick(): Boolean {
        if (externalPredictionActive()) return false
        if (bypass || dispatchingInput != null || !mc.isSameThread || (chain.count == 0 && waitingInputs.isEmpty() && !inputDispatchedThisTick)) return false
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val game = mc.gameMode ?: return false
        val input = mc as ZpbwMinecraftAccessor
        val hit = input.`zpbw$getHitResult`() ?: return false
        if (hit is EntityHitResult || player.isHandsBusy || game.isDestroying || game.isSpectator || mc.gui.screen() != null) return false
        val block = (hit as? net.minecraft.world.phys.BlockHitResult)?.takeIf { it.type == net.minecraft.world.phys.HitResult.Type.BLOCK }
        // A valid Etherwarp has its own ordered source-use path, including close block clicks.
        // redirectShovelBlockUse routes the dependent ability before block-use deferral can
        // occupy the journal and prevent the following item use from joining the chain.
        // This read-only preflight does not prepare a warp, allocate a sequence or send a packet.
        val warpClick = mode == Mode.REPLAY && !player.cooldowns.isOnCooldown(player.mainHandItem) &&
            EtherwarpPredictor.predictCurrentEtherwarpForRotation(player.yRot, player.xRot, chain.tail?.value?.target,
                sneakingOverride = NoSneakDelay.sneakForPrediction())?.succeeded == true
        if (warpClick && !chain.hasConfirmedPrefix && waitingInputs.isEmpty() && !inputDispatchedThisTick) return false
        val main = player.mainHandItem.copy()
        val off = player.offhandItem.copy()
        val state = block?.let { level.getBlockState(it.blockPos) }
        val clickedInput = NoSneakDelay.inputForClick() ?: player.input.keyPresses
        val queued = deferAction(if (warpClick) "warp_click" else "use_click") {
            if (mc.gui.screen() != null || mc.gameMode !== game || player.isHandsBusy || game.isDestroying ||
                !ItemStack.isSameItemSameComponents(main, player.mainHandItem) ||
                !ItemStack.isSameItemSameComponents(off, player.offhandItem) ||
                (!warpClick && block != null && (!player.isWithinBlockInteractionRange(block.blockPos, 0.0) ||
                    level.getBlockState(block.blockPos) != state))) {
                event("USE_CLICK_CANCELLED reason=target_item_or_context_changed")
                cancelInputDispatch("queued_click_context_changed")
            } else {
                val advancedHit = input.`zpbw$getHitResult`()
                val advancedEntity = mc.crosshairPickEntity
                val advancedInput = player.input.keyPresses
                val delay = input.`zpbw$getRightClickDelay`()
                try {
                    replayingUseClick = true; replayUseInput = clickedInput
                    player.input.keyPresses = clickedInput
                    if (dispatchingInput != null) {
                        // Etherwarp is directional: recompute its ray from the settled origin.
                        // Ordinary block clicks keep the original validated world point.
                        if (!warpClick && block != null) aimAtClickedPoint(player, hit.location)
                        input.`zpbw$pick`(1.0f)
                        val fresh = input.`zpbw$getHitResult`()
                        val sameBlock = warpClick || block == null || (fresh is net.minecraft.world.phys.BlockHitResult && fresh.blockPos == block.blockPos && fresh.type == block.type)
                        if (!sameBlock || fresh is EntityHitResult || (!warpClick && fresh?.type != hit.type)) {
                            cancelInputDispatch("queued_click_target_changed")
                            event("USE_CLICK_CANCELLED reason=queued_ray_changed")
                            return@deferAction
                        }
                    } else input.`zpbw$setHitResult`(hit)
                    mc.crosshairPickEntity = null
                    input.`zpbw$startUseItem`() // Owns fall-through, both hands and real-result swings.
                } finally {
                    replayingUseClick = false; replayUseInput = null
                    player.input.keyPresses = advancedInput
                    input.`zpbw$setHitResult`(advancedHit); mc.crosshairPickEntity = advancedEntity
                    input.`zpbw$setRightClickDelay`(delay) // Already paid the source click's delay.
                }
            }
        }
        if (queued) input.`zpbw$setRightClickDelay`(4)
        return queued
    }
    fun deferredUseInput() = replayUseInput

    /** Keep native attack/interact, swing and other mods' click-scoped observers together. */
    @JvmStatic fun deferEntityClick(attack: Boolean): Boolean {
        if (externalPredictionActive()) return false
        if (bypass || dispatchingInput != null || !mc.isSameThread || (chain.count == 0 && waitingInputs.isEmpty() && !inputDispatchedThisTick)) return false
        val input = mc as ZpbwMinecraftAccessor
        val hit = input.`zpbw$getHitResult`() as? EntityHitResult ?: return false
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        if (player.isHandsBusy || gameMode.isSpectator || mc.gui.screen() != null ||
            (attack && input.`zpbw$getMissTime`() > 0) || (!attack && gameMode.isDestroying)) return false
        val item = player.mainHandItem.copy()
        val offhand = player.offhandItem.copy()
        val queued = deferAction(if (attack) "entity_attack_click" else "entity_use_click") {
            // Never retarget a later crosshair, reused entity ID, changed stack, or removed target.
            val entity = hit.entity
            if (!entity.isAlive || entity.level() !== mc.level || mc.level?.getEntity(entity.id) !== entity ||
                !entity.boundingBox.inflate(1e-4).contains(hit.location) ||
                !player.isWithinEntityInteractionRange(entity, 0.0) || mc.gui.screen() != null ||
                !ItemStack.isSameItemSameComponents(item, player.mainHandItem) ||
                (!attack && !ItemStack.isSameItemSameComponents(offhand, player.offhandItem))) {
                event("ENTITY_CLICK_CANCELLED reason=target_or_item_changed attack=$attack")
                cancelInputDispatch("queued_entity_context_changed")
            } else {
                val advancedHit = input.`zpbw$getHitResult`()
                val advancedEntity = mc.crosshairPickEntity
                val currentDelay = input.`zpbw$getRightClickDelay`()
                try {
                    if (dispatchingInput != null) {
                        // Keep this original entity/point, not the user's later crosshair.
                        // A tiny inward bias avoids a floating-point miss at its exact surface.
                        aimAtClickedPoint(player, hit.location.lerp(entity.boundingBox.center, 1e-6))
                        input.`zpbw$pick`(1.0f)
                        val fresh = input.`zpbw$getHitResult`() as? EntityHitResult
                        if (fresh?.entity !== entity) {
                            cancelInputDispatch("queued_entity_target_changed")
                            event("ENTITY_CLICK_CANCELLED reason=queued_ray_changed attack=$attack")
                            return@deferAction
                        }
                    } else input.`zpbw$setHitResult`(hit)
                    mc.crosshairPickEntity = entity
                    if (attack) input.`zpbw$startAttack`() else input.`zpbw$startUseItem`()
                } finally {
                    input.`zpbw$setHitResult`(advancedHit)
                    mc.crosshairPickEntity = advancedEntity
                    // The source click already paid vanilla's four-tick input delay.
                    if (!attack) input.`zpbw$setRightClickDelay`(currentDelay)
                }
            }
        }
        if (queued && !attack) input.`zpbw$setRightClickDelay`(4)
        return queued
    }

    /** Express an already validated click point in the settled player's native input frame. */
    private fun aimAtClickedPoint(player: LocalPlayer, point: Vec3) {
        ClickIntentAim.toward(player.eyePosition, point)?.let {
            player.yRot = it.first; player.xRot = it.second
            queuedTickAim = it
        }
    }

    /** Defer native invocation, not an already-numbered interaction packet or mutated item stack. */
    @JvmStatic fun deferAction(name: String, action: Runnable): Boolean {
        if (externalPredictionActive()) return false
        if (bypass || dispatchingInput != null || !mc.isSameThread) return false
        if (waitingInputs.isNotEmpty() || inputDispatchedThisTick || (name == "warp_click" && chain.hasConfirmedPrefix))
            return queueInput(name, name == "warp_click", action)
        val tail = chain.tail ?: return false
        if (!tail.cut || !identity(tail.value)) return false
        val player = tail.value.player
        val gameMode = mc.gameMode ?: return false
        val accessor = gameMode as ZpbwGameModeAccessor
        val pose = Pose.of(player)
        val slot = player.inventory.selectedSlot
        try {
            // This vanilla method creates any required slot packet BEFORE the deferred action.
            accessor.`zpbw$syncCarriedItem`()
            chain.retain(Held.Action(name, Runnable {
                val advanced = Pose.of(player)
                val currentSlot = player.inventory.selectedSlot
                val carried = accessor.`zpbw$getCarriedIndex`()
                try {
                    pose.restore(player); player.inventory.setSelectedSlot(slot)
                    accessor.`zpbw$setCarriedIndex`(slot)
                    action.run()
                } finally {
                    advanced.restore(player); player.inventory.setSelectedSlot(currentSlot)
                    accessor.`zpbw$setCarriedIndex`(carried)
                }
            }), action = true)
            event("FAST_ACTION_DEFERRED kind=$name pending=${chain.count}")
        } catch (_: IllegalStateException) { recover("action_buffer_limit") }
        return true
    }
    fun joined() { firstInstallNotice.reset(); updateNotification.reset(); attach(); event("JOIN observe=automatic mode=$mode realTeleports=$realTeleports") }
    fun reset() {
        firstInstallNotice.reset()
        updateNotification.reset()
        cancelWaitingInputs("disconnect"); inputDispatchedThisTick = false
        queuedTickAim = null; visibleLook = null
        externalOwnership.bind(null, null, null)
        NoSneakDelay.reset()
        epoch++; warpTimeouts.bind(null); chain.clear(); observations.clear(); handling = null; teleportIds.clear()
        connected = null; world = null; sessionPlayer = null; sentRotation = null
        sentCollision = null
        movementInputs.reset()
        transmittedPosition.reset()
        event("DISCONNECT cleared=true enabled=${mode == Mode.REPLAY}")
    }
    fun status() = "${if (mode == Mode.REPLAY) "on" else "off"}; orderedActions=true, nsd=${NoSneakDelay.enabled}, pending=${chain.count}/${chain.maximum}, timeoutTicks=$timeoutTicks, retained=${chain.size}, genuine=$realTeleports, observedMatches=$observed, settled=$replayed, fallbacks=$failed, motionCorrections=$motionCorrections, physicsRebases=$physicsRebases, logDrops=${log.dropped.get()}, logErrors=${log.errors.get()}, configErrors=$configErrors; remote acceptance not measured"

    private fun physicsState(player: LocalPlayer): List<Double> = listOf(
        Attributes.MOVEMENT_SPEED, Attributes.GRAVITY, Attributes.JUMP_STRENGTH,
        Attributes.SNEAKING_SPEED, Attributes.STEP_HEIGHT, Attributes.SCALE, Attributes.FLYING_SPEED
    ).map { player.getAttribute(it)?.value ?: 0.0 } + listOf(
        (player.getEffect(MobEffects.LEVITATION)?.amplifier ?: -1).toDouble(),
        (player.getEffect(MobEffects.SLOW_FALLING)?.amplifier ?: -1).toDouble())

    @JvmStatic fun beforePhysicsUpdate() {
        if (externalPredictionActive()) return
        if (!mc.isSameThread) return
        physicsBefore = mc.player?.takeIf { chain.count > 0 && handling == null }?.let { it to physicsState(it) }
    }
    @JvmStatic fun afterPhysicsUpdate() {
        if (externalPredictionActive()) return
        if (!mc.isSameThread) return
        val before = physicsBefore ?: return
        physicsBefore = null
        val tail = chain.tail ?: return
        val p = tail.value
        if (!tail.cut) return // The real source tick has not relocated the player yet.
        if (handling != null || before.first !== p.player || !identity(p)) return
        val after = physicsState(p.player)
        if (before.second == after) return
        val obsolete = chain.rebaseJournal()
        // A prior warp's buff can be acknowledged before the final warp confirms. Replaying
        // pre-buff motion then would ask the server to validate physics the client no longer has.
        // Keep every pending genuine warp; discard only obsolete motion/unexecuted actions.
        forwardEnvelope(p.stream, obsolete, "physics_changed", cancelWorldActions = true)
        // Discarded ticks also contained pose/input history. The next native aiStep must
        // start from the last transmitted simulation step, while sampling live input normally.
        movementInputs.rebase()
        p.player.setPos(p.target); p.player.deltaMovement = Vec3.ZERO
        p.player.setOnGround(p.sourcePose.ground)
        p.player.horizontalCollision = p.sourcePose.horizontal
        p.player.verticalCollision = p.sourcePose.vertical
        p.player.verticalCollisionBelow = p.sourcePose.below
        p.player.setOldPosAndRot(); rebaseSender(p.player, forceLook = true); prepareReplayLook(p.player)
        // Rebase erased vanilla's pre-teleport XYZ delta. Its first resumed tick still needs
        // a position-bearing report, especially a zero-distance ground transition at Y+0.05.
        // It stays held until the genuine response; no extra packet or simulation tick is added.
        (p.player as ZpbwPlayerAccessor).`zpbw$setPositionReminder`(20)
        physicsRebases++
        event("PHYSICS_REBASE pending=${chain.count} discarded=${obsolete.size} speedBefore=${before.second[0]} speedAfter=${after[0]} target=${xyz(p.target)} crouching=${p.player.isCrouching} input=${p.player.input.keyPresses} sentInput=${p.player.lastSentInput}")
    }
    @JvmStatic fun previousSneakForPhysics(player: LocalPlayer, original: Boolean): Boolean {
        if (!mc.isSameThread || player !== sessionPlayer || externalPredictionActive()) return original
        val restored = movementInputs.previousSneak(original)
        if (restored != original) event("PHYSICS_INPUT_REBASED previousSneak=$restored speculativeSneak=$original")
        return restored
    }
    @JvmStatic fun finishPreviousPhysicsInput() { movementInputs.clearRebase() }
    @Synchronized fun diagnostics(): List<String> = events.toList()
    fun logReport(): String {
        check(mc.isSameThread)
        fun bounded(value: String) = value.filter { it >= ' ' && it != '\u007f' }.take(160)
        val mods = FabricLoader.getInstance().allMods.sortedBy { it.metadata.id }.take(200)
            .joinToString(", ") { bounded(it.metadata.id) + "@" + bounded(it.metadata.version.friendlyString) }.take(12_000)
        val environment = if (mc.connection == null) "disconnected" else if (mc.hasSingleplayerServer()) "singleplayer" else "multiplayer"
        return "ZPBW troubleshooting report v1\n" +
            "Version: ${ZpbwClient.version}\nCaptured: ${java.time.Instant.now()}\n" +
            "Platform: ${bounded(System.getProperty("os.name", "unknown"))}; Java: ${bounded(System.getProperty("java.version", "unknown"))}\n" +
            "Session: $environment\nSettings: enabled=${mode == Mode.REPLAY}, noSneakDelay=${NoSneakDelay.enabled}, timeoutTicks=$timeoutTicks, firstInstall=$firstInstall\n" +
            "State: ${status()}\nUpdate check: ${updateChecker.status}\nMods: $mods\n" +
            "Contains local positions, timings and mod versions. No chat, server address, account details or raw packet payloads are collected.\n\n" + log.snapshot()
    }
    private fun attach(stream: Connection? = mc.connection?.connection) {
        if (stream !== connected || mc.level !== world || mc.player !== sessionPlayer) {
            externalOwnership.bind(null, null, null)
            val count = chain.count
            epoch++; warpTimeouts.bind(stream); chain.clear(); observations.clear(); handling = null
            teleportIds.bind(stream, mc.level) // Clears on world-only changes too, with the same TCP connection.
            connected = stream; world = mc.level; sessionPlayer = mc.player; sentRotation = null
            cancelWaitingInputs("session_changed"); inputDispatchedThisTick = false
            queuedTickAim = null; visibleLook = null
            sentCollision = null
            movementInputs.reset()
            transmittedPosition.reset()
            event("SESSION epoch=$epoch discarded=$count connected=${stream != null}")
        }
    }
    private fun identity(p: Pending) = mc.connection?.connection === p.stream && mc.player === p.player && mc.level === p.level && p.stream.isConnected
    private fun saveSettings(): Boolean = try {
        settingsStore.save(ZpbwSettings(mode == Mode.REPLAY, NoSneakDelay.enabled, timeoutTicks, firstInstall)); true
    } catch (failure: Exception) {
        configErrors++; event("CONFIG_WRITE_FAILED type=${failure.javaClass.simpleName}"); false
    }
    private fun matches(source: PositionMoveRotation, target: Vec3, actual: PositionMoveRotation, packet: ClientboundPlayerPositionPacket) =
        ConfirmationPolicy.inspect(source, target, actual, packet.relatives()).canReplay
    private fun recover(reason: String, restore: Boolean = true, actual: Vec3? = null, positionConfirmed: Boolean = false, timeout: Int? = null) {
        if (chain.count > 0) cancelWaitingInputs(reason)
        inputDispatchedThisTick = false
        queuedTickAim = null
        movementInputs.clearRebase()
        warpTimeouts.clear()
        val p = chain.head?.value ?: return
        val count = chain.count
        val cancelActions = chain.hasActions
        val held = chain.clear()
        if (positionConfirmed) motionCorrections++ else failed++
        epoch++
        recoveringUntil = System.nanoTime() + 1_000_000_000
        event("${if (positionConfirmed) "MOTION_RECONCILED" else "FALLBACK"} reason=$reason cancelled=$count retained=${held.size} restore=$restore guess=${xyz(p.target)} actual=${actual?.let(::xyz) ?: "unavailable"} distance=${actual?.distanceTo(p.target) ?: "unknown"}")
        if (!identity(p)) return // Never send old world/connection objects to a successor session.
        if (restore) {
            val yaw = p.player.yRot; val pitch = p.player.xRot
            p.sourcePose.restore(p.player)
            // Roll back physics, not the user's new aim. Especially important when the next real
            // use is invalid: restoring an older camera could accidentally make it a valid warp.
            p.player.yRot = yaw; p.player.xRot = pitch
            rebaseSender(p.player, forceLook = true)
        }
        forwardEnvelope(p.stream, held, "fallback", cancelActions)
        if (restore) {
            // A timeout can roll back to the exact last transmitted position. Forcing a
            // heartbeat there invents a redundant position report (Grim BadPacketsV).
            // Include the original held TickEnds above, then restore the real sender history.
            val sender = p.player as ZpbwPlayerAccessor
            transmittedPosition.position?.let {
                sender.`zpbw$setXLast`(it.x); sender.`zpbw$setYLast`(it.y); sender.`zpbw$setZLast`(it.z)
            }
            sender.`zpbw$setPositionReminder`(transmittedPosition.reminder)
        }
        // Local chat only: no outgoing chat packet and no rate limit hiding an individual failure.
        mc.gui.chatListener().handleSystemMessage(if (timeout != null) ZpbwMessages.timeout(timeout)
            else ZpbwMessages.failure(p.target.point(), actual?.point(), reason, positionConfirmed), false)
    }
    private fun prepareReplayLook(player: LocalPlayer) {
        // A genuine absolute teleport can return the previous server look. The first natural
        // speculative movement must carry its own look, rather than inherit the native ACK's.
        // This only dirties vanilla's sender cache; no movement packet is constructed here.
        (player as ZpbwPlayerAccessor).`zpbw$setYRotLast`(Math.nextUp(player.yRot))
    }
    private fun rebaseSender(player: LocalPlayer, forceLook: Boolean = false) {
        val sender = player as ZpbwPlayerAccessor
        sender.`zpbw$setXLast`(player.x); sender.`zpbw$setYLast`(player.y); sender.`zpbw$setZLast`(player.z)
        // Recovery may have discarded look changes. Compare with the last transmitted look;
        // forcing a duplicate when the user never turned is invalid (AimDuplicateLook).
        sender.`zpbw$setYRotLast`(if (forceLook) sentRotation?.first ?: player.yRot else player.yRot)
        sender.`zpbw$setXRotLast`(if (forceLook) sentRotation?.second ?: player.xRot else player.xRot)
        sender.`zpbw$setLastOnGround`(player.onGround()); sender.`zpbw$setLastHorizontalCollision`(player.horizontalCollision)
        sender.`zpbw$setPositionReminder`(0)
        event("SENDER_REBASED pose=${xyz(player.position())}")
    }
    private fun forwardEnvelope(stream: Connection, held: List<Held>, reason: String, cancelWorldActions: Boolean = false) {
        bypass = true
        try { for (entry in held) if (entry is Held.Wire) {
            val original = entry.packet
            if (original !is ServerboundMovePlayerPacket &&
                !(cancelWorldActions && FastActionPolicy.worldAction(original))) stream.send(original)
        } }
        finally { bypass = false }
        if (cancelWorldActions) event("FAST_ACTIONS_CANCELLED count=${held.count { it is Held.Action || (it is Held.Wire && FastActionPolicy.worldAction(it.packet)) }} reason=$reason")
        if (held.isNotEmpty()) event("COALESCED reason=$reason droppedMovement=${held.count { it is Held.Wire && it.packet is ServerboundMovePlayerPacket }} originalEnvelope=${held.count { it is Held.Wire && it.packet !is ServerboundMovePlayerPacket }}")
    }
    @JvmStatic fun admitted(connection: Connection, packet: Packet<*>) {
        if (connection !== connected) return
        if (packet is ServerboundUseItemPacket || packet is ServerboundMovePlayerPacket ||
            packet is ServerboundAcceptTeleportationPacket || packet is ServerboundClientTickEndPacket ||
            packet is ServerboundPlayerInputPacket || packet is ServerboundPlayerCommandPacket)
            log.record("NETTY_ADMITTED ${kind(packet)}") // Admission, not socket delivery/server acceptance.
    }
    private fun kind(packet: Packet<*>): String = when (packet) {
        is ServerboundUseItemPacket -> "Use sequence=${packet.sequence}"
        is ServerboundAcceptTeleportationPacket -> "Accept id=${packet.id}"
        is ServerboundMovePlayerPacket -> "${packet.javaClass.simpleName} xyz=${packet.getX(Double.NaN)},${packet.getY(Double.NaN)},${packet.getZ(Double.NaN)} yaw=${packet.getYRot(Float.NaN)} pitch=${packet.getXRot(Float.NaN)} ground=${packet.isOnGround} horizontal=${packet.horizontalCollision()}"
        else -> packet.javaClass.simpleName
    }
    private fun xyz(value: Vec3) = "${value.x},${value.y},${value.z}"
    private fun Vec3.point() = PredictionFailureSupport.Point(x, y, z)
    private fun notice(message: net.minecraft.network.chat.Component) {
        val now = System.nanoTime()
        if (now - lastNotice > 1_000_000_000) {
            lastNotice = now
            mc.gui.chatListener().handleSystemMessage(message, false)
        }
    }
    @Synchronized private fun event(text: String) {
        if (events.size == 128) events.removeFirst()
        events.addLast("${System.nanoTime()} $text")
        log.record(text)
    }
}
