package com.resyst.vk.core

/** Replacement for a just-finished word (already cased), or null to keep it. */
fun interface Corrector {
    fun fix(word: String, sentenceStart: Boolean): String?
}

/**
 * Pure keyboard state machine: shift / caps lock / layers / enter semantics / double-space /
 * space-applies-the-correction. Knows nothing about Android; the IME service turns [Out] into
 * InputConnection calls.
 *
 * r12: the space a suggestion pick adds is provisional (Gboard-style): closing punctuation next
 * takes its place ("Hola " + "," → "Hola, "), a space next is absorbed; anything else keeps it.
 *
 * r13 (LR1–LR7): the corrections space applied in this field are remembered (memory only); when
 * the user deletes one later and types the same word again, space keeps it ([takeReverted]) and
 * the rejected pair is handed out once ([takeRejection]).
 *
 * r13c (EP1–EP8): every word ending corrects like space — a closing mark (`, . ; : ! ? ) ] } » ” …`,
 * typed or long-pressed) or Enter where it inserts a new line — and the mark lands after the fix.
 * (TS1) a word whose correction the user rejected stays as typed for the rest of this field.
 *
 * r13f: Enter as the field's key (single-line: an editor action or KEYCODE_ENTER) corrects the word first (SE1), and
 * a layer key that brings the letters back re-applies the last caps hint (CL1).
 */
class KeyboardEngine {
    var layer: Layer = Layer.LETTERS
        private set
    var shift: ShiftState = ShiftState.OFF
        private set
    var field: FieldInfo = FieldInfo()
        private set

    var autoCapEnabled = true
    var doubleSpacePeriod = true
    /** Set ⇒ space replaces the word before the cursor with [Corrector.fix]'s answer. */
    var corrector: Corrector? = null

    private var shiftHeld = false
    private var chordUsed = false
    private var lastShiftTap = Long.MIN_VALUE / 2
    private var lastWasSpace = false
    private var lastSpaceAt = Long.MIN_VALUE / 2

    /**
     * The correction the last word ending applied, undoable by ⌫ / ↶ right after it: [typed] →
     * [fixed], ended by [mark] (" " for space, a closing mark, "\n").
     */
    private class Undo(val typed: String, val fixed: String, val mark: String) {
        /** What the text before the cursor ends with while the undo is still on offer. */
        val tail: String get() = fixed + mark
    }

    private var undo: Undo? = null
    /** A word the user reverted: the next space keeps it as typed. */
    private var rejected: String? = null
    /** r11: the typed word the last [press] / [undoCorrection] restored (read once by [takeReverted]). */
    private var reverted: String? = null
    /** r13 (LR5): the correction the user just rejected (typed → fix), read once by [takeRejection]. */
    private var rejection: Pair<String, String>? = null

    /** r13: one correction space applied in this field; [deleted] = the user deleted (part of) it. */
    private class Applied(val typed: String, val fixed: String) {
        var deleted = false
        /** Words finished since the deletion; the retype must come soon (LR3). */
        var since = 0
    }

    /** r13 (LR6): this field's corrections, oldest first, at most [FIELD_MEMORY]; never persisted. */
    private val applied = ArrayList<Applied>()
    /**
     * r13c (TS1): the typed words ([Tokens.key]) whose correction the user rejected in this field,
     * oldest first, at most [FIELD_MEMORY]: never corrected again here. Memory only, like [applied].
     */
    private val protectedHere = LinkedHashSet<String>()
    /**
     * r12 (PS1–PS6): the text a suggestion pick (or a punctuation swap after it) left right before
     * the cursor, ending in its provisional space ("Hola "), or null. Only while the text before the
     * cursor still ends with exactly this can closing punctuation take that space's place, or a
     * space be absorbed.
     */
    private var provisional: String? = null

    val upper: Boolean get() = shift != ShiftState.OFF

