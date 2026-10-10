package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * r11 — the full emoji catalog (docs/failure-modes.md EC1–EC6, ET1–ET3). The asset is checked
 * against the pinned Unicode file itself (scripts/data/emoji-test-18.0.txt), re-read here
 * independently of scripts/gen_emoji.py, so a stale or hand-edited asset fails.
 */
class Round11EmojiTest {
    private val asset = File("src/main/assets/emoji/emoji.txt").readText()
    private val cat = EmojiCatalog.parse(asset)
    private val tonesRange = 0x1F3FB..0x1F3FF

    /** (group, emoji) of every fully-qualified line of the pinned emoji-test.txt, in file order. */
    private val pinned: List<Pair<String, String>> by lazy {
        var group = ""
        File("../scripts/data/emoji-test-18.0.txt").readLines().mapNotNull { line ->
            if (line.startsWith("# group:")) { group = line.substringAfter(':').trim(); return@mapNotNull null }
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val (cps, rest) = line.split(';', limit = 2)
            if (rest.substringBefore('#').trim() != "fully-qualified") return@mapNotNull null
            group to cps.trim().split(Regex("\\s+")).joinToString("") { String(Character.toChars(it.toInt(16))) }
        }
    }

    private fun toned(e: String) = e.codePoints().anyMatch { it in tonesRange }

    @Test fun everyFullyQualifiedEmojiIsInTheCatalogOnce() { // EC1
        assertEquals(3963, pinned.size) // the file's own "# fully-qualified : 3963"
        val expected = pinned.filter { it.first != "Component" }.map { it.second }
        val all = cat.groups.flatMap { g -> g.cells.flatMap { listOf(it.base) + it.tones } }
        assertEquals(expected.size, all.size)
        assertEquals(expected.toSet(), all.toSet())
        assertEquals(1923, cat.baseCount)
        assertEquals(2040, cat.variantCount)
        // no bare skin tone / hair component is offered as an emoji
        for (c in pinned.filter { it.first == "Component" }) assertEquals(null, cat.cellOf(c.second))
    }

    @Test fun groupsAndOrderFollowUnicode() { // EC1
        val names = listOf("Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places",
            "Activities", "Objects", "Symbols", "Flags")
        assertEquals(EmojiCatalog.GROUPS.map { it.first }, cat.groups.map { it.id })
        assertEquals(listOf("Caras y emociones", "Personas y cuerpo", "Animales y naturaleza", "Comida y bebida",
            "Viajes y lugares", "Actividades", "Objetos", "Símbolos", "Banderas"), cat.groups.map { it.label })
        names.forEachIndexed { i, n ->
            val bases = pinned.filter { it.first == n && !toned(it.second) }.map { it.second }
            assertEquals(n, bases, cat.groups[i].cells.map { it.base })
        }
    }

    @Test fun skinTonesHangOffTheirBaseNotTheGrid() { // EC2
        for (g in cat.groups) for (c in g.cells) {
            assertFalse(c.base, toned(c.base))
            for (t in c.tones) assertTrue("${c.base} $t", toned(t))
        }
        val thumbs = cat.cellOf("👍")!!
        assertEquals(listOf("👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"), thumbs.tones)
        assertEquals("✌️", cat.cellOf("✌🏽")!!.base) // a toned form drops the FE0F its base has
        assertEquals("🤝", cat.cellOf("🫱🏻\u200D🫲🏼")!!.base) // multi-person mixed tones parse
        assertEquals(25, cat.cellOf("💏")!!.tones.size) // 5 single + 20 mixed
        // every base that has toned forms in Unicode offers them
        val withTones = pinned.filter { toned(it.second) }.map { cat.cellOf(it.second)!!.base }.toSet()
        for (b in withTones) assertTrue(b, cat.cellOf(b)!!.tones.isNotEmpty())
    }

    @Test fun theSpotCheckListIsThere() { // EC1 (field report: 🥹 🫠 🫶 … were missing)
        for (e in listOf("🥹", "🫠", "🫶", "🥲", "🤌", "🫡", "🫣", "🫂", "❤️\u200D🔥", "🇳🇱", "🦭", "🪿"))
            assertNotNull(e, cat.cellOf(e))
        val countries = pinned.count { it.first == "Flags" && it.second.codePointAt(0) in 0x1F1E6..0x1F1FF }
        assertTrue(countries > 250) // vs 30 country flags in the r8 list
        assertEquals(countries, cat.groups.single { it.id == "banderas" }.cells.count { it.base.codePointAt(0) in 0x1F1E6..0x1F1FF })
        assertNotNull("keycap # (starts with '#', must not read as a comment)", cat.cellOf("#\uFE0F\u20E3"))
    }

    @Test fun malformedAssetLinesAreSkipped() { // EC4
        val c = EmojiCatalog.parse("@caras\n😀\nhola mundo\n\n@nope\n🐶\n@personas\n👍 👍🏽 junk 👍🏽\n😀\n# c\n@banderas")
        assertEquals(listOf("caras", "personas"), c.groups.map { it.id })
        assertEquals(listOf("😀"), c.groups[0].cells.map { it.base })
        assertEquals(listOf("👍🏽"), c.groups[1].cells.single().tones) // junk and the duplicate dropped, the dup base too
        assertEquals(0, EmojiCatalog.parse("").baseCount)
    }

    @Test fun recentsAcceptEveryCatalogEmojiAndOldFiles() { // EC6
        for (g in cat.groups) for (c in g.cells) for (e in listOf(c.base) + c.tones) assertTrue(e, EmojiRecents.valid(e))
        // an r8–r10 recents file keeps loading
        val old = listOf("😂", "❤️", "👍", "🇨🇱", "1️⃣").joinToString("\u001F")
        assertEquals(listOf("😂", "❤️", "👍", "🇨🇱", "1️⃣"), EmojiRecents.decode(old).items())
        val r = EmojiRecents()
        assertTrue(r.push("👍🏽"))
        assertEquals("👍🏽", r.items().first()) // stored toned
        assertFalse(EmojiRecents.valid("hola")); assertFalse(EmojiRecents.valid("a\u001Fb"))
    }

    @Test fun toneDefaultsAreChosenClearedAndValidated() { // ET1 + ET3
        val t = EmojiTones()
        val thumbs = cat.cellOf("👍")!!
        assertEquals("👍", t.shown("👍"))
        assertTrue(t.choose(thumbs, "👍🏽"))
        assertEquals("👍🏽", t.shown("👍"))
        assertEquals("✋", t.shown("✋")) // no leak to other emoji
        assertFalse(t.choose(thumbs, "👍🏽")) // no change, no write
        assertFalse(t.choose(thumbs, "✋🏽")) // not this cell's variant
        assertEquals("👍🏽", EmojiTones.decode(t.encode(), cat).shown("👍"))
        assertTrue(t.choose(thumbs, "👍")) // the plain form clears it
        assertEquals("👍", t.shown("👍"))
        assertEquals(0, t.size())
        // junk on disk: wrong pair, unknown base, a variant as key, garbage
        val junk = "👍\t✋🏽\n🦄\t🦄🏽\n👍🏽\t👍🏿\nnope\n✋\t✋🏾\n"
        val d = EmojiTones.decode(junk, cat)
        assertEquals(1, d.size())
        assertEquals("✋🏾", d.shown("✋"))
        assertEquals(0, EmojiTones.decode(null, cat).size())
    }
}
