package com.resyst.vk.core

enum class HapticEvent { KEY, LONG_PRESS, CURSOR_TICK }

/** Abstract pulse; the IME maps it to a platform VibrationEffect. */
enum class HapticPulse { CLICK, HEAVY_CLICK, TICK }

/**
 * Which vibration usage carries the pulse. TOUCH is subject to the system "touch feedback"
 * switch; when the user turned that off, the pulse goes out with another usage so the in-app
 * toggle stays the only switch (K4).
 */
enum class HapticRoute { TOUCH, OTHER }

/**
 * The in-app "Vibración" toggle is the only switch: when it's on every event gets a pulse,
 * when it's off nothing does. Distinct pulses keep long-press and cursor ticks recognizable.
 */
object Haptics {
    fun pulseFor(event: HapticEvent, enabled: Boolean): HapticPulse? {
        if (!enabled) return null
        return when (event) {
            HapticEvent.KEY -> HapticPulse.CLICK
            HapticEvent.LONG_PRESS -> HapticPulse.HEAVY_CLICK
            HapticEvent.CURSOR_TICK -> HapticPulse.TICK
        }
    }

    fun route(systemTouchFeedbackOn: Boolean): HapticRoute =
        if (systemTouchFeedbackOn) HapticRoute.TOUCH else HapticRoute.OTHER
}
