package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeometrySynthTest {

    // G2 — default variant sits over the key when there is room
    @Test fun defaultVariantIsOverTheKey() {
        val p = PopupGeometry.variants(keyLeft = 100f, keyWidth = 40f, count = 5, cellWidth = 40f, viewWidth = 400f, margin = 4f)
        assertEquals(120f, p.cellCenter(0), 0.01f)
    }

    // G1 — clamps inside the view on both edges, default still nearest the key
    @Test fun popupStaysInsideTheView() {
        for (left in listOf(0f, 20f, 180f, 330f, 360f)) for (n in 1..9) {
            val p = PopupGeometry.variants(left, 40f, n, 40f, 400f, 4f)
            assertTrue("left $left n $n", p.left >= 4f - 0.01f)
            assertTrue("right $left n $n", p.left + p.width <= 396f + 0.01f)
        }
        val right = PopupGeometry.variants(360f, 40f, 4, 40f, 400f, 4f)
        assertEquals(376f, right.cellCenter(0), 0.01f)
    }

    // G3
    @Test fun indexIsClamped() {
        val p = PopupGeometry.variants(100f, 40f, 3, 40f, 400f, 4f)
        assertEquals(0, p.indexAt(-1000f))
        assertEquals(0, p.indexAt(p.cellCenter(0)))
        assertEquals(2, p.indexAt(p.cellCenter(2)))
        assertTrue(p.indexAt(10_000f) in 0..2)
    }

    // G4
    @Test fun previewNeverAboveTheTop() {
        assertEquals(0f, PopupGeometry.previewTop(keyTop = 10f, previewHeight = 80f), 0.01f)
        assertEquals(120f, PopupGeometry.previewTop(keyTop = 200f, previewHeight = 80f), 0.01f)
    }

    // Y1 + Y2
    @Test fun synthBuffersAreSaneForEveryPack() {
        for (pack in SoundPack.values()) for (kind in SoundKind.values()) {
            val pcm = KeySynth.render(pack, kind, seed = 7)
            assertTrue("$pack $kind empty", pcm.isNotEmpty())
            val ms = pcm.size * 1000 / KeySynth.SAMPLE_RATE
            assertTrue("$pack $kind $ms ms", ms in 20..800)
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$pack $kind silent", peak > 500)
            assertTrue("$pack $kind clips", peak < Short.MAX_VALUE)
        }
    }

    // Y3
    @Test fun wavHeaderIsValid() {
        val pcm = KeySynth.render(SoundPack.CLICK, SoundKind.KEY, 1)
        val wav = KeySynth.wav(pcm)
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(wav.size - 8, bb.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, bb.getShort(22).toInt())
        assertEquals(KeySynth.SAMPLE_RATE, bb.getInt(24))
        assertEquals(pcm.size * 2, bb.getInt(40))
        assertEquals(44 + pcm.size * 2, wav.size)
    }
}
