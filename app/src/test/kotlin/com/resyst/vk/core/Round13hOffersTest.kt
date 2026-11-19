package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r13h (G3, docs/failure-modes.md MO1-MO5): every pending context offer of the open sentence stays on the bar, one tap
 * applies them all in one edit, the ↶ restores them while the text is unchanged. The decisions are the shipped ones
 * (OffersOracle: the shipped model, lexicon and keep-lists), made at every word end of the typed text as the IME makes
 * them.
 */
class Round13hOffersTest {
    /** Types [text] char by char; at every word end a job, its decision and arrive (the IME's flow), refresh after every key. */
    private fun typed(text: String, offers: ContextOffers = ContextOffers()): ContextOffers {
        for (i in 1..text.length) {
            val before = text.substring(0, i)
            offers.refresh(before, whole = true, collapsed = true)
            if (text[i - 1] == ' ' || KeyboardEngine.endsWord(text[i - 1].toString())) {
                offers.job(before, whole = true)?.let { job ->
                    offers.decide(job, OffersOracle.check, OffersOracle.keepGeneric)?.let { offers.arrive(it, before, whole = true) }
                }
            }
        }
        return offers
    }

    /**
     * The owner's example, `paso por tu caza y esta bien`, gets ONE offer from the frozen model: `esta` → `está` scores
     * Δ 5.73 there, under τ_offer 6 (CO1: port, don't retune). With `muy` before `bien` it scores 6.43: two offers.
     */
    private val two = "paso por tu caza y esta muy bien "

    @Test fun mo1TwoOffersStayLiveInOneSentence() {
        assertEquals("the literal example: one offer (Δ 5.73 < τ 6)", listOf("caza" to "casa"), typed("paso por tu caza y esta bien ").live.map { it.typed to it.label })
        assertEquals(listOf("caza" to "casa", "di" to "si"), typed("paso por tu caza y no se di vamos ").live.map { it.typed to it.label })
        val o = typed(two)
        assertEquals("caza → casa, esta → está, in sentence order", listOf("caza" to "casa", "esta" to "está"), o.live.map { it.typed to it.label })
        assertEquals("current is the last one (the 0.12.0 field)", "está", o.current?.label)
        // one goes stale (its word edited), the other stays; an edited EARLIER word takes the later offers with it (ST2:
        // an offer holds only while the words up to its target are the same)
        assertTrue(o.refresh("paso por tu caza y estaba muy bien ", whole = true, collapsed = true))
        assertEquals(listOf("caza"), o.live.map { it.typed })
        val o1 = typed(two)
        assertTrue(o1.refresh("paso por tu cama y esta muy bien ", whole = true, collapsed = true))
        assertTrue(o1.live.isEmpty())
        // the sentence moves on: both go, counted as kept (ST1/ST5)
        val p = typed(two)
        assertTrue(p.refresh("$two. Hola", whole = true, collapsed = true) || p.live.isEmpty())
        assertTrue(p.live.isEmpty())
        // a selection drops them all (ST2)
        val q = typed(two)
        assertTrue(q.refresh(two, whole = true, collapsed = false))
        assertTrue(q.live.isEmpty())
        // a single wrong word: one offer, the 0.12.0 chip
        assertEquals(listOf("casa"), typed("paso por tu caza como ").live.map { it.label })
    }

    @Test fun mo3OneTapAppliesAllInOneEditKeepingTheCursor() {
        val o = typed(two)
        val outs = o.take(two, whole = true)!!
        assertEquals("one delete + one commit (one batch edit)", 2, outs.size)
        assertEquals("paso por tu casa y está muy bien ", Edits.apply(two, outs))
        assertTrue("gone after the tap", o.live.isEmpty())
        // with text typed after the last target and a capital: kept, the cursor after the same text
        val t2 = "Mañana paso por tu caza y no se di vamos a"
        val o2 = typed("Mañana paso por tu caza y no se di vamos ")
        assertEquals(2, o2.live.size)
        o2.refresh(t2, whole = true, collapsed = true)
        assertEquals("Mañana paso por tu casa y no se si vamos a", Edits.apply(t2, o2.take(t2, whole = true)!!))
        // one of them went stale before the tap: the live one is applied alone (never blind)
        val o3 = typed(two)
        val stale = "paso por tu caza y estaba muy bien "
        assertEquals("paso por tu casa y estaba muy bien ", Edits.apply(stale, o3.take(stale, whole = true)!!))
        // nothing live: the tap edits nothing
        val o4 = typed(two)
        assertNull(o4.take("otra cosa ", whole = true))
    }

