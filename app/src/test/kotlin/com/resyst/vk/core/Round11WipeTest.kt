package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r11a-fix — "Borrar lo aprendido" covers everything in files/personal/ (docs/failure-modes.md F1,
 * F3). The device half (row enabled by emoji data alone, the wipe reaching the keyboard's memory,
 * F2/F4) is scripts/e2e_r11.py E4.
 */
class Round11WipeTest {
    private val cat = EmojiCatalog.parse(File("src/main/assets/emoji/emoji.txt").readText())

    @Test fun anyKeptKindAloneEnablesTheWipe() { // F1: the gate read words + emails only
        val none = PersonalSummary.Counts(0, 0, 0, 0)
        assertTrue(none.empty)
        assertEquals("Nada aprendido todavía · nada sale del teléfono", PersonalSummary.text(none))
        for (c in listOf(
            PersonalSummary.Counts(1, 0, 0, 0), PersonalSummary.Counts(0, 1, 0, 0),
            PersonalSummary.Counts(0, 0, 1, 0), PersonalSummary.Counts(0, 0, 0, 1),
        )) {
            assertFalse(c.toString(), c.empty)
            assertFalse(c.toString(), PersonalSummary.text(c).startsWith("Nada aprendido"))
            assertTrue(c.toString(), PersonalSummary.text(c).endsWith(" · nada sale del teléfono"))
        }
    }

    @Test fun theSummaryNamesWhatIsKeptInSpanish() { // F1: zero parts left out, singular/plural
        assertEquals("12 palabras · 1 correo · 8 emojis recientes · 2 tonos · nada sale del teléfono",
            PersonalSummary.text(PersonalSummary.Counts(12, 1, 8, 2)))
        assertEquals("1 palabra · 3 correos · 1 emoji reciente · 1 tono · nada sale del teléfono",
            PersonalSummary.text(PersonalSummary.Counts(1, 3, 1, 1)))
        assertEquals("2 emojis recientes · 1 tono · nada sale del teléfono",
            PersonalSummary.text(PersonalSummary.Counts(0, 0, 2, 1)))
    }

    @Test fun theTonesListIsWhatTheKeyboardDecodes() { // F3 (review m1)
        // a stale pair (a tone of another base), garbage, an unknown base, a variant as key
        val file = "👍\t👍🏽\n👎\t👍🏽\nbasura\n🦄\t🦄🏽\n👍🏽\t👍🏿\n✋\t✋🏾\n"
        val t = EmojiTones.decode(file, cat)
        assertEquals(listOf("👍" to "👍🏽", "✋" to "✋🏾"), t.entries())
        // forgetting one leaves exactly the other valid pair on disk, junk gone
        assertTrue(t.forget("👍"))
        assertFalse(t.forget("👍"))
        assertEquals(listOf("✋" to "✋🏾"), EmojiTones.decode(t.encode(), cat).entries())
        assertEquals("👍", t.shown("👍"))
    }
}
