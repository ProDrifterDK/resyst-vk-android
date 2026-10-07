package com.resyst.vk.core

import kotlin.math.max
import kotlin.math.min

/**
 * Bottom space the keyboard must reserve so no key sits under the navigation bar
 * (3-button bar, gesture pill, or the system's IME navigation bar with back + globe).
 *
 * r8 model (measured on API 34/35 emulators + Pixel 6 API 37, reports/r8-android.report.md):
 * - base = min(current, stable) of the ROOT nav insets = how much of the nav bar lies inside our
 *   window. A hidden bar (current = 0) reserves nothing; a transient bar taller than the stable
 *   one never makes the keyboard jump.
 * - base = 0 means the framework already keeps its own strip below our view (API ≤ 34 draws the
 *   IME nav bar — back + keyboard icon — in the window's decor, outside the input view). Nothing
 *   may be added then: the r5 88 px gesture floor did, leaving a ~106 px empty band above the
 *   system strip (API 34, gesture nav).
 * - The IME navigation bar reports itself as captionBar insets (API 35+). Visible → it is drawn
 *   over our bottom strip: reserve its full height. Hidden by us (r7) → the globe is gone, base
 *   alone.
 * - No caption insets + gesture nav + a real in-window inset: the globe can't be hidden and
 *   reaches above the pill (Pixel 6 r5: pill 53 px, globe ~19 px higher) → floor at
 *   [MIN_GESTURE_RESERVE].
 * - A reserve that changes after the window was sized needs a fresh layout pass (see
 *   KeyboardView.applyNavPadding): applied inside the insets pass it was measured against the OLD
 *   window height and the window never grew (API 35 emulator, 3-button: window 829 px, view
 *   955 px, the 126 px strip clipped, nav buttons drawn over the space row).
 */
object NavInsets {
    /** Gesture-nav floor when the globe is drawn over our window and can't be hidden. */
    const val MIN_GESTURE_RESERVE = 88

    fun bottomPadding(current: Int, stable: Int): Int = max(0, min(current, stable))

    /** Reserve without caption information (navigationMode 2 = gesture, Settings.Secure.NAVIGATION_MODE). */
    fun bottomPadding(current: Int, stable: Int, navigationMode: Int): Int =
        bottomPadding(current, stable, navigationMode, captionHeight = 0, captionVisible = false)

    /** r7 overload: [systemGlobeHidden] = the IME nav bar exists and our hide request was honored. */
    fun bottomPadding(current: Int, stable: Int, navigationMode: Int, systemGlobeHidden: Boolean): Int =
        if (systemGlobeHidden) bottomPadding(current, stable) else bottomPadding(current, stable, navigationMode)

    /** The r8 rule (see the object doc). [captionHeight] / [captionVisible] = our window's captionBar insets. */
    fun bottomPadding(current: Int, stable: Int, navigationMode: Int, captionHeight: Int, captionVisible: Boolean): Int {
        val base = bottomPadding(current, stable)
        return when {
            captionHeight > 0 && captionVisible -> max(base, captionHeight)
            captionHeight > 0 -> base
            navigationMode == 2 && base > 0 -> max(base, MIN_GESTURE_RESERVE)
            else -> base
        }
    }

    /**
     * Double-reserve guard: how much of a nav bar [navHeight] px tall at the bottom of a
     * [screenHeight] px screen our window (ending at [windowBottom], screen coordinates) really
     * covers. A window the system already placed above its own nav strip covers 0, whatever
     * the insets claim; null = geometry unknown (not laid out yet) → trust the insets.
     */
    fun overlap(windowBottom: Int, screenHeight: Int, navHeight: Int): Int? {
        if (windowBottom <= 0 || screenHeight <= 0 || navHeight <= 0) return null
        return (windowBottom - (screenHeight - navHeight)).coerceIn(0, navHeight)
    }

    /**
     * [reserve] limited to the measured [overlap] (when known): never reserve a strip we don't
     * cover. A window ending exactly where the nav bar starts (overlap 0) already sits above it
     * — the insets still report the bar, and reserving it again is User 1's empty band. A
     * partial overlap keeps the reserve: the gesture floor exists for a globe drawn ABOVE the
     * strip, which the overlap can't see.
     */
    fun clampToOverlap(reserve: Int, overlap: Int?, captionVisible: Boolean): Int = when {
        overlap == null || captionVisible -> reserve // the visible IME nav bar is drawn inside our window
        overlap == 0 -> 0
        else -> reserve
    }

    /** The whole decision for one insets pass (what KeyboardView applies). */
    fun reserve(
        current: Int, stable: Int, navigationMode: Int, captionHeight: Int, captionVisible: Boolean,
        windowBottom: Int, screenHeight: Int,
    ): Int {
        val raw = bottomPadding(current, stable, navigationMode, captionHeight, captionVisible)
        val captionDrawn = captionHeight > 0 && captionVisible
        return clampToOverlap(raw, overlap(windowBottom, screenHeight, max(0, stable)), captionDrawn)
    }

    /** Total view height: drawn content + the reserved strip (never negative, never twice). */
    fun totalHeight(contentPx: Int, navPaddingPx: Int): Int = max(0, contentPx) + max(0, navPaddingPx)
}
