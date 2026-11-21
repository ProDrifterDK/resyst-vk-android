package com.resyst.vk.core

import java.io.File
import java.util.IllegalFormatException
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r14b (TR2, TR3, TR4): every Spanish resource has its English twin and the other way round, with the same
 * placeholders; every id the data stores has a name in both languages; English reads as English.
 * `VK_I18N_RES=<res dir>` (or -Dvk.resDir) points the checks at another tree (used to prove they bite on 0.14.0).
 */
class TranslationParityTest {
    private val es = Res.parse(File(Res.resDir(), "values"))
    private val en = Res.parse(File(Res.resDir(), "values-en"))

    private fun spanishKeys() = es.strings.keys - es.untranslatable
    private fun spanishPlurals() = es.plurals.keys - es.untranslatable

    @Test fun everySpanishStringHasAnEnglishOne() {
        val missing = spanishKeys() - en.strings.keys
        assertTrue("no English for: ${missing.sorted()}", missing.isEmpty())
    }

    @Test fun everyEnglishStringHasASpanishOne() {
        val extra = en.strings.keys - es.strings.keys
        assertTrue("English-only keys: ${extra.sorted()}", extra.isEmpty())
        val leaked = en.strings.keys.intersect(es.untranslatable)
        assertTrue("translatable=false keys written in English: ${leaked.sorted()}", leaked.isEmpty())
    }

    @Test fun pluralsAndArraysMatchToo() {
        assertEquals(spanishPlurals().sorted(), en.plurals.keys.sorted())
        for (k in spanishPlurals()) assertEquals("plural $k: quantities", es.plurals.getValue(k).keys, en.plurals.getValue(k).keys)
        assertEquals((es.arrays.keys - es.untranslatable).sorted(), en.arrays.keys.sorted())
        for ((k, v) in en.arrays) assertEquals("array $k: length", es.arrays.getValue(k).size, v.size)
        for ((k, v) in es.plurals) assertTrue("plural $k needs 'other'", "other" in v)
    }

