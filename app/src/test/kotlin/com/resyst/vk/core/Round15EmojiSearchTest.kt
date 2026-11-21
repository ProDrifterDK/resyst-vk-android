package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** r15 (ES1–ES14 in docs/failure-modes.md): emoji search over the shipped asset and the shipped catalog. */
class Round15EmojiSearchTest {
    private val catalog = EmojiCatalog.parse(File("src/main/assets/emoji/emoji.txt").readText())
    private val text = File("src/main/assets/emoji/search.txt").readText()
    private val es = EmojiSearch.parse(text, catalog, "es")!!
    private val en = EmojiSearch.parse(text, catalog, "en")!!

    private fun EmojiSearch.find(q: String, recents: List<String> = emptyList(), limit: Int = 1000): List<String> =
        search(q, recentRanks(recents, catalog), limit).map { cell(it).base }

    @Test fun es3Es4TheOwnersFourQueriesReachTheEagle() {
        for (q in listOf("águila", "aguila", "hawk", "eagle", "ÁGUILA", "Eagle", "  aguila  ", "halcón", "halcon")) {
            val r = es.find(q)
            assertEquals("«$q» → $r", "🦅", r.firstOrNull())
            assertEquals("«$q» in the English index", "🦅", en.find(q).firstOrNull())
        }
        // typed one key at a time: the eagle is there from «agu» on and first once the word is whole
        assertTrue(es.find("agu").contains("🦅"))
        assertTrue(es.find("eag").contains("🦅"))
    }

    @Test fun es12TheNameIsInTheInterfaceLanguage() {
        val id = es.search("eagle").first()
        assertEquals("águila", es.name(id))
        assertEquals("eagle", en.name(en.search("águila").first()))
    }

    @Test fun es6ExactNameBeforeKeywordBeforeWordBeforePrefix() {
        // «gato»: the emoji named «gato» comes before those that only carry the word (cara de gato…) or start with it
        val gato = es.find("gato")
        assertEquals("🐈", gato.first())
        assertTrue(gato.size > 3)
        // a prefix never outranks an exact name: «sol» is ☀️'s name; «soldado», «soleado»… only start with it
        assertEquals("☀️", es.find("sol").first())
        // the whole multi-word name
        assertEquals("👍", es.find("pulgar hacia arriba").first())
        assertTrue(es.find("pulgar arriba").contains("👍"))
        // every word must match: a second word that matches nothing empties the result
        assertTrue(es.find("aguila zzzz").isEmpty())
    }

    @Test fun es6RecentsComeFirstWithinATier() {
        val plain = es.find("cara")
        assertTrue(plain.size > 20)
        assertEquals("😀", plain.first())
        assertTrue(plain.indexOf("😂") > 3) // «cara» is a keyword of both: the same tier, catalog order
        val boosted = es.find("cara", recents = listOf("😂"))
        assertEquals("a recent goes first among equals", "😂", boosted.first())
        assertEquals(plain.toSet(), boosted.toSet())
        // a recent that only starts with the query stays behind the exact matches
        val prefixOnly = plain.last()
        assertEquals(plain.first(), es.find("cara", recents = listOf(prefixOnly)).first())
        // a toned recent counts for its base cell (👍 is not first for «pulgar» on its own)
        assertTrue(es.find("pulgar").first() != "👍")
        assertEquals("👍", es.find("pulgar", recents = listOf("👍🏽")).first())
        // the band shows at most LIMIT
        assertEquals(EmojiSearch.LIMIT, es.search("a").size)
        // but never above a better tier: «águila» stays first even with another recent bird
        assertEquals("🦅", es.find("aguila", recents = listOf("🐦")).first())
    }

    @Test fun es1NoLetterNoSearch() {
        for (q in listOf("", " ", "!!", "😀", "¿?")) assertTrue(q, es.find(q).isEmpty())
        // flags are found by country (🌶️ carries the keyword «chile» itself, a stronger tier than a word of a name), keycaps by digit
        assertEquals(listOf("🌶️", "🫑", "🇨🇱"), es.find("chile"))
        assertTrue(en.find("keycap 1").isNotEmpty())
    }

    @Test fun es3TheKotlinFoldReproducesEveryTokenOfTheAsset() {
        var n = 0
        for (t in 0 until es.tokenCount) {
            val tok = es.token(t)
            assertEquals("token $t", tok, EmojiSearch.words(tok).joinToString(" "))
            n++
        }
        assertEquals(es.tokenCount, n)
        assertTrue(n > 10_000)
        assertEquals("aguila", EmojiSearch.fold("ÁGUILA"))
        assertEquals("nino", EmojiSearch.fold("niño"))
        assertEquals(listOf("cafe", "con", "leche"), EmojiSearch.words("¡Café con-leche!"))
    }

    @Test fun es3TokensAreSortedTheWayTheLookupSearches() {
        for (t in 1 until es.tokenCount) assertTrue("token order at $t", es.token(t - 1) < es.token(t))
    }

    @Test fun es14EveryCatalogEmojiIsIndexedWithNamesInBothLanguages() {
        assertEquals(EmojiSearch.cellsOf(catalog).size, es.size)
        var namedEs = 0
        var namedEn = 0
        for (i in 0 until es.size) { if (es.name(i).isNotEmpty()) namedEs++; if (en.name(i).isNotEmpty()) namedEn++ }
        println("ES14 coverage: ${es.size} emoji, es names $namedEs, en names $namedEn, ${es.tokenCount} tokens, ${es.postingCount} postings, asset ${File("src/main/assets/emoji/search.txt").length()} bytes")
        assertEquals(es.size, namedEs)
        assertEquals(es.size, namedEn)
    }

    @Test fun es14AnAssetForAnotherCatalogIsRefused() {
        val small = EmojiCatalog.parse("@caras\n😀\n😃\n")
        assertNull(EmojiSearch.parse(text, small, "es"))
        assertNull(EmojiSearch.parse("garbage", catalog, "es"))
        assertNotNull(EmojiSearch.parse(text, catalog, "en"))
    }

    @Test fun es5TheSupplementOnlyAddsAndSaysWhy() {
        val extra = File("../scripts/data/emoji-search-extra.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(extra.isNotEmpty())
        for (line in extra) {
            val parts = line.split('\t')
            assertEquals(line, 4, parts.size)
            assertTrue(line, parts[3].length > 20)
        }
        // the CLDR names are untouched: 🦅 is still «águila» / «eagle»
        assertEquals("águila", es.name(es.search("hawk").first()))
    }

    @Test fun es1TheSearchCoreHasNoWayOut() {
        val src = File("src/main/kotlin/com/resyst/vk/core/EmojiSearch.kt").readText()
        for (s in listOf("java.io", "File(", "Log.", "SharedPreferences", "write", "println", "URL", "Socket"))
            assertFalse("EmojiSearch must not reach «$s»", src.contains(s))
    }

    @Test fun es13TheAssetCarriesItsLicence() {
        val notice = File("src/main/assets/emoji/NOTICE.txt").readText()
        assertTrue(notice.contains("UNICODE LICENSE V3"))
        assertTrue(notice.contains("CLDR"))
        assertTrue(text.lineSequence().first().contains("CLDR 49"))
        for (dir in listOf("values", "values-en")) {
            val credits = File("src/main/res/$dir/strings_app.xml").readText()
            assertTrue(dir, credits.contains("CLDR"))
        }
        assertTrue(File("../README.md").readText().contains("CLDR"))
    }
}
