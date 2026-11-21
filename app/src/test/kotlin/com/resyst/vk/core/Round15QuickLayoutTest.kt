package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * r15 (QP6–QP13 in docs/failure-modes.md): the quick-settings panel's geometry over every keyboard the app can draw and
 * more (heights 70-150 % on 4- and 5-row keyboards, widths 320-600 dp, font ×1 to ×1.3, Spanish and English, with and
 * without the update line, 4 temas and 6 temas with 18-character names). The text is measured with a conservative model
 * (0.6 em per character, lines 1.32 em): the phone's real font is checked by the panel's own report line in the E2E.
 */
class Round15QuickLayoutTest {
    private val dp = 2.625f
    private val es = TestTxt.ES
    private val en = TestTxt.EN

    private fun textW(s: String, sizeDp: Float) = s.length * 0.6f * sizeDp * dp
    private fun line(sizeDp: Float) = 1.32f * sizeDp * dp

    private fun input(t: Txt, wDp: Float, hDp: Float, ff: Float, temas: List<String>, update: Boolean, active: Int = 0): QuickLayout.Input {
        val l = line(13 * ff)
        val s = line(11 * ff)
        return QuickLayout.Input(
            left = 0f, top = 0f, width = wDp * dp, height = hDp * dp, dp = dp, tiles = 8, update = update,
            chipW = temas.map { (8 + 20 + 6 + 10) * dp + textW(it, 13 * ff) }, // QuickPanel: CHIP_PAD + SWATCH + SWATCH_GAP + CHIP_END
            personalizeW = (12 + 16 + 8 + 14) * dp + textW(t.get(com.resyst.vk.R.string.quick_personalize), 13 * ff), personalizeIconW = 48 * dp,
            backW = (12 + 21 + 14) * dp + textW(t.get(com.resyst.vk.R.string.quick_back), 14 * ff),
            titleW = textW(t.get(com.resyst.vk.R.string.quick_title), 11 * ff) * 1.12f,
            need = mapOf(
                QuickLayout.Tier.FULL to (5 + 30 + 4 + 1 + 5) * dp + l + s,
                QuickLayout.Tier.COMPACT to (5 + 20 + 4 + 1 + 5) * dp + l + s,
                QuickLayout.Tier.MINI to (4 + 16 + 2 + 4) * dp + l,
            ),
            activeChip = active,
        )
    }

    /** The panel's height for a keyboard of [rows] rows at [scale] (KeyboardView: strip 42 dp + rows × 54 dp × scale + 4 dp). */
    private fun panelH(rows: Int, scale: Float) = 42f + rows * 54f * scale + 4f

    private fun names(t: Txt) = listOf("tema_seed_noche", "tema_seed_dia", "tema_seed_juego", "tema_seed_escritura").map { t.need(it) }
    private val long6 = listOf("Trabajo de oficina", "Noche de domingo..", "Mañana tranquila", "Juegos con amigos", "Escritura de tesis", "Mi tema favorito!")

    private fun near(a: Float, b: Float) = abs(a - b) < 0.5f

    @Test fun qp7Qp8EveryArrangementFitsWithOneRhythm() {
        var configs = 0
        val places = HashMap<QuickLayout.Place, Int>()
        val tiers = HashMap<QuickLayout.Tier, Int>()
        for (t in listOf(es, en)) for (w in 320..600 step 10) for (rows in listOf(4, 5)) for (pct in 70..150 step 5)
            for (ff in listOf(1f, 1.15f, 1.3f)) for (temas in listOf(names(t), long6, emptyList())) for (upd in listOf(false, true)) {
                val hDp = panelH(rows, pct / 100f)
                val i = input(t, w.toFloat(), hDp, ff, temas, upd)
                val r = QuickLayout.layout(i)
                configs++
                places.merge(r.place, 1, Int::plus)
                tiers.merge(r.tier, 1, Int::plus)
                val where = "${t.lang} w=$w rows=$rows h=$pct% ff=$ff temas=${temas.size} upd=$upd → ${r.place}/${r.tier} tileH=${r.tileH / dp}"
                // QP4: a tile is a touch target
                assertEquals(where, 8, r.tiles.size)
                for (b in r.tiles) assertTrue(where, b.h >= Quick.MIN_TILE_DP * dp - 0.5f && b.w >= Quick.MIN_TILE_DP * dp - 0.5f)
                // QP8: one rhythm: margins, gaps
                val m = QuickLayout.MARGIN * dp
                val g = QuickLayout.GAP * dp
                assertTrue(where, near(r.tiles[0].l, m) && near(r.tiles[3].r, i.width - m))
                for (k in listOf(0, 1, 2, 4, 5, 6)) assertTrue(where, near(r.tiles[k + 1].l - r.tiles[k].r, g))
                assertTrue(where, near(r.tiles[4].t - r.tiles[0].b, g))
                // no overlap anywhere
                val all = r.tiles + listOfNotNull(r.update, if (r.place == QuickLayout.Place.ROW) r.row else null)
                for (a in all.indices) for (b in a + 1 until all.size) assertTrue("$where: ${all[a]} × ${all[b]}", !all[a].overlaps(all[b]))
                if (r.place == QuickLayout.Place.HEADER) assertTrue(where, r.row!!.l >= r.back.r)
                // within the app's range (80-130 %), and up to 150 %: the chosen tier fits and the grid ends one margin above the bottom
                if (pct >= 80) {
                    assertTrue(where, r.tileH >= (i.need[r.tier] ?: 0f) - 0.5f)
                    assertTrue(where, near(r.tiles[7].b, i.height - m))
                }
                // QP6: chips are whole, in order, and every one is reachable by scrolling
                if (temas.isNotEmpty()) {
                    assertTrue(where, r.place != QuickLayout.Place.NONE)
                    val row = r.row!!
                    val vp = r.viewport!!
                    for ((k, b) in r.chips.withIndex()) assertTrue(where, near(b.w, i.chipW[k]))
                    for (k in 1 until r.chips.size) assertTrue(where, near(r.chips[k].l - r.chips[k - 1].r, QuickLayout.CHIP_GAP * dp))
                    // «Personalizar» right after the chips when nothing scrolls (no hole), pinned at the row's end when they scroll
                    val p = r.personalize!!
                    assertTrue(where, p.l >= vp.r && p.r <= row.r + 0.5f && p.w >= 48 * dp - 0.5f)
                    if (r.maxScroll > 0f) assertTrue(where, near(p.r, row.r)) else assertTrue(where, near(p.l - r.chips.last().r, QuickLayout.CHIP_GAP * dp))
                    assertTrue(where, r.chips.last().r - r.maxScroll <= vp.r + 0.5f)
                    if (r.maxScroll <= 0f) assertTrue(where, r.chips.last().r <= vp.r + 0.5f)
                }
            }
        println("QP sweep: $configs configurations; places $places; tiers $tiers")
        assertTrue(configs > 20_000)
    }