    @Test fun placeholdersAgree() {
        val bad = ArrayList<String>()
        for (k in spanishKeys()) {
            val a = en.strings[k] ?: continue
            if (args(es.strings.getValue(k)) != args(a)) bad += "$k: ${args(es.strings.getValue(k))} vs ${args(a)}"
        }
        for (k in spanishPlurals()) for ((q, text) in es.plurals.getValue(k)) {
            val other = en.plurals[k]?.get(q) ?: continue
            // the count (argument 1) may be spelled out in one language and not in the other ("a recent emoji")
            if (args(text) != args(other)) bad += "$k[$q]: ${args(text)} vs ${args(other)}"
        }
        assertTrue("placeholders differ:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun everyStringFormatsCleanly() {
        // every string goes through String.format (Resources.getString(id, args)): a stray % must be %%, positions must be numbered
        val bad = ArrayList<String>()
        fun check(where: String, text: String) {
            val specs = Regex("%(\\d+\\$)?[a-zA-Z]").findAll(text).map { it.value }.toList()
            if (specs.size > 1 && specs.any { !it.contains('$') }) bad += "$where: several arguments must be numbered: $text"
            try {
                val n = (specs.mapNotNull { Regex("%(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: specs.size)
                val dummy = (1..maxOf(n, 1)).map { i -> if (specs.any { it.startsWith("%$i$") && it.endsWith("d") } || (specs.size == 1 && specs[0].endsWith("d"))) 7 else "x" }
                String.format(Locale.ROOT, text, *dummy.toTypedArray())
            } catch (e: IllegalFormatException) { bad += "$where: ${e.javaClass.simpleName}: $text" }
        }
        for ((p, r) in listOf("es" to es, "en" to en)) {
            for ((k, v) in r.strings) check("$p/$k", v)
            for ((k, v) in r.plurals) for ((q, text) in v) check("$p/$k[$q]", text)
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun nothingIsEmpty() {
        for ((p, r) in listOf("es" to es, "en" to en)) for ((k, v) in r.strings) assertTrue("$p/$k is empty", v.isNotBlank())
    }

    @Test fun languageCodesDiffer() {
        assertEquals("es", es.strings["lang_code"])
        assertEquals("en", en.strings["lang_code"])
    }

    /** TR4 / TR1: a Spanish word left in the English file is a mixed-language screen. */
    @Test fun englishHasNoSpanishLeftOver() {
        val spanishMarks = Regex("[¿¡ñáéíóúÁÉÍÓÚ]")
        val ok = setOf( // English strings that name a Spanish mark or word on purpose
            "ctl_auto_openers", "page_escritura_summary", "s_openers_summary", "s_try_hint", "s_bilingual_summary",
            "s_words_lang_en", "s_words_lang_es", "context_offers_summary", "s_credits_context", // Wikipedia, la enciclopedia libre: a proper title
        )
        val bad = en.strings.filter { (k, v) -> spanishMarks.containsMatchIn(v) && k !in ok }
        assertTrue("accented Spanish in English: ${bad.keys.sorted()}", bad.isEmpty())
        val stray = en.strings.filter { (k, v) -> Regex("\\b(el|la|los|las|de|del|para|con|sin|tu|tus)\\b").containsMatchIn(v) && k !in ok + setOf("tema_seed_dia") }
        assertTrue("Spanish words in English: ${stray.keys.sorted()}", stray.isEmpty())
    }

    /** A copy-pasted Spanish line: the same text in both files for a sentence is not a translation. */
    @Test fun sentencesAreActuallyTranslated() {
        val same = spanishKeys().filter { k ->
            val a = es.strings[k] ?: return@filter false
            val b = en.strings[k] ?: return@filter false
            a == b && a.trim().length > 12 && a.contains(' ')
        }.toSet()
        // names, brands and symbols that read the same in both languages
        val allowed = setOf(
            "gif_attribution", "gif_item_desc", "qs_auto", "co_status", "co_kb", "quick_tile_desc", "u_notif_title", "s_accent_hint",
            "palette_lab", "co_sent", "font_legible",
        )
        assertEquals("not translated: ${(same - allowed).sorted()}", emptySet<String>(), same - allowed)
    }

    // ---- every id the data stores has a name in both languages (TR3) ----

    private fun named(name: String) = listOf(TestTxt.ES, TestTxt.EN).map { it.named(name) }.let { (a, b) -> a != null && b != null }

    private fun missing(names: List<String>) = names.filter { !named(it) }

    @Test fun everyControlPageAndModeHasAName() {
        val names = Ctl.values().map { "ctl_" + it.name.lowercase() } +
            SettingsIA.PAGES.flatMap { listOf("page_${it.id}", "page_${it.id}_summary") } +
            Mode.values().flatMap { listOf("mode_${it.id}", "mode_${it.id}_summary") } +
            QuickAction.values().map { "qa_" + it.name.lowercase() } +
            EditOp.values().flatMap { listOf("editd_" + it.name.lowercase()) } +
            EditOp.values().filter { it != EditOp.LEFT && it != EditOp.RIGHT && it != EditOp.UP && it != EditOp.DOWN }.map { "edit_" + it.name.lowercase() } +
            NoMemory.values().flatMap { listOf("nomem_" + it.name.lowercase(), "nomem_" + it.name.lowercase() + "_detail") } +
            Region.values().map { "region_" + it.name.lowercase() } +
            ConnectionLog.What.values().map { "conn_what_${it.id}" } + ConnectionLog.Why.values().map { "conn_why_${it.id}" } +
            GifCopy.Fail.values().flatMap { listOf("gif_faillog_" + it.name.lowercase()) + if (it == GifCopy.Fail.CANCELLED) emptyList() else listOf("gif_fail_" + it.name.lowercase()) }
        assertEquals(emptyList<String>(), missing(names))
    }

    @Test fun everyPaletteAccentTemaStyleAndEmojiGroupHasAName() {
        val names = Themes.CATALOG.map { "palette_" + it.id } +
            Themes.ACCENTS.map { "accent_" + it.second } +
            listOf("resyst", "arcade", "pizarra", "noche", "dia", "juego", "escritura").map { "tema_seed_$it" } +
            Styles.ALL.flatMap { listOf("style_" + it.id, "style_${it.id}_line") } +
            EmojiCatalog.GROUPS.map { "emoji_cat_" + it.first }
        assertEquals(emptyList<String>(), missing(names))
    }

    @Test fun theSeedNamesAreWhatIsStored() {
        // TR3: switching the language changes what is shown, never what is stored
        val st = ProfileCodec.seed()
        assertEquals(listOf("Resyst", "Arcade", "Pizarra"), st.temas.map { it.name })
        val slate = st.temas.first { it.id == "pizarra" }
        assertEquals("Pizarra", SeedNames.display(TestTxt.ES, slate))
        assertEquals("Slate", SeedNames.display(TestTxt.EN, slate))
        val renamed = slate.copy(name = "Mi noche")
        assertEquals("Mi noche", SeedNames.display(TestTxt.EN, renamed))
        assertNotEquals(SeedNames.display(TestTxt.ES, slate), SeedNames.display(TestTxt.EN, slate))
    }

    private fun args(text: String): List<String> =
        Regex("%(\\d+\\$)?[a-zA-Z]").findAll(text.replace("%%", "")).map { m ->
            val v = m.value
            if (v.contains('$')) v else "%1\$" + v.last() // an unnumbered single argument is argument 1
        }.sorted().toList()
}
