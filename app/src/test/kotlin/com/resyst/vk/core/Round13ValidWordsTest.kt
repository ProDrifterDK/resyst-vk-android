package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * r13a — the valid-word set over the SHIPPED assets (assets/lexicon/valid-{es,en}.dawg, written by
 * scripts/gen_valid_words.py). Ids map to docs/failure-modes.md (VW1–VW12). The assets are
 * memory-mapped exactly like the IME maps them.
 */
class Round13ValidWordsTest {
    private val dir = File("src/main/assets/lexicon")

    private fun map(name: String): ByteBuffer = FileChannel.open(File(dir, name).toPath(), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }

    private val es: ValidWords by lazy { ValidWords.read(map("valid-es.dawg"))!! }
    private val en: ValidWords by lazy { ValidWords.read(map("valid-en.dawg"))!! }

    // ── VW1/VW2: what is a word ──────────────────────────────────────────
    @Test fun realWordsAreValid() { // VW1: what VK 0.8.1 "corrected" in session s1, plus enclitics
        for (w in "bastión anduvieron soberanía pusiésemos condujéramos configurarlo dámelo caído también".split(" ")) {
            assertTrue("es: $w", es.contains(w))
        }
        for (w in "bastion without fighting".split(" ")) assertTrue("en: $w", en.contains(w))
        // the enclitic layer (Hunspell itself rejects these): infinitive, gerund, tú, usted, nosotros, vosotros
        for (w in "configurárselo configurándolo configúralo configúrelo configurémonos configuradlo configuraos vestíos pruébalo mantenlo sigámonos".split(" ")) {
            assertTrue("es enclitic: $w", es.contains(w))
        }
    }

    @Test fun typosAreNotValid() { // VW2: the generator must not invent forms
        for (w in "calna listl busvamos impoetsnte pkxel clnfkgurarlo".split(" ")) {
            assertFalse("es: $w", es.contains(w))
            assertFalse("en: $w", en.contains(w))
        }
        // wrong stems / accents the enclitic layer could invent (contar → cuéntala, decir → díselo, hacer → hazlo)
        for (w in "cóntala dícelo hácelo tiénelo configuralo configurarselo bastion".split(" ")) assertFalse("es: $w", es.contains(w))
        // digits and compounds are not words here (en COMPOUNDRULE ordinals)
        assertFalse(en.contains("21st"))
        assertFalse(es.contains(""))
    }

    // ── VW4: Hunspell case rules ──────────────────────────────────────────
    @Test fun caseRules() {
        assertTrue(es.contains("caído"))
        assertTrue("sentence-initial capital matches its lowercase entry", es.contains("Caído"))
        assertTrue("ALL CAPS matches", es.contains("CAÍDO"))
        assertTrue("a capitalized-only entry matches itself", es.contains("Chile"))
        assertTrue(es.contains("CHILE"))
        assertFalse("lowercase input never matches a capitalized-only entry", es.contains("chile"))
        assertFalse("mixed case matches exactly only", es.contains("cHile"))
        assertTrue(en.contains("Paris") && en.contains("PARIS"))
        assertFalse(en.contains("paris"))
        assertTrue(en.contains("The"))
    }

    // ── VW5: accent folding (ñ kept, ü folded, NFD and ’ input) ──────────
    @Test fun accentVariants() {
        assertEquals(listOf("caído"), es.accentVariants("caido"))
        assertEquals(listOf("que", "qué"), es.accentVariants("que"))
        assertEquals(listOf("bastión"), es.accentVariants("bastion"))
        assertEquals(listOf("vergüenza"), es.accentVariants("verguenza"))
        assertEquals(listOf("déjame"), es.accentVariants("dejame")) // the accent fix core needs
        assertEquals(listOf("caído"), es.accentVariants("Caido")) // stored form; the caller re-cases
        assertEquals(listOf("ano"), es.accentVariants("ano")) // ñ is a letter: never año
        assertEquals(listOf("año"), es.accentVariants("año"))
        assertEquals(emptyList<String>(), es.accentVariants("peru")) // only Perú exists: case rules apply
        assertEquals(listOf("Perú"), es.accentVariants("Peru"))
        assertEquals(listOf("Panamá", "panamá"), es.accentVariants("Panama")) // the name and the hat
        assertEquals(listOf("caído"), es.accentVariants("cáido")) // the typed accent is folded too
        assertEquals(emptyList<String>(), es.accentVariants("calna"))
        assertTrue("NFD input", es.contains("cai\u0301do"))
        assertTrue("’ is Hunspell's ICONV for '", en.contains("don’t") && en.contains("don't"))
    }

