package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Actual26.2/native application and compiled wrapper contracts; physics still needs live tests. */
class AuthoritativeMotionContractTest {
    private fun node(path:String)=ClassNode().also{result->
        javaClass.classLoader.getResourceAsStream(path).use{assertNotNull(it);ClassReader(it).accept(result,0)}
    }
    @Test fun bothWrappedNativeMotionApplicationsRunAfterTheActualMainThreadGuard() {
        val listener=node("net/minecraft/client/multiplayer/ClientPacketListener.class")
        for((name,owner,call) in listOf(
            Triple("handleSetEntityMotion","net/minecraft/world/entity/Entity","lerpMotion"),
            Triple("handleExplosion","java/util/Optional","ifPresent"))) {
            val method=listener.methods.single{it.name==name}
            val calls=method.instructions.toArray().filterIsInstance<MethodInsnNode>()
            val guards=calls.withIndex().filter{it.value.owner=="net/minecraft/network/protocol/PacketUtils" && it.value.name=="ensureRunningOnSameThread"}
            val native=calls.withIndex().filter{it.value.owner==owner && it.value.name==call}
            assertEquals(1,guards.size);assertEquals(1,native.size)
            assertTrue(native.single().index>guards.single().index)
            if(name=="handleExplosion") assertTrue(calls.any{it.name=="playerKnockback"})
        }
    }
    @Test fun compiledWrappersInvokeEachOriginalExactlyOnceAndAlwaysRunCleanup() {
        val mixin=node("com/hawkslol/zpbw/mixin/ZpbwAuthoritativeMotionMixin.class")
        for(name in listOf("zpbw\$nativeVelocity","zpbw\$nativeExplosion")) {
            val method=mixin.methods.single{it.name==name}
            val calls=method.instructions.toArray().filterIsInstance<MethodInsnNode>()
            assertEquals(1,calls.count{it.owner=="com/llamalad7/mixinextras/injector/wrapoperation/Operation" && it.name=="call"})
            assertTrue(method.tryCatchBlocks.any{it.type==null},"Native application must have finally cleanup")
            assertTrue(calls.any{it.name=="afterAuthoritativeMotion"})
            assertFalse(calls.any{it.name in listOf("send","aiStep","tick")})
        }
    }
    @Test fun authoritativeImpulseReplacesOnlyItsOwnLiveRecoveryAuthority() {
        val runtime=Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val begin=runtime.substringAfter("fun beforeAuthoritativeMotion(").substringBefore("fun afterAuthoritativeMotion(")
        assertContains(begin,"entity !== mc.player")
        assertContains(begin,"entries.all { it.cut && it.value.use != null }")
        assertContains(begin,"restartForRecovery(p.stream, entries.map { it.generation }, 20)")
        val end=runtime.substringAfter("fun afterAuthoritativeMotion(").substringBefore("fun beforeRecoveryPhysics(")
        assertContains(end,"token !== barrier")
        assertContains(end,"!identity(barrier.owner)")
        assertContains(end,"barrier.authority = Pose.of(barrier.owner.player)")
        assertContains(begin,"externalPredictionActive()");assertContains(end,"externalPredictionActive()")
        assertFalse(end.contains("deltaMovement ="),"Native impulse must not be zeroed or replaced")
    }
}