    @Test fun mo4OneUndoRestoresAll() {
        val o = typed(two)
        val after = Edits.apply(two, o.take(two, whole = true)!!)
        assertEquals(listOf("caza", "esta"), o.undoLabel(after))
        assertEquals("one ↶ restores both, the cursor after the same text", two, Edits.apply(after, o.undo(after)!!))
        assertNull("used once", o.undoLabel(two))
        // the restored pairs count as kept originals (ST5): a second keep in this field suppresses them
        assertFalse(o.suppressed("caza", "casa"))
        val o2 = typed(two)
        val after2 = Edits.apply(two, o2.take(two, whole = true)!!)
        // any edit ends the ↶: a letter typed after it, a cursor move (the text before the cursor changes)
        assertNull(o2.undoLabel(after2 + "y"))
        assertNull(o2.undo(after2 + "y"))
        // the single offer gets its ↶ too
        val o3 = typed("paso por tu caza como ")
        val a3 = Edits.apply("paso por tu caza como ", o3.take("paso por tu caza como ", whole = true)!!)
        assertEquals(listOf("caza"), o3.undoLabel(a3))
        // MO5: the ↶ is RAM only, gone with the keyboard hidden or another field
        o3.dropOffer()
        assertNull(o3.undoLabel(a3))
        val o5 = typed(two)
        val a5 = Edits.apply(two, o5.take(two, whole = true)!!)
        o5.reset()
        assertNull(o5.undoLabel(a5))
    }

    @Test fun mo2mo5TheChipTheStringsAndTheGates() {
        val es = File("src/main/res/values/strings.xml").readText()
        val en = File("src/main/res/values-en/strings.xml").readText()
        assertTrue(es.contains(">%1\$d cambios<") && es.contains(">Cambiar %1\$s<") && es.contains(">%1\$s por %2\$s<") && es.contains(">\" y \"<"))
        assertTrue(en.contains(">%1\$d changes<") && en.contains(">Replace %1\$s<") && en.contains(">%1\$s with %2\$s<") && en.contains(">\" and \"<"))
        val ime = File("src/main/kotlin/com/resyst/vk/ime/ResystImeService.kt").readText()
        val chip = ime.substringAfter("private fun showChip()").substringBefore("\n    }")
        assertTrue("one offer: the 0.12.0 chip", chip.contains("1 -> v.setOffer(live[0].label, getString(R.string.context_offer_desc, live[0].typed, live[0].label))"))
        assertTrue("several: N cambios", chip.contains("R.string.context_offers_many, live.size"))
        // the gate is 0.12.0's (secret, incognito, the field gate, the switch, Spanish) and the ↶ only exists after a tap
        val gate = ime.substringAfter("private fun offersAllowed()").substringBefore("\n    }")
        for (s in listOf("st.suggest", "st.contextOffers", "policy.suggestions", "!policy.secret", "!policy.incognito", "FieldKind.TEXT", "Lang.ES")) assertTrue(s, gate.contains(s))
        assertTrue(ime.substringAfter("override fun onSuggestion(word: String)").substringBefore("\n    }").contains("if (word == offerUndoChip) { undoContextOffers(ic); return }"))
        val glue = ime.substringAfter("// ── r13f: context offers").substringBefore("// ── r10: text editing")
        for (s in listOf("PersonalStore.changed", "learner.afterEdit", "learner.kept", "strike(", "rememberValue", ".unlearn(")) assertFalse(s, glue.contains(s))
    }
}
