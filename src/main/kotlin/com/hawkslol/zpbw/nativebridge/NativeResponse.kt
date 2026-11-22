package com.hawkslol.zpbw.nativebridge

import net.minecraft.world.phys.Vec3

/** Semantic steps from a genuine vanilla handler, not independently created packets. */
enum class NativeResponseStep { ACCEPT_ID, POSITION_ROTATION, COMBINED_ACCEPT_POSITION_ROTATION }

data class NativeResponse(val step: NativeResponseStep, val id: Int? = null,
                          val position: Vec3? = null, val yaw: Float? = null, val pitch: Float? = null,
                          val wireCollision: Pair<Boolean, Boolean>? = null)
