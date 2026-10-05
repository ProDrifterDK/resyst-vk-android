package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAndVariantsTest {

    private fun spec(
        lang: Lang = Lang.ES,
        top: TopRow = TopRow.ACCENTS,
        switch: Boolean = false,
        field: FieldKind = FieldKind.TEXT,
    ) = LayoutSpec(lang, top, switch, field)

    private val allSpecs = Lang.values().flatMap { l ->
        TopRow.values().flatMap { t ->
            listOf(true, false).flatMap { s ->
                FieldKind.values().map { f -> spec(l, t, s, f) }
            }
        }
    }

    // L1
    @Test fun everyRowOfEveryLayerFillsTheRowWidth() {
        for (sp in allSpecs) for (layer in Layer.values()) {
            KeyboardLayouts.rows(layer, sp).forEachIndexed { i, row ->
                assertEquals("$layer row $i $sp", KeyboardLayouts.ROW_UNITS, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
            }
        }
    }

    private fun letters(sp: LayoutSpec) = KeyboardLayouts.rows(Layer.LETTERS, sp)
        .flatten().filter { it.type == KeyType.CHAR && it.text.length == 1 && it.text[0].isLetter() && it.style != KeyStyle.ACCENT }
        .map { it.text }

    // L2 + L4
    @Test fun spanishHasEnyeRightAfterL() {
        val home = KeyboardLayouts.rows(Layer.LETTERS, spec(top = TopRow.NONE))[1].filter { it.type == KeyType.CHAR }.map { it.text }
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"), home)
        val ls = letters(spec(top = TopRow.NONE))
        assertEquals(ls.size, ls.toSet().size)
        assertEquals(('a'..'z').map { it.toString() }.toSet() + "ñ", ls.toSet())
    }

    // L3
    @Test fun englishHasExactlyAToZ() {
        val ls = letters(spec(lang = Lang.EN, top = TopRow.NONE))
        assertEquals(26, ls.size)
        assertEquals(('a'..'z').map { it.toString() }.toSet(), ls.toSet())
        assertFalse("ñ" in ls)
    }

    // L5
    @Test fun symbolLayersHaveEverydayCharactersAndAWayBack() {
        val sym = KeyboardLayouts.rows(Layer.SYMBOLS, spec()).flatten()
        val texts = sym.map { it.text }.toSet()
        for (c in listOf("1", "0", "@", "#", "€", "-", "(", ")", "/", "?", "!", "\"", "'", ":", ";")) assertTrue(c, c in texts)
        assertTrue(sym.any { it.type == KeyType.LAYER && it.target == Layer.LETTERS })
        assertTrue(sym.any { it.type == KeyType.LAYER && it.target == Layer.SYMBOLS2 })
        val sym2 = KeyboardLayouts.rows(Layer.SYMBOLS2, spec()).flatten()
        assertTrue(sym2.any { it.type == KeyType.LAYER && it.target == Layer.SYMBOLS })
        assertTrue(sym2.any { it.type == KeyType.LAYER && it.target == Layer.LETTERS })
        val num = KeyboardLayouts.rows(Layer.NUMPAD, spec()).flatten().map { it.text }.toSet()
        for (d in 0..9) assertTrue("$d", d.toString() in num)
    }

    // L6
    @Test fun everyLayerHasSpaceEnterBackspace() {
        for (sp in allSpecs) for (layer in Layer.values()) {
            val keys = KeyboardLayouts.rows(layer, sp).flatten().map { it.type }
            assertTrue("$layer $sp enter", KeyType.ENTER in keys)
            assertTrue("$layer $sp backspace", KeyType.BACKSPACE in keys)
            if (layer != Layer.NUMPAD) assertTrue("$layer $sp space", KeyType.SPACE in keys)
        }
    }

    // L7
    @Test fun switchKeyFollowsTheSystemFlag() {
        for (layer in listOf(Layer.LETTERS, Layer.SYMBOLS, Layer.SYMBOLS2)) {
            val withKey = KeyboardLayouts.rows(layer, spec(switch = true)).flatten()
            val without = KeyboardLayouts.rows(layer, spec(switch = false)).flatten()
            assertTrue(withKey.any { it.type == KeyType.SWITCH_IME })
            assertFalse(without.any { it.type == KeyType.SWITCH_IME })
        }
    }

    // L8
    @Test fun topRowPreference() {
        val none = KeyboardLayouts.rows(Layer.LETTERS, spec(top = TopRow.NONE))
        val acc = KeyboardLayouts.rows(Layer.LETTERS, spec(top = TopRow.ACCENTS))
        val num = KeyboardLayouts.rows(Layer.LETTERS, spec(top = TopRow.NUMBERS))
        assertEquals(4, none.size)
        assertEquals(5, acc.size)
        assertEquals(5, num.size)
        val accents = acc[0].map { it.text }
        for (c in listOf("á", "é", "í", "ó", "ú", "ñ", "¿", "¡")) assertTrue(c, c in accents)
        assertEquals((1..9).map { "$it" } + "0", num[0].map { it.text })
        // Symbols never get the extra row (digits are already there).
        assertEquals(4, KeyboardLayouts.rows(Layer.SYMBOLS, spec(top = TopRow.ACCENTS)).size)
    }

    @Test fun emailAndUrlFieldsSwapTheCommaKey() {
        val email = KeyboardLayouts.rows(Layer.LETTERS, spec(field = FieldKind.EMAIL)).last().map { it.text }
        val url = KeyboardLayouts.rows(Layer.LETTERS, spec(field = FieldKind.URL)).last().map { it.text }
        assertTrue("@" in email)
        assertTrue("/" in url)
    }

    @Test fun topRowLettersCarryDigitHints() {
        val row = KeyboardLayouts.rows(Layer.LETTERS, spec(top = TopRow.NONE))[0]
        assertEquals((1..9).map { "$it" } + "0", row.map { it.hint })
        row.forEach { assertTrue(it.text, it.hint!! in it.variants) }
    }

    // V1
    @Test fun spanishVowelsDefaultToAcute() {
        val expected = mapOf("a" to "á", "e" to "é", "i" to "í", "o" to "ó", "u" to "ú", "n" to "ñ")
        for ((k, v) in expected) assertEquals(k, v, Variants.forKey(k, Lang.ES).first())
        assertEquals("¿", Variants.forKey("?", Lang.ES).first())
        assertEquals("¡", Variants.forKey("!", Lang.ES).first())
    }

    // V2 + V4
    @Test fun shiftUppercasesSingleCharsOnly() {
        assertEquals(listOf("É", "3", "È"), Variants.applyShift(listOf("é", "3", "è"), true))
        assertEquals(listOf("é", "3"), Variants.applyShift(listOf("é", "3"), false))
        assertEquals("ß", Variants.shiftChar("ß"))
        assertEquals("Ñ", Variants.shiftChar("ñ"))
        assertEquals("¿", Variants.shiftChar("¿"))
    }

    // V3
    @Test fun variantListsHaveNoBaseAndNoDuplicates() {
        for (lang in Lang.values()) for ((base, list) in Variants.table(lang)) {
            assertFalse("$base contains itself", base in list)
            assertEquals("$base duplicates: $list", list.size, list.toSet().size)
            assertTrue("$base empty", list.isNotEmpty())
        }
    }

    // V5
    @Test fun keysWithoutVariantsReturnEmpty() {
        assertTrue(Variants.forKey("x-not-a-key", Lang.ES).isEmpty())
        assertTrue(Variants.forKey("", Lang.EN).isEmpty())
    }
}
