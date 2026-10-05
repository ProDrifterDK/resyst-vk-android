package com.resyst.vk.core

enum class HapticEvent { KEY, LONG_PRESS, CURSOR_TICK }

/** Abstract pulse; the IME maps it to a platform VibrationEffect. */
enum class HapticPulse { CLICK, HEAVY_CLICK, TICK }

/** User-chosen vibration strength ("Intensidad": Suave / Media / Fuerte). */
enum class HapticStrength { LOW, MEDIUM, HIGH }

/**
 * Which vibration usage carries the pulse. TOUCH is subject to the system "touch feedback"
 * switch; when the user turned that off, the pulse goes out with another usage so the in-app
 * toggle stays the only switch (K4).
 */
enum class HapticRoute { TOUCH, OTHER }

/**
 * How the motor is driven, best first (K10):
 * PRIMITIVE — composed click/tick primitives scaled by [HapticSpec.scale] (tuned, crisp on LRAs);
 * AMPLITUDE — one-shot with [HapticSpec.amplitude]; DURATION — one-shot at the default
 * amplitude, so on motors without amplitude control the level still changes the length.
 */
enum class HapticMechanism { PRIMITIVE, AMPLITUDE, DURATION }

/** One pulse at one strength: [amplitude] in 1..255 (platform range), [durationMs] > 0. */
data class HapticSpec(val durationMs: Long, val amplitude: Int) {
    /** Primitive scale in (0, 1]. */
    val scale: Float get() = amplitude / 255f
}

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

    /**
     * pulse × strength → (duration, amplitude). A table, not base × factor: MEDIUM keeps the
     * classic CLICK 160 / TICK 80, but HEAVY sits at 210 so HIGH still has headroom below the
     * 255 ceiling (K6). Within a level HEAVY > CLICK > TICK in both columns (K8); across levels
     * both columns strictly grow (K6, K7: duration is the knob when amplitude is ignored).
     */
    fun spec(pulse: HapticPulse, strength: HapticStrength): HapticSpec = when (pulse) {
        HapticPulse.TICK -> when (strength) {
            HapticStrength.LOW -> HapticSpec(6, 50)
            HapticStrength.MEDIUM -> HapticSpec(8, 80)
            HapticStrength.HIGH -> HapticSpec(10, 140)
        }
        HapticPulse.CLICK -> when (strength) {
            HapticStrength.LOW -> HapticSpec(10, 90)
            HapticStrength.MEDIUM -> HapticSpec(14, 160)
            HapticStrength.HIGH -> HapticSpec(18, 230)
        }
        HapticPulse.HEAVY_CLICK -> when (strength) {
            HapticStrength.LOW -> HapticSpec(16, 130)
            HapticStrength.MEDIUM -> HapticSpec(22, 210)
            HapticStrength.HIGH -> HapticSpec(28, 255)
        }
    }

    fun mechanism(primitivesSupported: Boolean, amplitudeControl: Boolean): HapticMechanism = when {
        primitivesSupported -> HapticMechanism.PRIMITIVE
        amplitudeControl -> HapticMechanism.AMPLITUDE
        else -> HapticMechanism.DURATION
    }
}
