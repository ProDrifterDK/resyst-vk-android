package com.resyst.vk.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r14b (TR1, TR10): no user-visible string literal in the app's Kotlin. Every human-readable literal in a UI file
 * must be a resource; what is left is an explicit, justified allow-list (symbols, ids, protocol words, stored data).
 * `-Dvk.mainDir=<dir with kotlin/>` points the scan at another tree (used to prove the guard bites on 0.14.0).
 */
class TranslationGuardTest {

    /** A literal found in a source file: where, what, and whether its line is a visible-text sink. */
    data class Lit(val file: String, val line: Int, val text: String, val sink: Boolean)

    private val mainDir get() = File(System.getProperty("vk.mainDir") ?: "src/main")

    @Test fun noHardcodedUserVisibleText() {
        val found = violations(mainDir)
        assertTrue(
            "user-visible text must be a string resource (values/ + values-en/), or be allow-listed in TranslationGuardTest with a reason. " +
                "${found.size} found:\n" + found.joinToString("\n") { "  ${it.file}:${it.line}  \"${it.text}\"" },
            found.isEmpty(),
        )
    }

    @Test fun theAllowListHasNoDeadEntries() {
        // An entry that no longer matches a literal hides nothing but rots: the list stays an exact inventory.
        val live = HashMap<String, MutableSet<String>>()
        for ((file, text) in allLiterals(mainDir).map { it.file to it.text }) live.getOrPut(file) { HashSet() }.add(text)
        val dead = ALLOW.flatMap { (f, set) -> set.filter { it !in (live[f] ?: emptySet()) }.map { "$f: \"$it\"" } }
        val files = ALLOW.keys.filter { !File(mainDir, "kotlin/com/resyst/vk").walkTopDown().any { f -> f.name == it } }
        assertTrue("allow-list entries without a literal: $dead / files gone: $files", dead.isEmpty() && files.isEmpty())
    }

    @Test fun everyDataFileIsStillThere() {
        val names = File(mainDir, "kotlin").walkTopDown().filter { it.extension == "kt" }.map { it.name }.toSet()
        assertEquals("exempt files that no longer exist", emptySet<String>(), DATA_FILES.keys - names)
    }

    // ---- the guard's own rules (what counts as visible text) ----

    @Test fun humanTextIsRecognised() {
        for (s in listOf("Hola mundo", "Tema", "Cancelar", "Descartar el aviso", "vacía", "ñandú", "Buscar", "1 vez", "$1 min ago"))
            assertTrue(s, isHuman(s))
    }

    @Test fun idsAndProtocolWordsAreNot() {
        for (s in listOf("update-card", "pair.\u0001.\u0001", "#5ee0a8", "emoji_recents.txt", "lab", "application/json", "^[0-9a-f]{64}$", "DEPRECATION", "\u0001", "x", "%d", "→"))
            assertFalse(s, isHuman(s))
    }

    @Test fun theLexerReadsKotlinStrings() {
        val src = "val a = \"Hola \${x + \"y\"} fin\"\n// \"comment\"\nval b = \"\"\"raw\n text\"\"\"\nval c = '\"' + \"d\\\"q\"\n"
        val lits = lex(src).map { it.second }
        assertEquals(listOf("Hola \u0001 fin", "raw\n text", "d\\\"q"), lits)
    }

    @Test fun sinksAreCaughtWhateverTheLiteralLooks() {
        val src = "x.text = \"ok\"\nval t = \"ok\"\nsetTitle(\"hi\")\nLog.d(TAG, \"Hola mundo\")\n"
        val lits = scan("T.kt", src)
        assertEquals(listOf(1, 3), lits.filter { it.sink }.map { it.line })
        assertTrue(scan("T.kt", "Log.d(TAG, \"Hola mundo\")").none { isViolation(it) })
        assertTrue(scan("T.kt", "val s = \"Hola mundo\"").any { isViolation(it) })
    }

    // ---- machinery ----

    private fun violations(main: File): List<Lit> = allLiterals(main).filter { isViolation(it) }

    private fun allLiterals(main: File): List<Lit> =
        File(main, "kotlin").walkTopDown().filter { it.extension == "kt" }.sortedBy { it.path }
            .flatMap { f -> scan(f.name, f.readText()) }.toList()

    private fun isViolation(l: Lit): Boolean {
        if (l.file in DATA_FILES) return false
        if (l.text in (ALLOW[l.file] ?: emptySet())) return false
        return if (l.sink) hasLetters(l.text) else isHuman(l.text)
    }