    /**
     * [sameField] (r13, LR1): the editor is the one the keyboard was just in (a restart, or the user
     * came back to it): its correction memory is kept. Any other field starts with none (LR6).
     */
    fun start(info: FieldInfo, sameField: Boolean = false) {
        if (!sameField) { applied.clear(); protectedHere.clear() }
        rejection = null
        field = info
        layer = if (info.kind == FieldKind.NUMBER || info.kind == FieldKind.PHONE) Layer.NUMPAD else Layer.LETTERS
        shift = ShiftState.OFF
        shiftHeld = false
        capsHint = false
        lastWasSpace = false
        undo = null
        rejected = null
        provisional = null
    }

    /** r12 (PS4): a provisional space is still pending (the IME watches the cursor while it is). */
    val hasProvisional: Boolean get() = provisional != null

    /** r12 (PS4): something outside the keys changed the text or moved the cursor. */
    fun endProvisional() { provisional = null }

    /**
     * r12 (PS4): the editor reported a selection change ([before] = the text before the new cursor,
     * read now). Our own pick / swap leaves a collapsed cursor right after the provisional tail; a
     * selection, or other text before the cursor, means the user moved it (or the app rewrote the
     * text): the provisional space is over, and coming back to the same place does not revive it.
     */
    fun selectionChanged(before: CharSequence, selStart: Int, selEnd: Int) {
        val p = provisional ?: return
        if (selStart != selEnd || !before.endsWith(p)) provisional = null
    }

    fun setLayer(l: Layer) { layer = l }

    fun shiftDown(now: Long) {
        shiftHeld = true
        chordUsed = false
        shift = when (shift) {
            ShiftState.LOCKED -> ShiftState.OFF
            ShiftState.ONCE -> if (now - lastShiftTap < DOUBLE_TAP_MS) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.AUTO -> ShiftState.OFF
            ShiftState.OFF -> ShiftState.ONCE
        }
        lastShiftTap = now
    }

    fun shiftUp() {
        shiftHeld = false
        if (chordUsed) shift = ShiftState.OFF
        chordUsed = false
    }

    /** r13f (CL1): the last caps hint given, re-applied when a layer key brings the letters back. */
    private var capsHint = false

    /** Called after every cursor/text change with the editor's caps-mode hint. */
    fun updateAutoCap(capsHint: Boolean) {
        this.capsHint = capsHint
        if (shiftHeld || shift == ShiftState.LOCKED || shift == ShiftState.ONCE) return
        shift = if (autoCapEnabled && field.autoCap && capsHint && layer == Layer.LETTERS) ShiftState.AUTO else ShiftState.OFF
    }

    /** [after] = text right after the cursor (only its first char matters: mid-word ⇒ no correction). */
    fun press(key: Key, before: CharSequence, now: Long, after: CharSequence = ""): List<Out> {
        reverted = null
        val pendingUndo = undo
        if (key.type != KeyType.SHIFT) undo = null
        // r12 (PS4): shift and the layer keys (?123 → "?") keep a provisional space; any other key ends it
        val prov = pendingProvisional(before)
        if (key.type != KeyType.SHIFT && key.type != KeyType.LAYER && key.type != KeyType.SPACER) provisional = null
        if (prov != null && key.type == KeyType.SPACE) return absorbSpace() // PS2
        val out = when (key.type) {
            KeyType.CHAR -> typed(key.text, before).let { t ->
                when {
                    prov != null && closes(t) -> swap(prov, t)
                    closes(t) -> ending(before, after, t) ?: listOf(Out.Commit(t)) // EP1
                    else -> withdraw(pendingUndo, before, t) ?: listOf(Out.Commit(t)) // EP5
                }
            }
            KeyType.SPACE -> space(before, now, after)
            // EP1: a new line ends (and corrects) the word; r13f (SE1): so does Enter as the field's key, the fix
            // first (one batch edit for the IME), then the action / key event, nothing added
            KeyType.ENTER -> if (field.multiLine) ending(before, after, "\n") ?: listOf(enter())
                else ending(before, after, "")?.plus(enter()) ?: listOf(enter())
            KeyType.BACKSPACE -> revert(pendingUndo, before) ?: run { deleting(before, after); listOf(Out.Backspace) }
            KeyType.LAYER -> {
                val from = layer
                layer = key.target ?: Layer.LETTERS
                if (layer == Layer.LETTERS && from != Layer.LETTERS) updateAutoCap(capsHint) // r13f (CL1)
                emptyList()
            }
            KeyType.SHIFT, KeyType.SPACER, KeyType.EMOJI -> emptyList()
        }
        if (key.type != KeyType.SPACE) lastWasSpace = false
        return out
    }

