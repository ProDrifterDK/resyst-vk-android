package com.resyst.vk.ime

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.resyst.vk.core.HapticMechanism
import com.resyst.vk.core.HapticPulse
import com.resyst.vk.core.HapticRoute
import com.resyst.vk.core.HapticStrength
import com.resyst.vk.core.Haptics

/**
 * Drives the vibrator directly (VIBRATE permission) instead of View.performHapticFeedback,
 * which the system can suppress before it reaches the motor. Callers decide *whether* to
 * pulse via [Haptics.pulseFor]; this class only decides *how* ([Haptics.spec] × the motor's
 * capabilities, [Haptics.mechanism]).
 */
class HapticPlayer(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val available: Boolean get() = vibrator?.hasVibrator() == true

    /** Resolved once: capabilities don't change at runtime. */
    val mechanism: HapticMechanism by lazy {
        val v = vibrator
        val primitives = v != null && Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK,
        )
        Haptics.mechanism(primitives, v?.hasAmplitudeControl() == true)
    }

    fun play(pulse: HapticPulse, strength: HapticStrength) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = effectFor(pulse, strength)
            if (Build.VERSION.SDK_INT >= 33) {
                val usage = when (Haptics.route(systemTouchFeedbackOn())) {
                    HapticRoute.TOUCH -> VibrationAttributes.USAGE_TOUCH
                    HapticRoute.OTHER -> VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                }
                v.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                v.vibrate(effect)
            }
        }
    }

    private fun effectFor(pulse: HapticPulse, strength: HapticStrength): VibrationEffect {
        val spec = Haptics.spec(pulse, strength)
        return when (mechanism) {
            HapticMechanism.PRIMITIVE -> if (Build.VERSION.SDK_INT >= 30) {
                val primitive = if (pulse == HapticPulse.TICK) VibrationEffect.Composition.PRIMITIVE_TICK
                else VibrationEffect.Composition.PRIMITIVE_CLICK
                VibrationEffect.startComposition().addPrimitive(primitive, spec.scale).compose()
            } else {
                VibrationEffect.createOneShot(spec.durationMs, spec.amplitude)
            }
            HapticMechanism.AMPLITUDE -> VibrationEffect.createOneShot(spec.durationMs, spec.amplitude)
            HapticMechanism.DURATION -> VibrationEffect.createOneShot(spec.durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private fun systemTouchFeedbackOn(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}
