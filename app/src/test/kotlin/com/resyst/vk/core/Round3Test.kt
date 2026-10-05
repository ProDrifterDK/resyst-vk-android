package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 3 field feedback; ids map to docs/failure-modes.md. */
class Round3Test {

    // ── K: vibration strength ───────────────────────────────────────────
    private val levels = HapticStrength.values().toList() // LOW, MEDIUM, HIGH

    @Test fun everySpecIsPlayable() { // K5
        for (p in HapticPulse.values()) for (st in levels) {
            val s = Haptics.spec(p, st)
            assertTrue("$p/$st amp ${s.amplitude}", s.amplitude in 1..255)
            assertTrue("$p/$st ms ${s.durationMs}", s.durationMs > 0)
            assertTrue("$p/$st scale ${s.scale}", s.scale > 0f && s.scale <= 1f)
        }
    }

    @Test fun levelsStrictlyGrowInAmplitudeAndDuration() { // K6 + K7
        for (p in HapticPulse.values()) {
            val specs = levels.map { Haptics.spec(p, it) }
            for (i in 1 until specs.size) {
                assertTrue("$p amplitude ${specs.map { it.amplitude }}", specs[i].amplitude > specs[i - 1].amplitude)
                assertTrue("$p duration ${specs.map { it.durationMs }}", specs[i].durationMs > specs[i - 1].durationMs)
            }
        }
    }

    @Test fun mediumKeepsTheClassicBases() { // K6: MEDIUM ≈ r2 feel
        assertEquals(160, Haptics.spec(HapticPulse.CLICK, HapticStrength.MEDIUM).amplitude)
        assertEquals(80, Haptics.spec(HapticPulse.TICK, HapticStrength.MEDIUM).amplitude)
        assertEquals(255, Haptics.spec(HapticPulse.HEAVY_CLICK, HapticStrength.HIGH).amplitude)
    }

    @Test fun pulsesStayDistinctAtEveryLevel() { // K8
        for (st in levels) {
            val tick = Haptics.spec(HapticPulse.TICK, st)
            val click = Haptics.spec(HapticPulse.CLICK, st)
            val heavy = Haptics.spec(HapticPulse.HEAVY_CLICK, st)
            assertTrue("$st amplitude", tick.amplitude < click.amplitude && click.amplitude < heavy.amplitude)
            assertTrue("$st duration", tick.durationMs < click.durationMs && click.durationMs < heavy.durationMs)
        }
    }

    @Test fun strengthDefaultsToMediumAndRoundTrips() { // K9
        for (p in ProfileCodec.seed().profiles) assertEquals(p.id, HapticStrength.MEDIUM, p.settings.hapticStrength)
        // r2 storage (no key) and garbage both fall back to MEDIUM
        val r2 = ProfileCodec.encode(ProfileCodec.seed()).filterKeys { !it.endsWith(".hapticStrength") }
        assertEquals(HapticStrength.MEDIUM, ProfileCodec.decode(r2).byId("noche")!!.settings.hapticStrength)
        val junk = ProfileCodec.encode(ProfileCodec.seed()).toMutableMap<String, Any?>().apply { put("p.noche.hapticStrength", "MAX") }
        assertEquals(HapticStrength.MEDIUM, ProfileCodec.decode(junk).byId("noche")!!.settings.hapticStrength)
        // save → load, without leaking into another profile
        val st = ProfileCodec.seed().update("dia") { it.copy(hapticStrength = HapticStrength.HIGH) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(HapticStrength.HIGH, back.byId("dia")!!.settings.hapticStrength)
        assertEquals(HapticStrength.MEDIUM, back.byId("noche")!!.settings.hapticStrength)
    }

    @Test fun mechanismFollowsMotorCapabilities() { // K10
        assertEquals(HapticMechanism.PRIMITIVE, Haptics.mechanism(primitivesSupported = true, amplitudeControl = true))
        assertEquals(HapticMechanism.AMPLITUDE, Haptics.mechanism(primitivesSupported = false, amplitudeControl = true))
        assertEquals(HapticMechanism.DURATION, Haptics.mechanism(primitivesSupported = false, amplitudeControl = false))
    }
}
