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
    /** Floor for gesture navigation (mode 2): the system's keyboard-switch button (white globe)
     *  lives in the nav strip and is taller than the gesture pill — on a Pixel 6 @356dpi the
     *  pill inset is 53px but the globe reaches ~19px above the strip. Keys need the floor. */
    const val MIN_GESTURE_RESERVE = 88

    fun bottomPadding(current: Int, stable: Int): Int = max(0, min(current, stable))

    /**
     * Final reserve given the reported insets and the navigation mode
     * (2 = gesture navigation, per Settings.Secure.NAVIGATION_MODE).
     */
    fun bottomPadding(current: Int, stable: Int, navigationMode: Int): Int =
        bottomPadding(current, stable, navigationMode, systemGlobeHidden = false)

    /**
     * r7: once the keyboard hid the IME navigation bar (see [ImeSwitcher.systemGlobeHidden]) the
     * globe is gone, so the floor is no longer needed — only the gesture pill's own inset.
     */
    fun bottomPadding(current: Int, stable: Int, navigationMode: Int, systemGlobeHidden: Boolean): Int {
        val base = bottomPadding(current, stable)
        return if (navigationMode == 2 && !systemGlobeHidden) max(base, MIN_GESTURE_RESERVE) else base
    }

    /** Total view height: drawn content + the reserved strip (never negative, never twice). */
    fun totalHeight(contentPx: Int, navPaddingPx: Int): Int = max(0, contentPx) + max(0, navPaddingPx)
}
