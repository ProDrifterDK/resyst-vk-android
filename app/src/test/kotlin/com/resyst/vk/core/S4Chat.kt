package com.resyst.vk.core

import java.io.File

/**
 * r13f: the owner's chat session s4 (VK 0.11.0 live, Firefox, the Hermes web composer) driven through the REAL
 * engine + the shipped corrector + the shipped assets, the way ResystImeService drives it in THAT field. Ported from
 * the r13e-s4 spike's S4Live with one difference that matters: nothing of r13f's behaviour lives here. The caps
 * answer the IME feeds the engine is [AutoCaps.hint] (production) over [androidCaps] (what the editor answers, Android's
 * TextUtils.getCapsMode); the layer return, the correction before Enter and the space-bar rule are the engine's and
 * SpaceGesture's own. On 8ffd08a (0.11.0) the same harness reproduces the chat (21 of 22 messages byte-identical).
 *
 * The field: inputType 0x4c0a1 sets IME_MULTI_LINE but not MULTI_LINE, so VK makes it single-line (Enter = the
 * field's key: action NONE → KEYCODE_ENTER, which the page takes as "send"); the page then empties the field, which the
 * IME sees as a selection update at offset 0 (no new input start: the symbols layer survived a send live).
 */
object S4Chat {
    class KeyEv(val k: String, val t: Long, val near: String?, val rep: Int)
    class Message(val id: String, val keys: List<KeyEv>)

    private const val RES = "src/test/resources/r13f"

    fun stream(name: String): List<Message> {
        val root = MiniJson.parse(File("$RES/$name").readText()) as Map<*, *>
        return (root["messages"] as List<*>).map { e ->
            val m = e as Map<*, *>
            Message(m["id"] as String, (m["keys"] as List<*>).map { k ->
                val x = k as Map<*, *>
                KeyEv(x["k"] as String, (x["t"] as Number).toLong(), x["near"] as String?, (x["rep"] as Number?)?.toInt() ?: 0)
            })
        }
    }

    /** The key stream as the phone decoded it (0.11.0's space gestures: 6 flicks, 1 drag). */
    val live: List<Message> by lazy { stream("s4-keys.json") }
    /** 0.12.0's space rule on the same touches: the accidental gestures are spaces, the restores are not needed. */
    val intent: List<Message> by lazy { stream("s4-keys-intent.json") }
    /** Every 0.11.0 gesture as a space (the brief's literal reading), Spanish throughout. */
    val allSpace: List<Message> by lazy { stream("s4-keys-allspace.json") }

    val final: List<String> by lazy { File("$RES/s4-final.txt").readLines().filter { it.isNotEmpty() } }
    val intended: List<String> by lazy { File("$RES/s4-intended.txt").readLines().filter { it.isNotEmpty() } }

    /**
     * The editor's caps answer: android.text.TextUtils.getCapsMode(cs, len, CAP_MODE_SENTENCES) != 0 (AOSP): back over
     * " ' and START_PUNCTUATION, paragraph start (optional blanks) → caps; else a blank must precede, then back over
     * " ' and END_PUNCTUATION, and . ? ! (not an abbreviation's / ellipsis' period) → caps. ¿ ¡ are Po: not skipped.
     */
    fun androidCaps(cs: CharSequence): Boolean {
        var i = cs.length
        while (i > 0) {
            val c = cs[i - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.START_PUNCTUATION.toInt()) break
            i--
        }
        var j = i
        while (j > 0 && (cs[j - 1] == ' ' || cs[j - 1] == '\t')) j--
        if (j == 0 || cs[j - 1] == '\n') return true
        if (i == j) return false
        while (j > 0) {
            val c = cs[j - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) break
            j--
        }
        if (j > 0) {
            val c = cs[j - 1]
            if (c == '.' || c == '?' || c == '!') {
                if (c == '.') {
                    var k = j - 2
                    while (k >= 0) {
                        val d = cs[k]
                        if (d == '.') return false
                        if (!Character.isLetter(d)) break
                        k--
                    }
                }
                return true
            }
        }
        return false
    }