    // ── VW6/VW8: the shipped bytes are the generator's, within budget ────
    @Test fun assetsMatchTheGeneratorManifest() {
        val lines = File("../lexicon-src/valid-words.manifest").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2, lines.size)
        var total = 0L
        for (line in lines) {
            val (name, sha, words, _, bytes) = line.split(" ")
            val f = File(dir, name)
            val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("$name was edited by hand or not regenerated (VW6)", sha, digest)
            assertEquals(bytes.toLong(), f.length())
            assertEquals(words.toInt(), ValidWords.read(map(name))!!.size)
            total += f.length()
        }
        assertTrue("VW8: both assets add ${total} B (> 4 MB)", total <= 4L * 1024 * 1024)
        // entry counts from the generator (r13a: es 2 267 583 incl. 1.6M enclitic forms, en 166 768)
        assertTrue("es words ${es.size}", es.size in 2_000_000..2_600_000)
        assertTrue("en words ${en.size}", en.size in 150_000..190_000)
        assertTrue(es.notice.contains("MPL-1.1+") && en.notice.contains("SCOWL"))
    }

    // ── VW11: a broken asset is absent, never a crash ─────────────────────
    @Test fun brokenAssetsReadAsNull() {
        val good = File(dir, "valid-en.dawg").readBytes()
        assertNotNull(ValidWords.read(ByteBuffer.wrap(good)))
        assertNull("truncated", ValidWords.read(ByteBuffer.wrap(good.copyOf(good.size - 4))))
        assertNull("wrong magic", ValidWords.read(ByteBuffer.wrap(good.copyOf().also { it[0] = 'X'.code.toByte() })))
        assertNull("empty", ValidWords.read(ByteBuffer.allocate(0)))
        // an arc pointing past the end (last arc of the file, target bits set to all ones)
        val bad = good.copyOf().also { for (i in it.size - 4 until it.size - 1) it[i] = 0xFF.toByte() }
        assertNull("arc out of range", ValidWords.read(ByteBuffer.wrap(bad)))
    }

    // ── VW7: licenses travel with the data, FrequencyWords is not "MIT" only ──
    @Test fun licensesAndCredits() {
        val fw = File(dir, "LICENSE-FrequencyWords.txt").readText()
        assertTrue(fw.contains("CC BY-SA 4.0") && fw.contains("creativecommons.org/licenses/by-sa/4.0"))
        assertTrue("the MIT text for the FrequencyWords code stays", fw.contains("MIT License"))
        assertTrue(File(dir, "LICENSE-Hunspell-es.txt").readText().let { it.contains("MPL") && it.contains("Santiago Bosio") && it.contains("MOZILLA PUBLIC LICENSE") })
        assertTrue(File(dir, "LICENSE-Hunspell-en_US.txt").readText().contains("Kevin Atkinson"))
        val readme = File(dir, "README.txt").readText()
        for (f in listOf("es.txt", "en.txt", "valid-es.dawg", "valid-en.dawg")) assertTrue(f, readme.contains(f))
        val settings = File("src/main/kotlin/com/resyst/vk/settings/SettingsActivity.kt").readText()
        assertFalse(settings.contains("FrequencyWords (MIT)"))
        assertTrue(settings.contains("FrequencyWords (CC BY-SA 4.0)") && settings.contains("RLA-ES (MPL)") && settings.contains("SCOWL"))
    }

    // ── VW10/VW12: loaded with the lexicon off the main thread; nothing corrects with it yet ──
    @Test fun wiredButNotUsedByCorrection() {
        val lexicon = File("src/main/kotlin/com/resyst/vk/ime/Lexicon.kt").readText()
        assertTrue(lexicon.contains("io.execute") && lexicon.contains("ValidWords.read("))
        assertTrue("mapped from an uncompressed APK entry", File("build.gradle.kts").readText().contains("noCompress += \"dawg\""))
        for (f in listOf("Suggest.kt", "Bar.kt", "KeyboardEngine.kt", "Learner.kt", "PersonalModel.kt")) {
            assertFalse("$f reads the valid set in phase A", File("src/main/kotlin/com/resyst/vk/core/$f").readText().contains("ValidWords"))
        }
    }

    // ── VW9/VW10: measured, printed for the report (not asserted: machine-dependent) ──
    @Test fun measureLoadHeapAndLookup() {
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(40) }; return rt.totalMemory() - rt.freeMemory() }
        fun load(name: String, mode: String) =
            ValidWords.read(if (mode == "mapped") map(name) else ByteBuffer.wrap(File(dir, name).readBytes()))!!
        fun us(t0: Long) = (System.nanoTime() - t0) / 1e3
        for (name in listOf("valid-es.dawg", "valid-en.dawg")) {
            for (mode in listOf("mapped", "heap")) {
                val before = used()
                val t0 = System.nanoTime()
                val v = load(name, mode)
                val loadMs = us(t0) / 1e3
                // retained heap per instance: hold 10 more so GC noise divides out
                val held = List(10) { load(name, mode) }
                val heapMb = (used() - before) / 11.0 / 1048576.0
                val probes = (if (name.contains("es")) "configurárselo bastión anduvieron caido que calna impoetsnte Chile CAÍDO pusiésemos" else "bastion without fighting paris Paris don't listl")
                    .split(" ")
                var t = System.nanoTime(); v.contains(probes[0]); val firstUs = us(t)
                t = System.nanoTime(); v.contains(probes[1]); val secondUs = us(t)
                t = System.nanoTime(); v.accentVariants(probes[3]); val firstVariantsUs = us(t)
                repeat(2000) { for (p in probes) { v.contains(p); v.accentVariants(p) } } // JIT warm-up
                val n = 20000
                var hits = 0
                t = System.nanoTime()
                repeat(n) { for (p in probes) if (v.contains(p)) hits++ }
                val containsUs = us(t) / (n * probes.size)
                t = System.nanoTime()
                repeat(n) { for (p in probes) hits += v.accentVariants(p).size }
                val variantsUs = us(t) / (n * probes.size)
                println(String.format(java.util.Locale.ROOT,
                    "valid-words: %s mode=%s words=%d load_ms=%.2f heap_mb_per_instance=%.3f first_contains_us=%.1f second_contains_us=%.1f first_accentVariants_us=%.1f contains_us=%.3f accentVariants_us=%.3f held=%d hits=%d",
                    name, mode, v.size, loadMs, heapMb, firstUs, secondUs, firstVariantsUs, containsUs, variantsUs, held.size, hits))
            }
        }
    }
}
