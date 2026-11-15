package com.resyst.vk.core

import kotlin.math.abs

/**
 * r10 (UX-4): what a finger doing on the space bar means, before the long press fires.
 * A quick, mostly vertical flick switches the language ES ⇄ EN inside Resyst; a mostly
 * horizontal drag moves the cursor (r2). The axes never steal each other (FL2): a flick needs
 * |dy| > 2·|dx|, a cursor drag needs |dx| > |dy|. Distances in dp, time in ms.
 *
 * r13f (SG1/SG2, live session s4): both gestures must be deliberate. A space tap's finger drifts: in s4, 4 taps
 * drifted 15-26 dp and were taken as a flick or a drag (the space was lost, the language changed), while the owner's
 * deliberate swipes went 64-114 dp; every space tap of s1-s4 lasted ≤ 153 ms. So a flick needs [FLICK_DP] (40 dp,
 * was 20), and a cursor drag either [CURSOR_DP] (24 dp, was 14) or, once the finger has been down [CURSOR_HOLD_MS],
 * [CURSOR_HOLD_DP] (one cursor step: hold, then drag).
 */
object SpaceGesture {
    enum class Kind { NONE, CURSOR, FLICK }

    const val FLICK_DP = 40f
    const val FLICK_MAX_MS = 300L
    const val CURSOR_DP = 24f
    const val CURSOR_HOLD_MS = 200L
    const val CURSOR_HOLD_DP = 9f

    /** [dxDp] / [dyDp]: the finger now relative to where it landed; [elapsedMs]: since it landed. */
    fun classify(dxDp: Float, dyDp: Float, elapsedMs: Long): Kind {
        val ax = abs(dxDp)
        val ay = abs(dyDp)
        return when {
            ay >= FLICK_DP && ay > 2 * ax && elapsedMs <= FLICK_MAX_MS -> Kind.FLICK
            ax > ay && (ax >= CURSOR_DP || (elapsedMs >= CURSOR_HOLD_MS && ax >= CURSOR_HOLD_DP)) -> Kind.CURSOR
            else -> Kind.NONE
        }
    }
}
