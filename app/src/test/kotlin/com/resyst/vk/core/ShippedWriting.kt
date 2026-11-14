package com.resyst.vk.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * r13c test harness: the IME's correction wiring ([Writing]) over the SHIPPED assets — frequency lists
 * re-ranked for es-CL, regional rules, valid-word DAWGs memory-mapped like ime/Lexicon maps them — and
 * the key path of ResystImeService for one text field: auto-capital from the field's text (Android's
 * CAP_MODE_SENTENCES), letters typed key by key, then the key that ended the word in the live session.
 * Shared by the r13c tests; loaded once per test JVM.
 */
object ShippedWriting {
    private val dir = File("src/main/assets/lexicon")

    private fun map(name: String): ByteBuffer = FileChannel.open(File(dir, name).toPath(), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }

    val validEs: ValidWords by lazy { ValidWords.read(map("valid-es.dawg"))!! }
    val validEn: ValidWords by lazy { ValidWords.read(map("valid-en.dawg"))!! }

    /** The two lexicons exactly as ime/Lexicon builds them for the default region (es-CL). */
    val lexicons: Map<Lang, Suggest> by lazy {
        fun list(n: String) = File(dir, n).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val rule = { name: String -> File(dir, "regional/$name.txt").takeIf { it.exists() }?.readText() }
        mapOf(
            Lang.ES to Suggest.build(Lang.ES, list("es.txt"), Region.ES_CL, rule).also { it.prepare(); it.valid = validEs },
            Lang.EN to Suggest.build(Lang.EN, list("en.txt"), Region.ES_CL, rule).also { it.prepare(); it.valid = validEn },
        )
    }

    /** The IME's wiring with the owner's defaults: Spanish keyboard, bilingual on, Chile, corrections on. */
    fun writing(personal: PersonalModel? = null, rejections: Rejections? = null) =
        Writing(get = { lexicons[it] }, peek = { lexicons[it] }).also { w ->
            w.settings = KbSettings()
            w.personal = { personal }
            w.rejections = { rejections }
            w.lang = w.settings.lang
        }

    /** Android's TextUtils.getCapsMode(CAP_MODE_SENTENCES), as the IME's refreshContext reads it. */
    fun capsHint(text: String): Boolean {
        var i = text.length
        while (i > 0 && (text[i - 1] in "\"'([{¿¡«“‘")) i--
        var j = i
        while (j > 0 && (text[j - 1] == ' ' || text[j - 1] == '\t')) j--
        if (j == 0 || text[j - 1] == '\n') return true
        if (j == i) return false
        while (j > 0 && text[j - 1] in "\"')]}»”’") j--
        return j > 0 && text[j - 1] in ".?!"
    }

    /** The key that ended a word in the live session: space, Enter (a new line here), or a mark. */
    fun endKey(end: String): Key = when (end) {
        " " -> Key(KeyType.SPACE, " ")
        "\n" -> Key(KeyType.ENTER, "⏎")
        else -> Key(KeyType.CHAR, end, end)
    }

    /** ResystImeService.WINDOW (= Tokens.READ_WINDOW): the chars it reads before the cursor. */
    const val WINDOW = Tokens.READ_WINDOW

    /**
     * One text field driven exactly like ResystImeService drives it (onKeyCommit → afterKey →
     * rejected / keep / learn; the bar's ↶ chip → barOffer): the engine, the learner over [model],
     * the rejection store [store] (null = the write gate is closed). [sameEditorAs] = the user came
     * back to that field (its engine memory is kept, r13 LR1).
     */
    class ImeField(private val w: Writing, private val model: PersonalModel?, private val store: Rejections?, sameEditorAs: ImeField? = null) {
        val engine: KeyboardEngine = sameEditorAs?.engine ?: KeyboardEngine()
        private val learner = Learner { model }
        private var learnedLang = Lang.ES
        var before = ""
        var after = ""
        val text: String get() = before + after

        init {
            engine.start(FieldInfo(FieldKind.TEXT, multiLine = true, action = ImeAction.NONE, autoCap = false), sameField = sameEditorAs != null)
            engine.corrector = w.corrector()
        }

        fun key(k: Key) {
            val b = before
            val ends = k.type == KeyType.SPACE || k.type == KeyType.ENTER || (k.type == KeyType.CHAR && KeyboardEngine.endsWord(k.text))
            if (ends) w.lang = w.detect(b, b.length >= WINDOW)
            val outs = engine.press(k, b, 0L, if (ends || k.type == KeyType.BACKSPACE) after else "")
            before = Edits.apply(b, outs)
            afterKey(b, outs, if (k.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY, late = ends)
        }

        /** Types [s]: ' ' = space, '\n' = Enter, '⌫' = backspace, anything else a key. */
        fun type(s: String) {
            for (c in s) key(
                when (c) {
                    ' ' -> Key(KeyType.SPACE, " ")
                    '\n' -> Key(KeyType.ENTER, "⏎")
                    '⌫' -> Key(KeyType.BACKSPACE, "⌫")
                    else -> Key(KeyType.CHAR, c.toString(), c.toString())
                },
            )
        }

        /** The bar's ↶ chip (null when it is not on offer). */
        fun undoChip(): Boolean {
            val b = before
            val outs = engine.undoCorrection(b) ?: return false
            before = Edits.apply(b, outs)
            val kept = engine.takeReverted()
            val forGood = rejected(late = false)
            if (kept != null) learner.kept(learnedLang, learnedLang, b, outs, false, kept, mark = forGood) else learner.reset()
            return true
        }

        /** The user puts the cursor at [at] (a tap in the text). */
        fun cursor(at: Int) {
            val t = text
            before = t.substring(0, at); after = t.substring(at)
            learner.reset()
        }

        private fun afterKey(b: String, outs: List<Out>, kind: Learner.Edit, late: Boolean) {
            val kept = engine.takeReverted()
            val forGood = rejected(late)
            if (kept != null) learner.kept(learnedLang, learnedLang, b, outs, false, kept, mark = forGood)
            else learn(b, outs, kind)
        }

        private fun learn(b: String, outs: List<Out>, kind: Learner.Edit) {
            val lang = if (kind == Learner.Edit.BACKSPACE) learnedLang else w.lang
            learner.afterEdit(lang, b, outs, windowFull = b.length >= WINDOW, kind = kind)
            if (kind != Learner.Edit.BACKSPACE) learnedLang = w.lang
        }

        private fun rejected(late: Boolean): Boolean {
            val (typed, fix) = engine.takeRejection() ?: return false
            val m = model ?: return false
            val s = store ?: return false
            if (late) m.unlearn(learnedLang, null, fix, false)
            return s.strike(typed, fix)
        }
    }

    /**
     * A fresh multi-line text field holding [left]; [typed] is typed key by key, then [end] is pressed.
     * Returns what follows [left] (the committed word, its ending and anything the engine added).
     */
    fun replay(left: String, typed: String, end: String, w: Writing = writing()): String {
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
        w.lang = w.detect(before, before.length >= WINDOW) // the service: writeLang = detectLang(before) before the press
        text = text.dropLast(before.length) + Edits.apply(before, engine.press(endKey(end), before, 10_000L, ""))
        check(text.startsWith(left)) { "the field keeps its left context" }
        return text.substring(left.length)
    }
}
