package com.hawkslol.zpbw

import kotlin.test.assertTrue

/** Existing Ping/ownership regressions model ordinary native-tick/Ping progress.
 * Burst ordering and independent counter progression are tested separately in NativeTickTimeoutGraceTest.
 * These are model calls only; no Minecraft simulation or packets are produced. */
internal fun <S : Any> ServerTickTimeouts<S>.nativeTickThenPing(session: S, id: Int): List<ServerTickTimeouts.Expiry> {
    assertTrue(completedClientTick(session).isEmpty(), "Paced-Ping baseline must still await its next Ping")
    return ping(session, id)
}