    /**
     * A long-press variant. [before] = the text before the cursor, so a closing variant (`.` → `…`)
     * can take a pick's provisional space too (PS1); null (the GIF search box) never swaps.
     */
    fun variant(text: String, before: CharSequence? = null, after: CharSequence = ""): List<Out> {
        reverted = null
        val prov = before?.let(::pendingProvisional)
        provisional = null
        lastWasSpace = false
        undo = null
        val t = typed(text, before)
        return when {
            prov != null && closes(t) -> swap(prov, t)
            before != null && closes(t) -> ending(before, after, t) ?: listOf(Out.Commit(t)) // EP1: … ! ? by long-press
            else -> listOf(Out.Commit(t))
        }
    }

    /**
     * A tapped suggestion replaces the word + adds a space; the user chose it, so it is final.
     * r12: that space is provisional (PS1–PS6) in plain text fields only.
     */
    fun pickSuggestion(word: String, typedWord: String): List<Out> {
        lastWasSpace = true
        lastSpaceAt = Long.MIN_VALUE / 2 // a picked word's space must not arm double-space
        undo = null
        rejected = null
        provisional = if (field.kind == FieldKind.TEXT) "$word " else null // PS6
        return if (typedWord.isEmpty()) listOf(Out.Commit("$word "))
        else listOf(Out.DeleteBefore(typedWord.length), Out.Commit("$word "))
    }

    /** The provisional tail if it is still exactly what precedes the cursor (PS4), else null. */
    private fun pendingProvisional(before: CharSequence): String? =
        provisional?.takeIf { field.kind == FieldKind.TEXT && before.endsWith(it) }

    /** PS1: the provisional space goes, [mark] takes its place, and a new provisional space follows it. */
    private fun swap(prov: String, mark: String): List<Out> {
        provisional = prov.dropLast(1) + mark + " "
        lastWasSpace = false
        lastSpaceAt = Long.MIN_VALUE / 2 // PS3: the swap's space never arms double-space
        return listOf(Out.DeleteBefore(1), Out.Commit("$mark "))
    }

    /** PS2/PS3: a space right after a pick / swap is already there: nothing is committed or armed. */
    private fun absorbSpace(): List<Out> {
        lastWasSpace = false
        lastSpaceAt = Long.MIN_VALUE / 2
        return emptyList()
    }

    private fun closes(t: String): Boolean = t.length == 1 && t[0] in CLOSING

    /**
     * r13c (MC1): AUTO shift (the editor's caps-mode answer, possibly stale after a ⌫ or a fast edit)
     * capitalizes only a letter that starts a word; right after a letter, digit or apostrophe it types
     * lowercase. A shift the user pressed (ONCE / LOCKED) is never overridden.
     */
    private fun typed(text: String, before: CharSequence? = null): String {
        val inWord = before != null && before.isNotEmpty() && before[before.length - 1].let { it.isLetterOrDigit() || it == '\'' }
        val up = upper && !(shift == ShiftState.AUTO && inWord)
        val t = if (up) Variants.shiftChar(text) else text
        if (shiftHeld) chordUsed = true
        else if (shift == ShiftState.ONCE || shift == ShiftState.AUTO) shift = ShiftState.OFF
        return t
    }

    private fun space(before: CharSequence, now: Long, after: CharSequence): List<Out> {
        val armed = doubleSpacePeriod && lastWasSpace && now - lastSpaceAt <= DOUBLE_SPACE_MS &&
            before.length >= 2 && before[before.length - 1] == ' ' && before[before.length - 2].isLetterOrDigit()
        lastWasSpace = !armed
        lastSpaceAt = now
        if (armed) return listOf(Out.DeleteBefore(1), Out.Commit(". "))
        return ending(before, after, " ") ?: listOf(Out.Commit(" "))
    }

