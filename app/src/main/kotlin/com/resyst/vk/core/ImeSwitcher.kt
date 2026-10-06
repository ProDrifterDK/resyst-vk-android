package com.resyst.vk.core

/**
 * The system keyboard-switch button (white globe) and how the keyboard replaces it (r7).
 *
 * On gesture navigation the framework draws an "IME navigation bar" (back chevron + globe)
 * inside the keyboard's own window, over its bottom strip — the r5 globe that sat right of the
 * keys and caught mistaps. Since API 35 that bar reports itself to the IME window as
 * `captionBar` insets and the IME may hide it with
 * `decorView.windowInsetsController.hide(WindowInsets.Type.captionBar())`
 * (AOSP `NavigationBarController`, FlorisBoard `ImeSystemUi`). On API 36+ the framework then
 * calls `InputMethodService.onCustomImeSwitcherButtonRequestedVisible(true)`: the IME must offer
 * an equivalent switch itself — here, long-press on the space bar (already the picker), with a
 * small hint drawn on the space bar. The keyboard never draws its own 🌐 key.
 */
object ImeSwitcher {
    /** First API where the IME navigation bar listens to captionBar visibility. */
    const val MIN_HIDE_SDK = 35

    /**
     * True when the system globe is really gone from the keyboard window: the platform supports
     * hiding, the IME navigation bar exists (caption height > 0) and it is not visible
     * (our hide request was honored). Anything else means the globe may still be drawn.
     */
    fun systemGlobeHidden(sdk: Int, captionHeight: Int, captionVisible: Boolean): Boolean =
        sdk >= MIN_HIDE_SDK && captionHeight > 0 && !captionVisible

    /** Show the switch hint on the space bar only if there is something to switch to. */
    fun spaceHint(systemGlobeHidden: Boolean, switchRequested: Boolean): Boolean =
        systemGlobeHidden && switchRequested
}
