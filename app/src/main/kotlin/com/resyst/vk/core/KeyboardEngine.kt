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

    /** The correction the last space applied (typed → committed), undoable by backspace. */
    private var undo: Pair<String, String>? = null
    /** A word the user reverted: the next space keeps it as typed. */
    private var rejected: String? = null
    /** r11: the typed word the last [press] / [undoCorrection] restored (read once by [takeReverted]). */
    private var reverted: String? = null
    /**
     * r12 (PS1–PS6): the text a suggestion pick (or a punctuation swap after it) left right before
     * the cursor, ending in its provisional space ("Hola "), or null. Only while the text before the
     * cursor still ends with exactly this can closing punctuation take that space's place, or a
     * space be absorbed.
     */
    private var provisional: String? = null

    val upper: Boolean get() = shift != ShiftState.OFF

    fun start(info: FieldInfo) {
        field = info
        layer = if (info.kind == FieldKind.NUMBER || info.kind == FieldKind.PHONE) Layer.NUMPAD else Layer.LETTERS
        shift = ShiftState.OFF
        shiftHeld = false
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

    /** Called after every cursor/text change with the editor's caps-mode hint. */
    fun updateAutoCap(capsHint: Boolean) {
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
            KeyType.CHAR -> typed(key.text).let { t -> if (prov != null && closes(t)) swap(prov, t) else listOf(Out.Commit(t)) }
            KeyType.SPACE -> space(before, now, after)
            KeyType.ENTER -> listOf(enter())
            KeyType.BACKSPACE -> revert(pendingUndo, before) ?: listOf(Out.Backspace)
            KeyType.LAYER -> { layer = key.target ?: Layer.LETTERS; emptyList() }
            KeyType.SHIFT, KeyType.SPACER, KeyType.EMOJI -> emptyList()
        }
        if (key.type != KeyType.SPACE) lastWasSpace = false
        return out
    }

    /**
     * A long-press variant. [before] = the text before the cursor, so a closing variant (`.` → `…`)
     * can take a pick's provisional space too (PS1); null (the GIF search box) never swaps.
     */
    fun variant(text: String, before: CharSequence? = null): List<Out> {
        val prov = before?.let(::pendingProvisional)
        provisional = null
        lastWasSpace = false
        undo = null
        val t = typed(text)
        return if (prov != null && closes(t)) swap(prov, t) else listOf(Out.Commit(t))
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

    private fun typed(text: String): String {
        val t = if (upper) Variants.shiftChar(text) else text
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
        val fix = correctionFor(before, after)
        rejected = null
        if (fix != null) {
            undo = fix
            return listOf(Out.DeleteBefore(fix.first.length), Out.Commit(fix.second + " "))
        }
        return listOf(Out.Commit(" "))
    }

    /** (typed, replacement) for the word ending at the cursor, if space should correct it. */
    private fun correctionFor(before: CharSequence, after: CharSequence): Pair<String, String>? {
        val c = corrector ?: return null
        if (after.isNotEmpty() && (after[0].isLetter() || after[0] == '\'')) return null // A11
        val word = Suggest.currentWord(before)
        if (word.isEmpty() || word == rejected || !Suggest.standalone(before, word)) return null
        val fix = c.fix(word, Suggest.sentenceStart(before, word)) ?: return null
        return if (fix != word) word to fix else null
    }

    /**
     * r10 (UX-5): the word the last space-correction replaced, while the text still ends with
     * that correction + space — the bar offers "↶ typed" for exactly that long (U1).
     */
    fun undoOffer(before: CharSequence): String? {
        val (typedWord, fixed) = undo ?: return null
        return if (before.endsWith("$fixed ")) typedWord else null
    }

    /** The "↶" chip: restore the typed word and its space; the next space keeps it (U2). */
    fun undoCorrection(before: CharSequence): List<Out>? {
        val u = undo ?: return null
        val outs = revert(u, before) ?: return null
        undo = null
        lastWasSpace = false
        return outs + Out.Commit(" ")
    }

    /** Backspace right after a space-correction restores the typed word (A10). */
    private fun revert(u: Pair<String, String>?, before: CharSequence): List<Out>? {
        val (typedWord, fixed) = u ?: return null
        val tail = "$fixed "
        if (!before.endsWith(tail)) return null
        rejected = typedWord
        reverted = typedWord
        return listOf(Out.DeleteBefore(tail.length), Out.Commit(typedWord))
    }

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
        /** r12 (PS1): marks that close what comes before them and take a pick's provisional space. */
        const val CLOSING = ",.;:!?)]}»”…"
    }
}