    /**
     * r13c (EP1/EP2): [mark] ends the word before the cursor — space, a closing mark or a new line:
     * the same correction for all of them, the mark right after the fix, in one edit. null = nothing
     * to correct (the caller commits the mark as typed).
     */
    private fun ending(before: CharSequence, after: CharSequence, mark: String): List<Out>? {
        val fix = correctionFor(before, after)
        rejected = null
        if (mark == " " || Suggest.currentWord(before).isNotEmpty()) afterWord()
        if (fix == null) return null
        undo = Undo(fix.first, fix.second, mark)
        applied += Applied(fix.first, fix.second)
        while (applied.size > FIELD_MEMORY) applied.removeAt(0)
        return listOf(Out.DeleteBefore(fix.first.length), Out.Commit(fix.second + mark))
    }

    /**
     * r13c (EP5): a letter or digit typed right after the `.` that just corrected a word: the period
     * was inside a token (resyst.cl, informe.pdf), not a sentence end — the typed word comes back
     * with its period and the key, in one edit. Not a rejection, and nothing to unlearn: a word is
     * learned only once whitespace follows it (Tokens.finished).
     */
    private fun withdraw(u: Undo?, before: CharSequence, t: String): List<Out>? {
        if (u == null || u.mark != "." || t.isEmpty() || !t[0].isLetterOrDigit() || !before.endsWith(u.tail)) return null
        applied.indexOfLast { it.typed == u.typed && it.fixed == u.fixed }.takeIf { it >= 0 }?.let { applied.removeAt(it) }
        return listOf(Out.DeleteBefore(u.tail.length), Out.Commit(u.typed + u.mark + t))
    }

    /** (typed, replacement) for the word ending at the cursor, if space should correct it. */
    private fun correctionFor(before: CharSequence, after: CharSequence): Pair<String, String>? {
        val c = corrector ?: return null
        if (after.isNotEmpty() && (after[0].isLetter() || after[0] == '\'')) return null // A11
        val word = Suggest.currentWord(before)
        if (word.isEmpty() || word == rejected || !Suggest.standalone(before, word)) return null
        if (Tokens.key(word) in protectedHere) return null // r13c (TS1): rejected once in this field
        // r13 (LR1): the user deleted a correction of this very word and typed it again: keep it
        lateRejection(word)?.let { a ->
            applied.remove(a)
            rejection = a.typed to a.fixed
            reverted = word
            protect(word)
            return null
        }
        val fix = c.fix(word, Suggest.sentenceStart(before, word)) ?: return null
        return if (fix != word) word to fix else null
    }

    /**
     * r10 (UX-5): the word the last space-correction replaced, while the text still ends with
     * that correction + space — the bar offers "↶ typed" for exactly that long (U1).
     */
    fun undoOffer(before: CharSequence): String? {
        val u = undo ?: return null
        return if (before.endsWith(u.tail)) u.typed else null
    }

    /**
     * The "↶" chip: restore the typed word and what ended it (its space, or the mark: `lento,` →
     * `lentl,`, EP3); the next space keeps it (U2).
     */
    fun undoCorrection(before: CharSequence): List<Out>? {
        val u = undo ?: return null
        val outs = revert(u, before) ?: return null
        undo = null
        lastWasSpace = false
        return if (u.mark == " ") outs + Out.Commit(" ") else outs
    }

    /**
     * Backspace right after a correction restores the typed word (A10). After space the space goes
     * too (`lento ` → `lentl`, r10); after a mark or a new line the mark stays (`lento,` → `lentl,`,
     * r13c EP3): one ⌫ undoes the fix, never the key the user chose.
     */
    private fun revert(u: Undo?, before: CharSequence): List<Out>? {
        if (u == null || !before.endsWith(u.tail)) return null
        rejected = u.typed
        reverted = u.typed
        rejection = u.typed to u.fixed // r13 (LR5)
        protect(u.typed)
        applied.indexOfLast { it.typed == u.typed && it.fixed == u.fixed }.takeIf { it >= 0 }?.let { applied.removeAt(it) }
        return listOf(Out.DeleteBefore(u.tail.length), Out.Commit(if (u.mark == " ") u.typed else u.typed + u.mark))
    }

