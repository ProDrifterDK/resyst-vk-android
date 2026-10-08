package com.resyst.vk.core

import kotlin.math.abs

/**
 * r10 (UX-4): what a finger doing on the space bar means, before the long press fires.
 * A quick, mostly vertical flick switches the language ES ⇄ EN inside Resyst; a mostly
 * horizontal drag moves the cursor (r2). The axes never steal each other (FL2): a flick needs
 * |dy| > 2·|dx|, a cursor drag needs |dx| > |dy|. Distances in dp, time in ms.
 */
object SpaceGesture {
    enum class Kind { NONE, CURSOR, FLICK }

    const val FLICK_DP = 20f
    const val FLICK_MAX_MS = 300L
    const val CURSOR_DP = 14f

    fun classify(dxDp: Float, dyDp: Float, elapsedMs: Long): Kind {
        val ax = abs(dxDp)
        val ay = abs(dyDp)
        return when {
            ay >= FLICK_DP && ay > 2 * ax && elapsedMs <= FLICK_MAX_MS -> Kind.FLICK
            ax > CURSOR_DP && ax > ay -> Kind.CURSOR
            else -> Kind.NONE
        }
    }
}