    @Test fun qp6TheOwnersPanelShowsEveryNameWhole() {
        // the owner's Pixel 8 Pro: dark, 120 %, 4 temas, a monospace key font (≈ 0.6 em per character, the model here);
        // his screenshot's geometry is ~458 dp wide
        for (t in listOf(es, en)) for (rows in listOf(4, 5)) {
            val r = QuickLayout.layout(input(t, 458f, panelH(rows, 1.2f), 1f, names(t), false))
            assertEquals(QuickLayout.Place.ROW, r.place)
            assertEquals("all four names fit without scrolling (${t.lang}, $rows rows)", 0f, r.maxScroll)
            assertEquals(QuickLayout.Tier.FULL, r.tier)
        }
        // the Pixel 6 at its default density (411 dp) and at the test phone's 485 dp
        for (w in listOf(411f, 485f)) {
            val r = QuickLayout.layout(input(es, w, panelH(4, 1.0f), 1f, names(es), false))
            assertEquals(QuickLayout.Place.ROW, r.place)
        }
    }

    @Test fun qp7TheSmallestKeyboardStillKeepsTheTemas() {
        // 80 % on 4 rows, font ×1.3, 320 dp: the chips move into the header rather than vanish (0.14 hid them)
        val r = QuickLayout.layout(input(es, 320f, panelH(4, 0.8f), 1.3f, names(es), true))
        assertTrue(r.place != QuickLayout.Place.NONE)
        assertTrue(r.tileH >= Quick.MIN_TILE_DP * dp)
    }

    @Test fun qp12TheTemaInUseIsScrolledIntoView() {
        val i = input(es, 320f, panelH(5, 1.0f), 1.3f, long6, false, active = 5)
        val r = QuickLayout.layout(i)
        assertTrue(r.maxScroll > 0f)
        val a = r.chips[5]
        assertTrue("the active chip is inside the viewport after the first scroll", a.r - r.scrollToActive <= r.viewport!!.r + 0.5f && a.l - r.scrollToActive >= r.viewport!!.l - 0.5f)
        // the first chip in use needs no scroll
        assertEquals(0f, QuickLayout.layout(input(es, 320f, panelH(5, 1.0f), 1.3f, long6, false, active = 0)).scrollToActive)
    }

    @Test fun qp6ThePanelNeverEllipsizesAName() {
        val src = File("src/main/kotlin/com/resyst/vk/ime/QuickPanel.kt").readText()
        val drawTema = src.substringAfter("private fun drawTema(").substringBefore("\n    }\n")
        assertTrue("the name is drawn whole", drawTema.contains("baseline(c, chip.name, x, r.centerY())"))
        assertTrue("no ellipsis on a name", !drawTema.contains("ellipsize"))
        assertTrue("the geometry is QuickLayout's", src.contains("QuickLayout.layout(QuickLayout.Input("))
        // the chip's fixed overhead the sweep models (8 + 20 + 6 + 10 dp) is the panel's
        assertTrue(src.contains("QuickLayout.Tier.FULL to (5 + 30 + 4 + 1 + 5) * dp + l + s") && src.contains("QuickLayout.Tier.COMPACT to (5 + 20 + 4 + 1 + 5) * dp + l + s"))
        for (c in listOf("const val CHIP_PAD = 8f", "const val SWATCH = 20f", "const val SWATCH_GAP = 6f", "const val CHIP_END = 10f")) assertTrue(c, src.contains(c))
        // QP9: the panel's text follows the font scale
        assertTrue(src.contains("Legend.fontFactor(fontScale).coerceAtMost(1.3f)"))
    }
}
