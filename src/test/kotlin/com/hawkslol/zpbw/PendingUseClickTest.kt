package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.world.InteractionResult
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Structural contracts plus real vanilla result/bytecode checks; live click effects need the lab. */
class PendingUseClickTest {
    private val runtime get() = Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
    private val click get() = runtime.substringAfter("fun deferUseClick()").substringBefore("fun deferredUseInput()")
    @Test fun deferralIsBeforeVanillaClickAndDoesNotInventItsResultOrSwing() {
        val hook=Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwEntityClickMixin.java"))
        assertContains(hook,"method=\"startUseItem\",at=@At(\"HEAD\"),cancellable=true")
        assertContains(hook,"ZpbwRuntime.deferUseClick()) ci.cancel()")
        assertContains(click,"deferAction(if (warpClick) \"warp_click\" else \"use_click\")")
        assertEquals(1,Regex("`zpbw\\\$startUseItem`\\(\\)").findAll(click).count())
        assertFalse(click.contains("player.swing(")); assertFalse(click.contains("InteractionResult.SUCCESS"))
    }
    @Test fun ordinaryBlockClickReplaysBothHandsAndFallbackThroughUnmodifiedVanillaMethod() {
        val node=ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/client/Minecraft.class").use {
            assertNotNull(it); ClassReader(it).accept(node,0)
        }
        val vanilla=node.methods.single { it.name=="startUseItem" && it.desc=="()V" }
        val calls=vanilla.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertTrue(calls.any { it.owner=="net/minecraft/world/InteractionHand" && it.name=="values" })
        val block=calls.indexOfFirst { it.name=="useItemOn" }
        val item=calls.indexOfFirst { it.name=="useItem" }
        assertTrue(block>=0 && item>block)
        assertTrue(calls.any { it.name=="swingSource" })
        assertTrue(calls.any { it.name=="swing" })
        assertContains(click,"input.`zpbw\$startUseItem`()")
    }
    @Test fun oldLowerLevelDeferralsCannotCreateAnotherSyntheticSwing() {
        val mixin=Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwUseMixin.java"))
        assertFalse(mixin.contains("InteractionResult.SUCCESS"))
        assertContains(mixin,"InteractionResult.CONSUME")
        assertEquals(InteractionResult.SwingSource.NONE,InteractionResult.CONSUME.swingSource())
        assertTrue(InteractionResult.CONSUME.consumesAction())
    }
    @Test fun bothStacksBlockStateReachAndScreenAreRevalidatedBeforeReplay() {
        for (guard in listOf("mc.gui.screen() != null","mc.gameMode !== game","player.isHandsBusy","game.isDestroying",
            "ItemStack.isSameItemSameComponents(main, player.mainHandItem)",
            "ItemStack.isSameItemSameComponents(off, player.offhandItem)",
            "player.isWithinBlockInteractionRange(block.blockPos, 0.0)","level.getBlockState(block.blockPos) != state"))
            assertContains(click,guard)
        assertTrue(click.indexOf("USE_CLICK_CANCELLED") < click.indexOf("input.`zpbw\$startUseItem`()"))
    }
    @Test fun clickRestoresCrosshairInputAndExistingRepeatDelayEvenOnFailure() {
        val finallyBlock=click.substringAfter("} finally {")
        for (restore in listOf("replayingUseClick = false; replayUseInput = null", "player.input.keyPresses = advancedInput",
            "input.`zpbw\$setHitResult`(advancedHit)","mc.crosshairPickEntity = advancedEntity",
            "input.`zpbw\$setRightClickDelay`(delay)")) assertContains(finallyBlock,restore)
        assertContains(click,"if (queued) input.`zpbw\$setRightClickDelay`(4)")
        val nsd=Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/NoSneakDelay.kt"))
        assertContains(nsd,"ZpbwRuntime.deferredUseInput() ?: latch.value")
        assertContains(nsd,"fun sneakForPrediction(): Boolean? = inputForClick()?.shift()")
        assertContains(click,"val clickedInput = NoSneakDelay.inputForClick() ?: player.input.keyPresses")
    }
    @Test fun validArmedBlockClicksReachTheSameFiveWarpItemUsePath() {
        assertFalse(click.contains("(block == null || routed)"), "A valid close block click must not occupy the action journal before its Etherwarp use")
        assertContains(click,"if (warpClick && !chain.hasConfirmedPrefix && waitingInputs.isEmpty() && !inputDispatchedThisTick) return false")
        assertContains(click,"chain.tail?.value?.target")
        assertTrue(click.indexOf("if (warpClick && !chain.hasConfirmedPrefix") < click.indexOf("val queued = deferAction("))
        assertContains(runtime,"if (mode == Mode.REPLAY && !chain.canAdd)")
        val route = runtime.substringAfter("fun redirectShovelBlockUse(").substringBefore("fun beforeUse(")
        assertContains(route, "(chain.count > 0 || sneaking) && EtherwarpPredictor.qualifiesForBlockUse")
        assertContains(route, "if (!shovelRoute && !warpRoute) return null")
        assertFalse(route.contains("val warpRoute = chain.count > 0"), "A valid first warp uses the same native ability path")
        assertContains(route, "ShovelBlockUsePolicy.consumeRoutedUse { game.useItem(player, hand) }")
        assertFalse(route.contains("Thread.sleep"))
        assertEquals(5,ChainLedger<String,String>().maximum)
    }
    @Test fun packetAbilitiesBehaviorIsNotChangedByThisRepair() {
        assertFalse(runtime.contains("is ServerboundPlayerAbilitiesPacket"))
        assertContains(runtime,"recover(\"unsupported_outgoing_\${packet.javaClass.simpleName}\")")
    }
}