    /** r13c (TS1): [word] stays as typed for the rest of this field. */
    private fun protect(word: String) {
        val k = Tokens.key(word)
        protectedHere.remove(k)
        protectedHere += k
        while (protectedHere.size > FIELD_MEMORY) protectedHere.remove(protectedHere.first())
    }

    /**
     * r13 (LR7): text is about to be deleted at the cursor — [before] / [after] the cursor now — by
     * ⌫, the held-⌫ word mode or the ⌫ swipe: a correction of this field whose word is the one under
     * the cursor is marked deleted. [selected] = a selection being deleted (each of its words counts).
     */
    fun deleting(before: CharSequence, after: CharSequence = "", selected: CharSequence? = null) {
        if (applied.isEmpty()) return
        val words = ArrayList<String>(4)
        if (!selected.isNullOrEmpty()) words += selected.split(WORD_SPLIT).filter { it.isNotEmpty() }
        else {
            var i = before.length
            while (i > 0 && Tokens.isWordChar(before[i - 1])) i--
            var j = 0
            while (j < after.length && Tokens.isWordChar(after[j])) j++
            val w = before.subSequence(i, before.length).toString() + after.subSequence(0, j)
            if (w.isNotEmpty()) words += w
        }
        for (w in words) {
            val k = Tokens.key(w.trim('\''))
            for (a in applied) if (Tokens.key(a.fixed) == k) { a.deleted = true; a.since = 0 }
        }
    }

    /** The deleted correction of [word] still waiting for its retype (LR1/LR3), or null. */
    private fun lateRejection(word: String): Applied? {
        val k = Tokens.key(word)
        return applied.lastOrNull { it.deleted && Tokens.key(it.typed) == k }
    }

    /** A word was finished: a deletion whose retype did not come within [RETYPE_WORDS] words is over (LR3). */
    private fun afterWord() {
        for (a in applied) if (a.deleted && ++a.since > RETYPE_WORDS) a.deleted = false
    }

    /**
     * r13 (LR5): the correction the user just rejected — ⌫ right after it, the ↶ chip, or a late
     * retype — as (typed, fix), or null. Cleared by the read.
     */
    fun takeRejection(): Pair<String, String>? = rejection.also { rejection = null }

    /** r13: corrections remembered for this field (tests, diagnostics). */
    val fieldCorrections: Int get() = applied.size

    /**
     * r13i (PM1): the words space wrote in this field and the user never undid (the pair memory masks them: the
     * keyboard's own unconfirmed edits are never learned). Memory only, at most [FIELD_MEMORY].
     */
    val fieldFixes: List<String> get() = applied.map { it.fixed }

    /** r13c (TS1): words kept as typed in this field after a rejection (tests, diagnostics). */
    val fieldProtected: Int get() = protectedHere.size

    /**
     * r11 (K1/K2): the word the user just kept by reverting its space-correction (⌫ right after
     * it, or the ↶ chip), or null. Cleared by the read and by the next key press.
     */
    fun takeReverted(): String? = reverted.also { reverted = null }

    private fun enter(): Out = when {
        field.multiLine -> Out.Commit("\n")
        field.action != ImeAction.NONE -> Out.Action(field.action)
        else -> Out.EnterKey
    }

    companion object {
        const val DOUBLE_TAP_MS = 400L
        const val DOUBLE_SPACE_MS = 1500L
        /** r12 (PS1): marks that close what comes before them and take a pick's provisional space; r13c (EP1): they end a word. */
        const val CLOSING = ",.;:!?)]}»”…"

        /** r13c (EP1/EP8): [text] (a key or long-press variant) ends the word before it — the IME reads after the cursor for it. */
        fun endsWord(text: String): Boolean = text.length == 1 && text[0] in CLOSING
        /** r13 (LR6): corrections remembered per field. */
        const val FIELD_MEMORY = 16
        /** r13 (LR3): words the user may finish between deleting a correction and retyping its word. */
        const val RETYPE_WORDS = 3
        private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}']+")
    }
}
