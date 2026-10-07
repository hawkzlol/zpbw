package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

class MiningInputPolicyTest {
    @Test fun actualVanillaAttackMethodsKeepBlockActionAndSwingInTheSameInvocation() {
        val node = ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/client/Minecraft.class").use {
            assertNotNull(it); ClassReader(it).accept(node, 0)
        }
        for ((method, descriptor, blockAction) in listOf(
            Triple("startAttack", "()Z", "startDestroyBlock"),
            Triple("continueAttack", "(Z)V", "continueDestroyBlock"))) {
            val native = node.methods.single { it.name == method && it.desc == descriptor }
            val calls = native.instructions.toArray().filterIsInstance<MethodInsnNode>()
            val action = calls.indexOfFirst { it.owner == "net/minecraft/client/multiplayer/MultiPlayerGameMode" && it.name == blockAction }
            // startAttack also has an earlier, separately returning piercing-weapon branch.
            val swing = calls.indexOfLast { it.name == "swing" }
            assertTrue(action >= 0, "$method must invoke $blockAction")
            assertTrue(swing > action, "$method must generate its own swing after its block action")
        }
    }

    @Test fun completeNativeClicksAndLowLevelFallbackShareOneHeldMiningEntry() {
        val kinds = listOf("start_dig", "continue_dig", "block_attack_click", "block_continue_click")
        for (kind in kinds) {
            assertTrue(MiningInputPolicy.isMining(kind))
            assertTrue(MiningInputPolicy.coalesce(true, sequenceOf("use_click", kind, "warp_click")))
            assertFalse(MiningInputPolicy.coalesce(false, sequenceOf(kind)))
        }
        assertFalse(MiningInputPolicy.isMining("entity_attack_click"))
        assertFalse(MiningInputPolicy.coalesce(true, sequenceOf("entity_attack_click", "use_click")))
    }

    @Test fun nativeMiningOwnsItsSwingAndCompleteClickObserversAtFutureInputBoundary() {
        val runtime = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val click = runtime.substringAfter("@JvmStatic fun deferBlockAttack(").substringBefore("@JvmStatic fun deferMining(")
        assertContains(click, "dispatchingInput != null || !attackHeld")
        assertContains(click, "return queueInput(if (continuing) \"block_continue_click\" else \"block_attack_click\"")
        assertContains(click, "input.`zpbw\$continueAttack`(true)")
        assertContains(click, "input.`zpbw\$startAttack`()")
        assertFalse(click.contains("game.startDestroyBlock"))
        assertFalse(click.contains("game.continueDestroyBlock"))
        assertFalse(click.contains("ServerboundSwingPacket"))
        assertFalse(click.contains("player.swing("))
        assertTrue(click.indexOf("input.`zpbw\$pick`(1.0f)") < click.indexOf("input.`zpbw\$startAttack`()"))
        val mixin = Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwEntityClickMixin.java"))
        assertContains(mixin, "@Inject(method=\"startAttack\",at=@At(\"HEAD\"),cancellable=true)")
        assertContains(mixin, "ZpbwRuntime.deferBlockAttack(false, true)")
        assertContains(mixin, "@Inject(method=\"continueAttack\",at=@At(\"HEAD\"),cancellable=true)")
        assertContains(mixin, "if (ZpbwRuntime.deferBlockAttack(true, attackHeld)) ci.cancel();")
    }

    @Test fun heldAttackDoesNotQueueTickBacklogAheadOfChestAndNextWarp() {
        val inputs = ArrayDeque<String>()
        inputs.addLast("start_dig")
        repeat(30) {
            if (!MiningInputPolicy.coalesce(true, inputs.asSequence())) inputs.addLast("continue_dig")
        }
        inputs.addLast("use_click"); inputs.addLast("warp_click")
        repeat(30) {
            if (!MiningInputPolicy.coalesce(true, inputs.asSequence())) inputs.addLast("continue_dig")
        }
        assertEquals(listOf("start_dig", "use_click", "warp_click"), inputs.toList())
        inputs.removeFirst()
        assertFalse(MiningInputPolicy.coalesce(true, inputs.asSequence()))
    }

    @Test fun continuationWithoutAnInitialTapHasOnlyOneQueueEntry() {
        val inputs = ArrayDeque<String>()
        repeat(25) {
            if (!MiningInputPolicy.coalesce(true, inputs.asSequence())) inputs.addLast("continue_dig")
        }
        assertEquals(listOf("continue_dig"), inputs.toList())
        assertFalse(MiningInputPolicy.coalesce(false, inputs.asSequence()), "A separate pressed tap retains its place")
    }

    @Test fun releasedTapStillExecutesButReleasedOrRetargetedHoldDoesNotContinue() {
        val gate = MiningReplayGate(); val world = Any(); var calls = 0
        assertTrue(gate.replay(world, 1, false, true, false) { calls++ })
        for (held in listOf(false, true)) for (hit in listOf(false, true)) for (native in listOf(false, true)) {
            val valid = MiningInputPolicy.canContinue(held, hit, native)
            assertEquals(held && hit && native, valid)
        }
        assertFalse(gate.replay(world, 2, true, true,
            MiningInputPolicy.canContinue(false, true, true)) { calls++ })
        assertFalse(gate.replay(world, 2, true, true,
            MiningInputPolicy.canContinue(true, false, true)) { calls++ })
        assertFalse(gate.replay(world, 2, true, true,
            MiningInputPolicy.canContinue(true, true, false)) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun supportRemovalOccursAfterHistoricalSupportedMovementAndBothNativeResponses() {
        val ledger = ChainLedger<String, Boolean>()
        val events = mutableListOf<String>()
        var supportExists = true
        ledger.add(1, "warp"); ledger.cut(1)
        repeat(6) { ledger.retain(supportExists) }
        val nextNativeInput = Runnable { supportExists = false; events += "mine" }
        ledger.genuine(1, 7); ledger.response(7, true)
        assertFailsWith<IllegalStateException> { ledger.settle(1, 7) }
        assertTrue(supportExists)
        ledger.response(7, false)
        ledger.settle(1, 7).forEach { claimedGround ->
            assertEquals(supportExists, claimedGround)
            events += "supported movement"
        }
        assertEquals(0, ledger.count)
        nextNativeInput.run()
        events += if (supportExists) "supported physics" else "falling physics"
        assertEquals(List(6) { "supported movement" } + listOf("mine", "falling physics"), events)
    }

    @Test fun miningUsesFutureInputQueueAndCurrentReachInsteadOfCapturedPoseReplay() {
        val runtime = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val mining = runtime.substringAfter("@JvmStatic fun deferMining(").substringBefore("@JvmStatic fun deferAction(")
        assertContains(mining, "return queueInput(")
        assertFalse(mining.contains("return deferAction("))
        assertFalse(mining.contains("pose.restore("))
        assertContains(mining, "player.isWithinBlockInteractionRange(target, 0.0)")
        assertContains(mining, "world.getBlockState(target) == expected")
        assertContains(mining, "MiningInputPolicy.canContinue(mc.options.keyAttack.isDown")
        assertContains(mining, "if (chain.count == 0 && waitingInputs.isEmpty())")
        assertContains(mining, "return continuing && miningReplay.continuedAt(world, world.gameTime)")
        val dispatch = runtime.substringAfter("@JvmStatic fun dispatchWaitingInput()").substringBefore("@JvmStatic fun finishInputDispatchTick()")
        assertContains(dispatch, "if (chain.count != 0")
        assertContains(dispatch, "MiningInputPolicy.isMining(next.name)")
        assertContains(dispatch, "ItemStack.isSameItemSameComponents(next.item, player.inventory.getItem(next.slot))")
    }
}
