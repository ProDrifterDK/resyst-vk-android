package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * r13a phase B acceptance: Alan's live session s1 (Pixel 8 Pro, VK 0.8.1, 2026-11-13) replayed
 * through the REAL engine — [KeyboardEngine] + the shipped corrector ([Writing], the object the IME
 * runs) + the shipped assets (frequency lists re-ranked for es-CL, regional rules, valid-word DAWGs
 * mapped like the IME maps them). Each item: the field holds `left`, `typed` is typed key by key
 * (auto-capitalized like the IME would), space is pressed, the committed word is read back.
 *
 * Fixture: src/test/resources/r13/s1-items.json (cat / left / typed / gold / vk_final only: Alan's
 * test text). Writes build/r13/replay.txt + replay.json (per category: this build, VK 0.8.1's
 * vk_final, the offline experiment's S1, the misses). Failure modes: RW1–RW4, AC1–AC5, NW1–NW10.
 */
class Round13ReplayTest {
    private val dir = File("src/main/assets/lexicon")

    private fun map(name: String): ByteBuffer = FileChannel.open(File(dir, name).toPath(), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }

    private val validEs by lazy { ValidWords.read(map("valid-es.dawg"))!! }
    private val validEn by lazy { ValidWords.read(map("valid-en.dawg"))!! }

    /** The two lexicons exactly as ime/Lexicon builds them for the default region (es-CL). */
    private val lexicons: Map<Lang, Suggest> by lazy {
        fun list(n: String) = File(dir, n).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val rule = { name: String -> File(dir, "regional/$name.txt").takeIf { it.exists() }?.readText() }
        mapOf(
            Lang.ES to Suggest.build(Lang.ES, list("es.txt"), Region.ES_CL, rule).also { it.prepare(); it.valid = validEs },
            Lang.EN to Suggest.build(Lang.EN, list("en.txt"), Region.ES_CL, rule).also { it.prepare(); it.valid = validEn },
        )
    }

    /** The IME's wiring with Alan's defaults: Spanish keyboard, bilingual on, Chile, corrections on. */
    private fun writing(personal: PersonalModel? = null) = Writing(get = { lexicons[it] }, peek = { lexicons[it] }).also { w ->
        w.settings = KbSettings()
        w.personal = { personal }
        w.lang = w.settings.lang
    }

    class Item(val cat: String, val left: String, val typed: String, val gold: String, val vkFinal: String?)

    private val items: List<Item> by lazy {
        (MiniJson.parse(File("src/test/resources/r13/s1-items.json").readText()) as List<*>).map {
            val m = it as Map<*, *>
            Item(m["cat"] as String, m["left"] as String, m["typed"] as String, m["gold"] as String, m["vk_final"] as String?)
        }
    }

    /** Android's TextUtils.getCapsMode(CAP_MODE_SENTENCES), as the IME's refreshContext reads it. */
    private fun capsHint(text: String): Boolean {
        var i = text.length
        while (i > 0 && (text[i - 1] in "\"'([{¿¡«“‘")) i--
        var j = i
        while (j > 0 && (text[j - 1] == ' ' || text[j - 1] == '\t')) j--
        if (j == 0 || text[j - 1] == '\n') return true
        if (j == i) return false
        while (j > 0 && text[j - 1] in "\"')]}»”’") j--
        return j > 0 && text[j - 1] in ".?!"
    }

    /** [micros] = the space press (the bar computed the correction just before, as on the device); [coldMicros] = the full correction search alone. */
    class Replayed(val committed: String, val bar: List<String>, val micros: Long, val coldMicros: Long)

    /** Types [typed] after [left] in a fresh text field and presses space, like the IME does. */
    private fun replay(left: String, typed: String, w: Writing = writing()): Replayed {
        val engine = KeyboardEngine()
        engine.start(FieldInfo(FieldKind.TEXT, multiLine = true, action = ImeAction.NONE, autoCap = true))
        w.lang = w.settings.lang
        engine.corrector = w.corrector()
        var text = left
        for (c in typed) {
            engine.updateAutoCap(capsHint(text))
            val before = text.takeLast(WINDOW)
            text = text.dropLast(before.length) + Edits.apply(before, engine.press(Key(KeyType.CHAR, c.toString(), c.toString()), before, 0L))
        }
        engine.updateAutoCap(capsHint(text))
        val before = text.takeLast(WINDOW)
        val full = before.length >= WINDOW
        w.lang = w.detect(before, full) // the service: writeLang = detectLang(before) before the press
        val lex = w.lexiconFor(w.lang)!!
        val word = Suggest.currentWord(before)
        lex.best("~") // drop the cached answer: time the whole search once
        val c0 = System.nanoTime()
        lex.best(word.lowercase())
        val coldMicros = (System.nanoTime() - c0) / 1000
        val bar = Bar.words(before, "", full, w.lang, lex, w.personal(), engine.shift, clean = true, vocab = w.vocab())
        val t0 = System.nanoTime()
        val outs = engine.press(Key(KeyType.SPACE, " "), before, 10_000L, "")
        val micros = (System.nanoTime() - t0) / 1000
        text = text.dropLast(before.length) + Edits.apply(before, outs)
        assertTrue("the field keeps its left context", text.startsWith(left))
        return Replayed(text.substring(left.length).trimEnd(' '), bar, micros, coldMicros)
    }

    private fun valid(w: String) = validEs.contains(w) || validEn.contains(w) ||
        validEs.contains(w.replaceFirstChar { it.uppercaseChar() }) || validEn.contains(w.replaceFirstChar { it.uppercaseChar() })

    @Test fun sessionReplay() {
        repeat(2) { items.take(40).forEach { replay(it.left, it.typed) } } // JIT warm-up for the timing column
        class Cat(val name: String) { var n = 0; var ok = 0; var vk = 0; var vkKnown = 0; val misses = ArrayList<String>(); val broken = ArrayList<String>() }
        val cats = LinkedHashMap<String, Cat>()
        val times = ArrayList<Long>()
        val cold = ArrayList<Long>()
        for (it in items) {
            val r = replay(it.left, it.typed)
            times += r.micros
            cold += r.coldMicros
            val c = cats.getOrPut(it.cat) { Cat(it.cat) }
            c.n++
            val ok = r.committed.lowercase() == it.gold.lowercase()
            if (ok) c.ok++ else c.misses += "${it.typed}→${r.committed.lowercase()} (gold ${it.gold})"
            if (it.cat.endsWith("_ok") || it.cat.endsWith("_keep")) if (r.committed.lowercase() != it.typed.lowercase()) c.broken += "${it.typed}→${r.committed}"
            if (it.vkFinal != null) { c.vkKnown++; if (it.vkFinal.lowercase() == it.gold.lowercase()) c.vk++ }
        }
        // the offline experiment's "S1 valid-list + geometry" (evidence/report_q4phon.txt)
        val s1 = mapOf("s1_typo" to "38/47", "s1_accent" to "5/12", "s1_ok" to "177/181", "ctx_fix" to "0/26", "ctx_accent" to "1/14", "ctx_keep" to "35/35")
        val sorted = times.sorted()
        val coldSorted = cold.sorted()
        val report = StringBuilder("r13 session replay (KeyboardEngine + Writing.corrector + shipped assets), ${items.size} items\n")
        report.append(String.format("%-11s %10s %12s %12s\n", "category", "r13", "VK 0.8.1", "exp. S1"))
        for (c in cats.values) report.append(String.format("%-11s %10s %12s %12s\n", c.name, "${c.ok}/${c.n}", if (c.vkKnown > 0) "${c.vk}/${c.vkKnown}" else "-", s1[c.name] ?: "-"))
        for (c in cats.values) if (c.misses.isNotEmpty()) report.append("misses ${c.name}: ${c.misses.joinToString(", ")}\n")
        // why each typo was left: the best spelling families of its search (Suggest.trace)
        for (it in items.filter { i -> i.cat == "s1_typo" }) {
            var why = "protected (R1) or no candidate"
            lexicons.values.forEach { l -> l.trace = { t -> why = t } }
            val r = replay(it.left, it.typed)
            lexicons.values.forEach { l -> l.trace = null }
            if (r.committed.lowercase() != it.gold.lowercase()) report.append("  why ${it.typed}: $why\n")
        }
        report.append("space press, JVM: median ${sorted[sorted.size / 2]} us, p90 ${sorted[sorted.size * 9 / 10]} us, max ${sorted.last()} us\n")
        report.append("whole correction search (uncached), JVM: median ${coldSorted[coldSorted.size / 2]} us, p90 ${coldSorted[coldSorted.size * 9 / 10]} us, max ${coldSorted.last()} us\n")
        File("build/r13").mkdirs()
        File("build/r13/replay.txt").writeText(report.toString())
        val json = StringBuilder("{\"items\":${items.size},\"categories\":{")
        cats.values.forEachIndexed { i, c ->
            if (i > 0) json.append(',')
            json.append(MiniJson.quote(c.name)).append(":{\"fixed\":${c.ok},\"total\":${c.n},\"vk_final\":")
                .append(if (c.vkKnown > 0) MiniJson.quote("${c.vk}/${c.vkKnown}") else "null")
                .append(",\"experiment_s1\":").append(MiniJson.quote(s1[c.name] ?: "-"))
                .append(",\"misses\":[").append(c.misses.joinToString(",") { MiniJson.quote(it) }).append("]")
                .append(",\"broken\":[").append(c.broken.joinToString(",") { MiniJson.quote(it) }).append("]}")
        }
        json.append("},\"space_us\":{\"median\":${sorted[sorted.size / 2]},\"p90\":${sorted[sorted.size * 9 / 10]},\"max\":${sorted.last()}}")
        json.append(",\"search_us\":{\"median\":${coldSorted[coldSorted.size / 2]},\"p90\":${coldSorted[coldSorted.size * 9 / 10]},\"max\":${coldSorted.last()}}}")
        File("build/r13/replay.json").writeText(json.toString())
        println(report)

        val ok = cats.getValue("s1_ok")
        assertTrue("RW1/RW2: at most 1 good word broken: ${ok.broken}", ok.broken.size <= 1)
        assertTrue("RW1: a Hunspell-valid word was broken: ${ok.broken}", ok.broken.none { valid(it.substringBefore('→')) })
        assertEquals("RW1: ctx_keep untouched: ${cats.getValue("ctx_keep").broken}", 35, cats.getValue("ctx_keep").ok)
        assertTrue("NW1: s1_typo fixed ${cats.getValue("s1_typo").ok}/47: ${cats.getValue("s1_typo").misses}", cats.getValue("s1_typo").ok >= 40)
    }

    // ── the named rows of the brief ──────────────────────────────────────
    @Test fun bastionStaysAndTheBarOffersBastionFirst() { // AC3 (VK 0.8.1: bastion → bastón ×4)
        for (left in listOf("El servidor del ", "Al final, lo importante es que el ")) {
            val r = replay(left, "bastion")
            assertEquals("bastion", r.committed)
            assertEquals("bastión", r.bar.first())
        }
    }

    @Test fun realWordsAndSlangSurvive() { // RW1, RW2
        assertEquals("anduvieron", replay("satisficiera, condujéramos, ", "anduvieron").committed)
        assertEquals("cachai", replay("No molesta. Cachai, GrapheneOS no ", "cachai").committed)
        assertEquals("fome", replay("GrapheneOS me gusta harto porque es ", "fome").committed)
        assertEquals("autocorrector", replay("Si el ", "autocorrector").committed)
        assertEquals("grapheneos", replay("Me gusta ", "grapheneos").committed)
    }

    @Test fun chileanWordsSurvive() { // RW2, NW11: everyday Chilean words outside Hunspell stay as typed
        val chilean = ("cachai fome pololo altiro pucha filo carrete lucas flaite cuico pega copete guagua pituto pitutos chela " +
            "caleta pana chanta piola jote pasao pelao cachar chamullo chuta harto nica pololear tincar tuto wena weón wn " +
            "voh wena sipo piscola zapallo pichanga polola quiltro chupalla guata chorito palta porotos choclo humita pebre " +
            "sopaipilla liceo profe finde lesera ramadas chascón cachái pelusón " +
            "culiao culiaos culiá conchetumadre weones weonas weá").split(" ")
        val changed = chilean.mapNotNull { w -> replay("Oye, ", w).committed.takeIf { it.lowercase() != w }?.let { "$w→$it" } }
        assertEquals("changed: $changed", emptyList<String>(), changed)
        // accent completion of slang the region lists with its accent is still welcome (R2 / A2)
        assertEquals("bacán", replay("Estuvo ", "bacan").committed)
        // typed without the long-press accent: the same word or its accented form, never another word
        val weon = replay("Oye, ", "weon").committed
        val culia = replay("Oye, ", "culia").committed
        assertTrue("weon → $weon", weon in setOf("weon", "weón"))
        assertTrue("culia → $culia", culia in setOf("culia", "culiá"))
        // the curated words are in the regional vocabulary: offered while typing (wuena is gone: w/hu → w)
        val es = lexicons.getValue(Lang.ES)
        for ((prefix, word) in listOf("wen" to "wena", "culia" to "culiao", "culiao" to "culiaos", "conchet" to "conchetumadre", "weon" to "weones", "weon" to "weonas")) {
            assertTrue("$prefix → $word: ${es.suggest(prefix, 6)}", word in es.suggest(prefix, 6))
        }
        assertTrue("wuena is not a Chilean spelling: ${es.suggest("wue", 6)}", "wuena" !in es.suggest("wue", 6))
    }

    @Test fun singleAccentVariantsAreApplied() { // AC1, AC4
        assertEquals("soberanía", replay("Eso es lo que buscamos, ", "soberania").committed)
        assertEquals("pusiésemos", replay("anduvieron y ", "pusiesemos").committed)
        assertEquals("caído", replay("El servidor se ha ", "caido").committed)
        assertEquals("Caído", replay("Listo. ", "caido").committed) // auto-capital kept
        assertEquals("condujéramos", replay("cupiese, satisficiera, ", "condujeramos").committed)
    }

    @Test fun severalVariantsAreOfferedNotApplied() { // AC2
        val r = replay("No sé ", "que")
        assertEquals("que", r.committed)
        assertTrue(r.bar.toString(), "qué" in r.bar)
        assertEquals("esta", replay("Mi mamá ", "esta").committed)
    }

    @Test fun thumbTyposVkLeftAreFixed() { // NW1: the misses VK 0.8.1 left that the brief names
        for ((typed, gold) in listOf("calna" to "calma", "listl" to "listo", "busvamos" to "buscamos", "ñarte" to "parte", "impoetsnte" to "importante", "pillae" to "pillar")) {
            assertEquals(typed, gold, replay("Hoy ", typed).committed)
        }
    }

    @Test fun theFixLeavesAccentsToR2AndEnglishTyposGetEnglishWords() { // NW7, NW8
        assertEquals("pixel", replay("revisando el ", "pkxel").committed) // pixel is a word: no píxel forced
        assertEquals("build", replay("Habríamos terminado antes si el ", "vuild").committed)
        assertEquals("check", replay("This sentence is in English, just to ", "checl").committed)
    }

    @Test fun withoutTheValidSetTheR12PathRuns() { // RW3: before the DAWG is mapped nothing half-works
        fun list(n: String) = File(dir, n).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val rule = { name: String -> File(dir, "regional/$name.txt").takeIf { it.exists() }?.readText() }
        val bare = Suggest.build(Lang.ES, list("es.txt"), Region.ES_CL, rule)
        val r12 = Suggest(Regional.forRegion(list("es.txt"), Region.ES_CL, rule), Lang.ES)
        for (w in listOf("bastion", "calna", "soberania", "qje", "anduvieron")) assertEquals(w, r12.correction(w, false), bare.correction(w, false))
        assertEquals("bastón", bare.correction("bastion", false)) // VK 0.8.1's answer, until the set is mapped
        bare.valid = validEs
        assertEquals("monolingual Spanish: R2 completes the accent", "bastión", bare.correction("bastion", false))
        bare.foreign = lexicons.getValue(Lang.EN)
        assertNull("bilingual: bastion is an English word (AC3)", bare.correction("bastion", false))
    }

    private companion object {
        /** ResystImeService.WINDOW (= Tokens.READ_WINDOW): the chars it reads before the cursor. */
        const val WINDOW = Tokens.READ_WINDOW
    }
}
