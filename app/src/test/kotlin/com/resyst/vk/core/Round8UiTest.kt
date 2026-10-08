package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 8 emoji key + theming. Failure modes, written before the code:
 *
 * E8-1 the emoji key appears by default and steals width from the space bar only (row = 10 units).
 * E8-2 email / URL / password fields never get it (their @ or / stays; no emoji in secrets).
 * E8-3 the setting turns it off and the row is the r7 row again.
 * E8-4 symbol layers carry it too (same bottom row), numpad never.
 * E8-5 recents: most-recent-first, no duplicates, capped, survive encode/decode, reject junk.
 * E8-6 the catalog has categories, no empty ones, no duplicates inside a category.
 *
 * T8-1 three new themes exist with WCAG contrast: text on keys ≥ 7:1, accent ≥ 4.5:1 (via Palette).
 * T8-2 AMOLED is true black; e-ink is pure black on white.
 * T8-3 day/night flips dark⇄light, and a second flip returns to the exact starting theme.
 * T8-4 the flip remembers the partner across a codec round-trip; junk altTheme is ignored.
 * T8-5 every theme has a twin of the opposite brightness.
 */
class Round8UiTest {

    private fun bottom(field: FieldKind = FieldKind.TEXT, emoji: Boolean = true, layer: Layer = Layer.LETTERS) =
        KeyboardLayouts.rows(layer, LayoutSpec(Lang.ES, TopRow.ACCENTS, field, emojiKey = emoji)).last()