    /** One word ending: the letters the engine saw ([typed]), the key that ended it, what the field got. */
    class EndEvent(val msg: String, val typed: String, val end: String, val got: String)

    /**
     * The composer, one instance per session: the engine, the language and the learned words carry over from message
     * to message. [singleLine] = s4's field (Enter is its key); false = a multi-line field.
     */
    class Composer(val singleLine: Boolean = true, val model: PersonalModel? = PersonalModel(), val store: Rejections? = Rejections(),
                   val pairs: PairMemory? = null, val pairGate: () -> Boolean = { true }) {
        val w: Writing = ShippedWriting.writing(model, store)
        val engine = KeyboardEngine()
        private val learner = Learner { model }
        /** r13i: the pair memory's learner, wired as ResystImeService wires it (the write gate open: a normal field). */
        val pairLearner = PairLearner { pairs?.takeIf { pairGate() } }
        /** r13i: the wall clock the pair memory's decay reads (ms). */
        var now = 0L
        /** r13i: the lexicon hooks as the IME builds them (ContextLexicon + the learned words' owns ≥ 2). */
        val pairWords: PairLearner.Words = object : PairLearner.Words {
            override fun isEs(w: String) = OffersOracle.lex.isWord(w)
            override fun isEn(w: String) = OffersOracle.lex.isEn(w)
            override fun owned(w: String) = model?.owns(listOf(Lang.ES, Lang.EN), w, 2) == true
        }
        private val trail = TouchTrail()
        private var learnedLang = Lang.ES
        private var bsRepeats = 0
        var text = ""
        val events = ArrayList<EndEvent>()
        val sent = ArrayList<String>()
        private var msg = ""

        init {
            w.touch = { trail }
            engine.start(FieldInfo(FieldKind.TEXT, multiLine = !singleLine, action = ImeAction.NONE, autoCap = true))
            engine.autoCapEnabled = w.settings.autoCap
            engine.doubleSpacePeriod = w.settings.doubleSpace
            engine.corrector = w.corrector()
            refresh()
        }

        /** onUpdateSelection → refreshContext: the IME's caps hint (the editor's answer + AutoCaps), the touch record. */
        private fun refresh() {
            engine.updateAutoCap(AutoCaps.hint(androidCaps(text), text.takeLast(WINDOW), text.length))
            if (!trail.spells(Suggest.currentWord(text))) trail.reset()
        }

        /** r13f (offers): called after every key with the field's text (null = nobody listens; no effect on typing). */
        var observer: ((Composer, KeyEv) -> Unit)? = null

        fun run(m: Message) {
            msg = m.id
            for (k in m.keys) { key(k); observer?.invoke(this, k) }
        }

        private fun key(k: KeyEv) {
            when {
                k.k == "PAGE" || k.k == "CURSOR" || k.k == "SPACE_HOLD" || k.k == "ENTER" -> Unit
                k.k == "FLICK" -> {
                    // onSpaceFlick: the other language becomes the keyboard's; applySettings + refreshContext
                    w.settings = w.settings.copy(lang = BiLang.other(w.settings.lang))
                    engine.corrector = w.corrector()
                    refresh()
                }
                // a layer key commits nothing: no selection update, so no refresh here (the engine owns CL1)
                k.k.startsWith("LAYER:") -> {
                    val target = when (k.k.removePrefix("LAYER:")) { "SYM" -> Layer.SYMBOLS; "SYM2" -> Layer.SYMBOLS2; else -> Layer.LETTERS }
                    engine.press(Key(KeyType.LAYER, k.k, target = target), text.takeLast(WINDOW), k.t)
                }
                k.k.startsWith("VAR:") -> variant(k.k.removePrefix("VAR:"))
                k.k == "⌫" -> {
                    bsRepeats = 0
                    for (r in 0..k.rep) commit(Key(KeyType.BACKSPACE, "⌫"), k.t + r * 50L, "")
                }
                k.k == "SEND" -> commit(Key(KeyType.ENTER, "⏎"), k.t, "")
                k.k == " " -> commit(Key(KeyType.SPACE, " ", " "), k.t, "")
                else -> commit(Key(KeyType.CHAR, k.k, k.k), k.t, k.near ?: "")
            }
        }

        /** ResystImeService.onKeyCommit. */
        private fun commit(key: Key, now: Long, near: String) {
            val before = text.takeLast(WINDOW)
            val ends = key.type == KeyType.SPACE || key.type == KeyType.ENTER || (key.type == KeyType.CHAR && KeyboardEngine.endsWord(key.text))
            if (ends) w.lang = w.detect(before, before.length >= WINDOW)
            val word = Suggest.currentWord(before)
            val fieldLen = text.length
            var outs = engine.press(key, before, now, "")
            if (key.type == KeyType.BACKSPACE) {
                val span = TextEdit.backspaceSpan(before, bsRepeats++, false)
                if (outs == listOf(Out.Backspace) && span > 2) {
                    outs = listOf(Out.DeleteBefore(span))
                    engine.deleting("", "", before.takeLast(span))
                }
            }
            text = text.dropLast(before.length) + Edits.apply(before, outs)
            afterKey(before, outs, if (key.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY, late = ends)
            trail(key.type, outs, near)
            val sends = outs.any { it is Out.Action || it == Out.EnterKey }
            pairsAfter(before, outs, ends, sends)
            if (ends && word.isNotEmpty()) {
                val s = fieldLen - word.length
                val got = if (text.length < s) "" else text.substring(s).takeWhile { Tokens.isWordChar(it) }
                events += EndEvent(msg, word, if (sends) "SEND" else if (key.type == KeyType.SPACE) " " else key.text, got)
            }
            if (sends) {
                sent += text
                text = "" // the page sends the message and empties the field: a selection update at offset 0
                pairLearner.emptiedFromOutside(pairWords, engine.fieldFixes, now) // r13i: onUpdateSelection, the field empty
            }
            refresh()
        }

        /**
         * r13i: ResystImeService's pair-memory calls after a key: the edit tracked on the key path's short read; at a word
         * end (posted after the key there) and at VK's send, the offers' longer read of the field ([ContextOffers.WINDOW]).
         */
        private fun pairsAfter(before: String, outs: List<Out>, ends: Boolean, sends: Boolean) {
            pairLearner.track(before, outs, before.length >= WINDOW)
            val read = text.takeLast(ContextOffers.WINDOW)
            val whole = read.length < ContextOffers.WINDOW
            if (sends) pairLearner.sent(read, whole, pairWords, engine.fieldFixes, now)
            else if (ends) pairLearner.edited(read, whole, true, pairWords, engine.fieldFixes, now)
            // onUpdateSelection: VK's edit left the whole field empty (the cursor is always at the end here)
            if (!sends && text.isEmpty()) pairLearner.emptiedFromOutside(pairWords, engine.fieldFixes, now)
        }

        /**
         * r13i: ResystImeService.onContextOffer: the live offers of [offers] taken on the offers' read, the edit applied,
         * then the pair memory's tracking (a tapped offer is the user's choice). True when something was applied.
         */
        fun tapOffer(offers: ContextOffers): Boolean {
            val before = text.takeLast(ContextOffers.WINDOW)
            val outs = offers.take(before, whole = before.length < ContextOffers.WINDOW) ?: return false
            text = text.dropLast(before.length) + Edits.apply(before, outs)
            pairLearner.track(before, outs, before.length >= ContextOffers.WINDOW)
            refresh()
            return true
        }

        /** r13i: ResystImeService.onPasteOffer: VK commits the clip; the pair memory holds this field until it is empty. */
        fun paste(clip: String) {
            text += clip
            pairLearner.pasted()
            engine.endProvisional()
            refresh()
        }

        /** r13i: the app sends the message with its own button and empties the field (ResystImeService.onUpdateSelection). */
        fun appSends() {
            sent += text
            text = ""
            pairLearner.emptiedFromOutside(pairWords, engine.fieldFixes, now)
            refresh()
        }

        private fun variant(t: String) {
            val before = text.takeLast(WINDOW)
            val ends = KeyboardEngine.endsWord(t)
            if (ends) w.lang = w.detect(before, before.length >= WINDOW)
            val outs = engine.variant(t, before, "")
            text = text.dropLast(before.length) + Edits.apply(before, outs)
            afterKey(before, outs, Learner.Edit.KEY, late = ends)
            pairsAfter(before, outs, ends, sends = false)
            trail(KeyType.CHAR, outs, "")
            refresh()
        }

        /** A field already holding [left] (the IME reads it: refreshContext). */
        fun load(left: String) {
            text = left
            refresh()
        }

        private fun afterKey(b: String, outs: List<Out>, kind: Learner.Edit, late: Boolean) {
            val kept = engine.takeReverted()
            val forGood = rejected(late)
            if (kept != null) learner.kept(learnedLang, learnedLang, b, outs, false, kept, mark = forGood)
            else {
                val lang = if (kind == Learner.Edit.BACKSPACE) learnedLang else w.lang
                learner.afterEdit(lang, b, outs, windowFull = b.length >= WINDOW, kind = kind)
                if (kind != Learner.Edit.BACKSPACE) learnedLang = w.lang
            }
        }

        private fun rejected(late: Boolean): Boolean {
            val (typed, fix) = engine.takeRejection() ?: return false
            val m = model ?: return false
            val s = store ?: return false
            if (late) m.unlearn(learnedLang, null, fix, false)
            return s.strike(typed, fix)
        }

        /** ResystImeService.trail (r13d TB2). */
        private fun trail(type: KeyType, outs: List<Out>, near: String) {
            val one = (outs.singleOrNull() as? Out.Commit)?.text?.singleOrNull()
            when {
                type == KeyType.CHAR && one != null && one.isLetter() -> trail.add(one, near)
                type == KeyType.BACKSPACE && outs == listOf(Out.Backspace) -> trail.pop()
                type == KeyType.SHIFT || type == KeyType.LAYER || type == KeyType.SPACER -> Unit
                else -> trail.reset()
            }
        }
    }

    const val WINDOW = Tokens.READ_WINDOW

    /** The whole session through one [Composer]. */
    fun session(messages: List<Message>): Composer = Composer().also { c -> for (m in messages) c.run(m) }

    /**
     * r13d's per-word convention: a fresh field holding [left], [typed] key by key (a ' ' inside it is a space key),
     * then [end]: " " , . ? or "SEND" (VK's Enter in this single-line field). [lang] = the keyboard language at that
     * word, [near] = the touch record per letter. [singleLine] false = a multi-line field (Enter is a new line).
     */
    fun replayWord(left: String, typed: String, end: String, lang: Lang, near: List<String>?, singleLine: Boolean = true): String {
        val c = Composer(singleLine, model = null, store = null)
        if (lang != c.w.settings.lang) { c.w.settings = c.w.settings.copy(lang = lang); c.engine.corrector = c.w.corrector() }
        c.load(left)
        val keys = ArrayList<KeyEv>()
        var li = 0
        for (ch in typed) {
            if (ch == ' ') { keys += KeyEv(" ", 0, null, 0); continue }
            keys += KeyEv(ch.toString(), 0, near?.getOrNull(li) ?: "", 0)
            li++
        }
        keys += KeyEv(end, 10_000L, null, 0)
        c.run(Message("w", keys))
        val out = if (end == "SEND") c.sent.last() else c.text
        check(out.startsWith(left)) { "the field keeps its left context: «$left» → «$out»" }
        return out.substring(left.length)
    }
}
