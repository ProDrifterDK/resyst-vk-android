package com.resyst.vk.core

import kotlin.math.max
import kotlin.math.min

/**
 * Bottom space the keyboard must reserve so no key sits under the navigation bar
 * (3-button bar or the gesture pill). Mirrors how the framework sizes the IME's own
 * navigation bar frame: min(current, stable). A hidden bar (current = 0) reserves nothing;
 * a transient bar taller than the stable one never makes the keyboard jump.
 */
object NavInsets {
    fun bottomPadding(current: Int, stable: Int): Int = max(0, min(current, stable))

    /** Total view height: drawn content + the reserved strip (never negative, never twice). */
    fun totalHeight(contentPx: Int, navPaddingPx: Int): Int = max(0, contentPx) + max(0, navPaddingPx)
}