    @Test fun emojiKeyIsOnByDefaultAndFitsTheRow() { // E8-1
        assertTrue(KbSettings().emojiKey)
        val row = bottom()
        assertEquals(listOf(KeyType.LAYER, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), row.map { it.type })
        assertEquals(KeyboardLayouts.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
        val without = bottom(emoji = false)
        assertEquals(without.first { it.type == KeyType.SPACE }.width - 1f, row.first { it.type == KeyType.SPACE }.width, 0.001f)
    }

    @Test fun addressAndSecretFieldsHaveNoEmojiKey() { // E8-2
        for (f in listOf(FieldKind.EMAIL, FieldKind.URL, FieldKind.PASSWORD)) {
            assertFalse("$f", bottom(f).any { it.type == KeyType.EMOJI })
        }
        assertEquals("@", bottom(FieldKind.EMAIL)[1].text)
    }

    @Test fun settingOffRestoresTheR7Row() { // E8-3
        assertEquals(listOf(KeyType.LAYER, KeyType.CHAR, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), bottom(emoji = false).map { it.type })
        val s = ProfileCodec.decode(ProfileCodec.encode(ProfileCodec.seed().updatePhone { it.copy(emojiKey = false) }))
        assertFalse(s.settings.emojiKey)
        assertTrue(ProfileCodec.seed().settings.emojiKey)
    }

    @Test fun symbolLayersCarryItNumpadNever() { // E8-4
        assertTrue(bottom(layer = Layer.SYMBOLS).any { it.type == KeyType.EMOJI })
        assertTrue(bottom(layer = Layer.SYMBOLS2).any { it.type == KeyType.EMOJI })
        val pad = KeyboardLayouts.rows(Layer.NUMPAD, LayoutSpec(Lang.ES, TopRow.ACCENTS, FieldKind.NUMBER, emojiKey = true))
        assertFalse(pad.flatten().any { it.type == KeyType.EMOJI })
    }

    @Test fun recentsAreMruDedupedCappedAndPersistable() { // E8-5
        val r = EmojiRecents()
        assertTrue(r.push("😀")); assertTrue(r.push("❤️")); assertTrue(r.push("😀"))
        assertEquals(listOf("😀", "❤️"), r.items())
        assertFalse(r.push("😀")) // already first: no write
        assertFalse(r.push("hola")); assertFalse(r.push("")); assertFalse(r.push("a b"))
        assertTrue(r.push("1️⃣")) // keycaps are emoji even though they start with a digit
        repeat(40) { r.push(Emoji.ALL[it]) }
        assertEquals(EmojiRecents.MAX, r.items().size)
        assertEquals(r.items(), EmojiRecents.decode(r.encode()).items())
        assertEquals(emptyList<String>(), EmojiRecents.decode(null).items())
        assertEquals(listOf("😀"), EmojiRecents.decode("😀\u001Fjunk text\u001F😀").items())
    }

    @Test fun catalogIsWellFormed() { // E8-6
        assertTrue(Emoji.CATEGORIES.size >= 6)
        for (c in Emoji.CATEGORIES) {
            assertTrue(c.id, c.items.size >= 20)
            assertEquals(c.id, c.items.size, c.items.toSet().size)
            for (e in c.items) assertTrue("${c.id} $e", EmojiRecents.valid(e))
        }
        assertEquals(Emoji.CATEGORIES.size, Emoji.CATEGORIES.map { it.id }.toSet().size)
    }

    // ── themes ──────────────────────────────────────────────────────────
    @Test fun newThemesMeetContrast() { // T8-1
        for (id in listOf("sepia", "amoled", "eink")) {
            val t = Themes.byId(id)
            assertEquals(id, t.id)
            assertTrue("$id text/key", ColorMath.contrast(t.text, t.key) >= 7.0)
            assertTrue("$id textMod/keyMod", ColorMath.contrast(t.textMod, t.keyMod) >= 4.5)
            assertTrue("$id muted/bg", ColorMath.contrast(t.muted, t.bg) >= 4.5)
            assertTrue("$id accent/key", ColorMath.contrast(Palette.of(id, null).accent, t.key) >= 4.5)
            for ((acc, _) in Themes.ACCENTS) assertTrue("$id ${ColorMath.toHex(acc)}", ColorMath.contrast(Palette.of(id, acc).accent, t.key) >= 4.5)
        }
        assertEquals(listOf("lab", "slate", "arcade", "paper", "hc", "sepia", "amoled", "eink"), Themes.ALL.map { it.id })
    }

    @Test fun amoledIsTrueBlackEinkIsInk() { // T8-2
        val a = Themes.byId("amoled")
        assertEquals("#000000", ColorMath.toHex(a.bg)); assertEquals("#000000", ColorMath.toHex(a.bg2))
        assertTrue(a.dark)
        val e = Themes.byId("eink")
        assertEquals("#ffffff", ColorMath.toHex(e.key)); assertEquals("#000000", ColorMath.toHex(e.text))
        assertFalse(e.dark)
        assertTrue(ColorMath.contrast(e.text, e.key) >= 21.0)
        assertFalse(Themes.byId("sepia").dark)
    }

    @Test fun dayNightFlipsAndComesBack() { // T8-3
        for (t in Themes.ALL) {
            val s = KbSettings(theme = t.id)
            val once = DayNight.toggle(s)
            assertNotEquals(t.id, Themes.byId(once.theme).dark, t.dark)
            assertEquals(t.id, DayNight.toggle(once).theme)
        }
        assertEquals("sepia", DayNight.toggle(KbSettings(theme = "slate")).theme)
        // a hand-picked theme in between: the flip still goes to the remembered partner if it has the other brightness
        val picked = DayNight.toggle(KbSettings(theme = "slate")).copy(theme = "eink") // light → altTheme slate is dark
        assertEquals("slate", DayNight.toggle(picked).theme)
        // remembered partner of the same brightness is skipped for the default twin
        assertEquals("paper", DayNight.toggle(KbSettings(theme = "lab", altTheme = "slate")).theme)
    }

    @Test fun flipSurvivesTheCodec() { // T8-4
        val st = ProfileCodec.seed().updateLook("resyst") { DayNight.toggle(it) }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals("paper", back.settings.theme)
        assertEquals("lab", back.settings.altTheme)
        val raw = ProfileCodec.encode(st).toMutableMap<String, Any?>().apply { put("t.resyst.altTheme", "neon") }
        assertEquals(null, ProfileCodec.decode(raw).settings.altTheme)
        // r10 (QS1/QS3): the strip's sun/moon is opt-in now; Día / noche lives in the quick panel
        assertFalse(ProfileCodec.decode(emptyMap<String, Any?>()).settings.dayNightChip)
    }

    @Test fun everyThemeHasAnOppositeTwin() { // T8-5
        for (t in Themes.ALL) assertNotEquals(t.id, t.dark, Themes.byId(Themes.twin(t.id)).dark)
    }
}
