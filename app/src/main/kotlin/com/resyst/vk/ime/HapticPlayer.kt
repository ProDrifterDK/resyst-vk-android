package com.resyst.vk.ime

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.resyst.vk.core.HapticPulse
import com.resyst.vk.core.HapticRoute
import com.resyst.vk.core.Haptics

/**
 * Drives the vibrator directly (VIBRATE permission) instead of View.performHapticFeedback,
 * which the system can suppress before it reaches the motor. Callers decide *whether* to
 * pulse via [Haptics.pulseFor]; this class only decides *how*.
 */
class HapticPlayer(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val available: Boolean get() = vibrator?.hasVibrator() == true

    fun play(pulse: HapticPulse) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = effectFor(pulse)
        runCatching {
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

    private fun effectFor(pulse: HapticPulse): VibrationEffect = if (Build.VERSION.SDK_INT >= 29) {
        VibrationEffect.createPredefined(
            when (pulse) {
                HapticPulse.CLICK -> VibrationEffect.EFFECT_CLICK
                HapticPulse.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
                HapticPulse.TICK -> VibrationEffect.EFFECT_TICK
            },
        )
    } else {
        val (ms, amp) = when (pulse) {
            HapticPulse.CLICK -> 14L to 150
            HapticPulse.HEAVY_CLICK -> 28L to 255
            HapticPulse.TICK -> 6L to 90
        }
        VibrationEffect.createOneShot(ms, amp)
    }

    private fun systemTouchFeedbackOn(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
}