    companion object {
        /** Files whose literals are data, not interface: lexicon seeds, protocol/JSON keys, DSP, model parsing. */
        val DATA_FILES: Map<String, String> = mapOf(
            "Seeds.kt" to "lexicon seed words (typing data)",
            "KeySynth.kt" to "DSP filter names and WAV chunk tags",
            "MiniJson.kt" to "JSON syntax",
            "KlipyParse.kt" to "KLIPY JSON field names",
            "KlipyUrls.kt" to "KLIPY URL parts",
            "ContextKeep.kt" to "context-offer word lists (typing data)",
            "ContextSegments.kt" to "context-offer sentence data (typing data)",
            "ContextTokens.kt" to "context-offer tokenizer data",
            "ContextNgram.kt" to "context-offer model format",
            "ContextCandidates.kt" to "context-offer candidate rules (typing data)",
            "ContextCheck.kt" to "context-offer check rules (typing data)",
            "ContextOffers.kt" to "context-offer rules (typing data)",
            "Suggest.kt" to "suggestion engine (typing data)",
            "TypoModel.kt" to "corrector keyboard geometry (typing data)",
            "PersonalModel.kt" to "personal model file format",
            "PairMemory.kt" to "pair memory file format",
            "ValidWords.kt" to "valid-word set format",
            "Writing.kt" to "writing rules (typing data)",
            "Profanity.kt" to "profanity word list (typing data)",
            "Tokens.kt" to "tokenizer data",
            "Learner.kt" to "learning rules (typing data)",
            "FieldPolicy.kt" to "Android input-type names",
            "Rejections.kt" to "rejection file format",
            "ValueMemory.kt" to "value memory file format",
            "KeyboardEngine.kt" to "typing logic (typed output)",
            "BiLang.kt" to "bilingual suggestion rules (typing data)",
            "ClipboardHistory.kt" to "history file format",
            "PersonalStore.kt" to "personal file names and logs",
            "ClipStore.kt" to "clipboard file names and logs",
            "WhatsNewStore.kt" to "preference keys and logs",
            "SubtypeSync.kt" to "preference keys and logs",
            "GifOptIn.kt" to "preference keys",
            "GifFeed.kt" to "logs and decoder names",
            "Lexicon.kt" to "asset names and logs",
            "ContextModel.kt" to "asset names and logs",
            "EmojiAsset.kt" to "asset names",
            "Fonts.kt" to "font asset names",
            "HapticPlayer.kt" to "logs",
            "KeySoundPlayer.kt" to "logs",
            "SystemLook.kt" to "system colour resource names",
            "SettingsRepo.kt" to "preference file name",
            "GifStore.kt" to "preference file name",
            "ConnectionBook.kt" to "preference keys and logs",
            "UpdateChecker.kt" to "release.json field names, parse reasons (logged, never shown) and URL shapes",
            "Drawn.kt" to "the legibility table's descriptions: only in test reports, never drawn",
        )

        /** Literals that look like text and are not: a few legitimate ones, each with its reason. */
        val ALLOW: Map<String, Set<String>> = mapOf(
            // stored data: the seed temas keep their Spanish names byte for byte; screens show tema_seed_<id> until renamed (TR3)
            "Profiles.kt" to setOf("Resyst", "Arcade", "Pizarra", "Noche", "Día", "Juego", "Escritura"),
            // layout data: the Spanish letter row, and the space bar's typing-language label (not the interface language, TR4)
            "KeyboardLayouts.kt" to setOf("asdfghjklñ", "español"),
            // the brand, as the app's own name in its header and in the clip description
            "SettingsActivity.kt" to setOf(
                "Resyst VK", // the brand
                "Añ", // the font tiles' sample key: shows the ñ glyph of each font, the same in every language
                "está", // sample suggestions in the live keyboard preview: Spanish typing data
                "d MMM yyyy HH:mm", // a date skeleton for the device's locale, not text
            ),
            "ResystImeService.kt" to setOf(
                "Resyst VK", // the brand, as the label of the content description handed to the app
                "typed past", // a reason id written to the log
                "candidatesUs=\u0001 cpuUs=\u0001 memory=\u0001", // a developer timing line
            ),
            "Updater.kt" to setOf("ResystVK-Updater", "User-Agent", "Accept", "Cache-Control"), // HTTP
            "KlipyClient.kt" to setOf("ResystVK", "GET media (chosen GIF)", "too large", "User-Agent", "Content-Type"), // HTTP and logs
        )

        private val SINKS = listOf(
            ".text =", " text =", "setText(", "contentDescription", "setContentDescription(", ".hint =", "setHint(", "setTitle(",
            "setMessage(", "setPositiveButton(", "setNegativeButton(", "setNeutralButton(", "Toast.makeText(", "setContentTitle(",
            "setContentText(", "setSubText(", "setTicker(", "announceForAccessibility(", "setLabel(", "stateDescription",
        )

        /** A line that only logs or throws: developer text, never shown. */
        private val DEV_LINE = Regex("""\b(Log\.[a-z]\(|dbg\(|logd\(|error\(|require\(|check\(|requireNotNull\(|IllegalArgumentException\(|IllegalStateException\(|throw )|@Suppress|^\s*(private )?const val TAG\b|@Deprecated\(""")

        private val IDISH = Regex("""^[a-z0-9_.:/\-#+*?<>=\[\](){}|^,;@!&%$\\\u0001 ]*$""")

        fun hasLetters(s: String) = Regex("""\p{L}{2}""").containsMatchIn(s.replace('\u0001', ' '))

        fun isHuman(s: String): Boolean {
            val t = s.replace('\u0001', ' ').trim()
            if (!hasLetters(s)) return false
            if (t.startsWith("^") || t.contains("\\") || t.startsWith("#")) return false
            if (t.any { it.isLetter() && it.code > 127 }) return true
            if (Regex("""\p{L}{2,}\s+\p{L}""").containsMatchIn(t)) return true
            if (Regex("""\p{L}{3,}""").containsMatchIn(t) && t.contains(' ') && t.any { it.isDigit() }) return true // "1 vez"
            if (Regex("""^\p{Lu}\p{Ll}{2,}""").containsMatchIn(t)) return true
            return !IDISH.matches(s) && t.contains(' ')
        }

        fun scan(file: String, src: String): List<Lit> {
            val lines = src.split('\n')
            return lex(src).map { (ln, text) ->
                val line = lines.getOrElse(ln - 1) { "" }
                val dev = DEV_LINE.containsMatchIn(line)
                val tag = line.contains("tag = \"" + text + "\"") // a view tag is an id, even on a line that also sets the description
                Lit(file, ln, text, sink = !dev && !tag && SINKS.any { line.contains(it) }).let { if (dev) it.copy(text = "") else it }
            }.filter { it.text.isNotEmpty() }
        }

        /** (line, content) of each string literal; `${…}` is replaced by U+0001; comments and char literals are skipped. */
        fun lex(src: String): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            var i = 0
            var line = 1
            val n = src.length
            while (i < n) {
                val c = src[i]
                when {
                    c == '\n' -> { line++; i++ }
                    src.startsWith("//", i) -> { while (i < n && src[i] != '\n') i++ }
                    src.startsWith("/*", i) -> {
                        val j = src.indexOf("*/", i + 2).let { if (it < 0) n else it }
                        line += src.substring(i, j).count { it == '\n' }; i = j + 2
                    }
                    src.startsWith("\"\"\"", i) -> {
                        val j = src.indexOf("\"\"\"", i + 3)
                        out.add(line to src.substring(i + 3, j)); line += src.substring(i, j).count { it == '\n' }; i = j + 3
                    }
                    c == '\'' -> i = if (src[i + 1] == '\\') src.indexOf('\'', i + 2) + 1 else i + 3
                    c == '"' -> {
                        val sb = StringBuilder()
                        var j = i + 1
                        while (j < n && src[j] != '"') {
                            val d = src[j]
                            if (d == '\\') { sb.append(src, j, j + 2); j += 2; continue }
                            if (d == '$' && j + 1 < n && src[j + 1] == '{') {
                                var k = j + 2
                                var depth = 1
                                while (depth > 0) {
                                    when (src[k]) {
                                        '{' -> depth++
                                        '}' -> depth--
                                        '"' -> { k++; while (src[k] != '"') { if (src[k] == '\\') k++; k++ } }
                                    }
                                    k++
                                }
                                sb.append('\u0001'); j = k; continue
                            }
                            if (d == '$' && j + 1 < n && (src[j + 1].isLetter() || src[j + 1] == '_')) {
                                var k = j + 1
                                while (k < n && (src[k].isLetterOrDigit() || src[k] == '_')) k++
                                sb.append('\u0001'); j = k; continue
                            }
                            sb.append(d); j++
                        }
                        out.add(line to sb.toString()); i = j + 1
                    }
                    else -> i++
                }
            }
            return out
        }
    }
}
