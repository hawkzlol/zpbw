package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeResponseStep

/** Legacy pair regressions remain explicit; dedicated contract tests exercise both shapes. */
object TestResponseContracts {
    val PAIR = listOf(NativeResponseStep.ACCEPT_ID, NativeResponseStep.POSITION_ROTATION)
    val COMBINED = listOf(NativeResponseStep.COMBINED_ACCEPT_POSITION_ROTATION)
}
