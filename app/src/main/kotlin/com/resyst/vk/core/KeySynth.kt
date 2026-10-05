package com.resyst.vk.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Offline port of the desktop WebAudio key sounds: filtered noise bursts + swept tones with
 * exponential envelopes, rendered to 16-bit PCM once per pack (no audio assets shipped).
 */
object KeySynth {
    const val SAMPLE_RATE = 44_100

    private class Mix(seconds: Double) {
        val buf = FloatArray((seconds * SAMPLE_RATE).toInt() + 1)
    }

    private fun envAt(peak: Double, dur: Double, t: Double): Double =
        if (t < 0 || t > dur) 0.0 else peak * (0.001 / peak).pow(t / dur)

    private class Biquad(type: String, freq: Double, q: Double) {
        private val b0: Double; private val b1: Double; private val b2: Double
        private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        init {
            val w0 = 2 * PI * freq / SAMPLE_RATE
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val a0: Double
            val nb0: Double; val nb1: Double; val nb2: Double
            when (type) {
                "lowpass" -> { nb0 = (1 - c) / 2; nb1 = 1 - c; nb2 = (1 - c) / 2 }
                "highpass" -> { nb0 = (1 + c) / 2; nb1 = -(1 + c); nb2 = (1 + c) / 2 }
                else -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha } // bandpass, 0 dB peak
            }
            a0 = 1 + alpha
            b0 = nb0 / a0; b1 = nb1 / a0; b2 = nb2 / a0
            a1 = -2 * c / a0; a2 = (1 - alpha) / a0
        }

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun burst(m: Mix, rnd: Random, t0: Double, dur: Double, type: String, freq: Double, q: Double, peak: Double, shape: Double) {
        val n = (SAMPLE_RATE * dur).toInt().coerceAtLeast(1)
        val f = Biquad(type, freq, if (type == "bandpass") q else 0.7071)
        val start = (t0 * SAMPLE_RATE).toInt()
        for (i in 0 until n) {
            val noise = (rnd.nextDouble() * 2 - 1) * (1 - i.toDouble() / n).pow(shape)
            val y = f.step(noise) * envAt(peak, dur, i.toDouble() / SAMPLE_RATE)
            val j = start + i
            if (j < m.buf.size) m.buf[j] += y.toFloat()
        }
    }

    private fun tone(m: Mix, t0: Double, wave: String, f0: Double, f1: Double, peak: Double, dur: Double) {
        val n = (SAMPLE_RATE * (dur + 0.02)).toInt()
        val start = (t0 * SAMPLE_RATE).toInt()
        val sweep = dur * 0.9
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val f = if (t < sweep) f0 * (f1 / f0).pow(t / sweep) else f1
            phase += f / SAMPLE_RATE
            val p = phase - kotlin.math.floor(phase)
            val s = if (wave == "triangle") 1 - 4 * abs(p - 0.5) else sin(2 * PI * p)
            val j = start + i
            if (j < m.buf.size) m.buf[j] += (s * envAt(peak, dur, t)).toFloat()
        }
    }

    fun render(pack: SoundPack, kind: SoundKind, seed: Int = 0): ShortArray {
        val rnd = Random(seed)
        val m = Mix(0.72)
        var end: Double
        when (pack) {
            SoundPack.CLICK -> {
                val dur = if (kind == SoundKind.SPACE) 0.05 else 0.03
                burst(m, rnd, 0.0, dur, "bandpass",
                    if (kind == SoundKind.MOD) 900.0 else 2100 + rnd.nextDouble() * 500, 1.1,
                    if (kind == SoundKind.SPACE) 0.5 else 0.34, 2.2)
                tone(m, 0.0, "triangle", if (kind == SoundKind.MOD) 420.0 else 640.0,
                    if (kind == SoundKind.MOD) 300.0 else 430.0, 0.12, 0.03)
                end = dur + 0.03
            }
            SoundPack.THOCK -> {
                val big = kind == SoundKind.SPACE || kind == SoundKind.ENTER
                burst(m, rnd, 0.0, if (big) 0.07 else 0.05, "lowpass",
                    if (big) 520.0 else 700 + rnd.nextDouble() * 120, 0.8, 0.55, 3.0)
                val d = if (big) 0.08 else 0.06
                tone(m, 0.0, "sine", if (big) 120.0 else 165 + rnd.nextDouble() * 20, if (big) 70.0 else 95.0, 0.32, d)
                end = d + 0.03
            }
            SoundPack.TYPE -> {
                burst(m, rnd, 0.0, 0.025, "highpass", 2600.0, 0.7, 0.42, 4.0)
                burst(m, rnd, 0.004, 0.06, "bandpass", 3400 + rnd.nextDouble() * 300, 9.0, 0.16, 1.5)
                end = 0.07
                if (kind == SoundKind.ENTER) {
                    tone(m, 0.05, "sine", 2093.0, 2090.0, 0.18, 0.6)
                    tone(m, 0.05, "sine", 4186.0, 4180.0, 0.05, 0.35)
                    end = 0.67
                } else if (kind == SoundKind.SPACE) {
                    burst(m, rnd, 0.0, 0.07, "lowpass", 900.0, 0.8, 0.3, 2.0)
                    end = 0.075
                }
            }
            SoundPack.BUBBLE -> {
                val base = when (kind) {
                    SoundKind.MOD -> 260.0
                    SoundKind.SPACE -> 200.0
                    else -> 340 + rnd.nextDouble() * 80
                }
                val d = if (kind == SoundKind.SPACE) 0.09 else 0.06
                tone(m, 0.0, "sine", base, base * 2.6, 0.26, d)
                end = d + 0.025
            }
        }
        val n = (end * SAMPLE_RATE).toInt().coerceAtMost(m.buf.size)
        // gentle saturation keeps every sample strictly inside 16-bit range
        return ShortArray(n) { i -> (tanh(m.buf[i].toDouble() * 1.2) * 0.92 * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Mono 16-bit PCM → RIFF/WAVE bytes (what SoundPool loads). */
    fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + data); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(SAMPLE_RATE); bb.putInt(SAMPLE_RATE * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(data)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }
}
