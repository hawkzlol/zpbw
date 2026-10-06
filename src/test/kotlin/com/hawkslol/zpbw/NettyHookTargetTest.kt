package com.hawkslol.zpbw

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.*

/** Checks the actual 26.2 bytecode, not a differently versioned Odin source signature. */
class NettyHookTargetTest {
    @Test fun mappedConnectionContainsExactlyOnePacketDispatchAtTheInjectedBoundary() {
        val node=ClassNode()
        javaClass.classLoader.getResourceAsStream("net/minecraft/network/Connection.class").use {
            assertNotNull(it); ClassReader(it).accept(node,0)
        }
        val inbound=node.methods.single { it.name=="channelRead0" &&
            it.desc=="(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V" }
        val dispatch=inbound.instructions.toArray().filterIsInstance<MethodInsnNode>().filter {
            it.owner=="net/minecraft/network/Connection" && it.name=="genericsFtw" &&
                it.desc=="(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"
        }
        assertEquals(1,dispatch.size)
        assertEquals(1,node.methods.count { it.name=="channelInactive" && it.desc=="(Lio/netty/channel/ChannelHandlerContext;)V" })
    }
}
