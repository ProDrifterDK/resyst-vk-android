package com.resyst.vk.core

/** Replacement for a just-finished word (already cased), or null to keep it. */
fun interface Corrector {
    fun fix(word: String, sentenceStart: Boolean): String?
}

/**
 * Pure keyboard state machine: shift / caps lock / layers / enter semantics / double-space /
 * space-applies-the-correction. Knows nothing about Android; the IME service turns [Out] into
 * InputConnection calls.
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

    val upper: Boolean get() = shift != ShiftState.OFF

    fun start(info: FieldInfo) {
        field = info
        layer = if (info.kind == FieldKind.NUMBER || info.kind == FieldKind.PHONE) Layer.NUMPAD else Layer.LETTERS
        shift = ShiftState.OFF
        shiftHeld = false
        lastWasSpace = false
        undo = null
        rejected = null
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
        val pendingUndo = undo
        if (key.type != KeyType.SHIFT) undo = null
        val out = when (key.type) {
            KeyType.CHAR -> listOf(Out.Commit(typed(key.text)))
            KeyType.SPACE -> space(before, now, after)
            KeyType.ENTER -> listOf(enter())
            KeyType.BACKSPACE -> revert(pendingUndo, before) ?: listOf(Out.Backspace)
            KeyType.LAYER -> { layer = key.target ?: Layer.LETTERS; emptyList() }
            KeyType.SWITCH_IME -> listOf(Out.SwitchIme)
            KeyType.SHIFT, KeyType.SPACER -> emptyList()
        }
        if (key.type != KeyType.SPACE) lastWasSpace = false
        return out
    }

    fun variant(text: String): List<Out> {
        lastWasSpace = false
        undo = null
        return listOf(Out.Commit(typed(text)))
    }

    /** A tapped suggestion replaces the word + adds a space; the user chose it, so it is final. */
    fun pickSuggestion(word: String, typedWord: String): List<Out> {
        lastWasSpace = true
        lastSpaceAt = Long.MIN_VALUE / 2 // a picked word's space must not arm double-space
        undo = null
        rejected = null
        return if (typedWord.isEmpty()) listOf(Out.Commit("$word "))
        else listOf(Out.DeleteBefore(typedWord.length), Out.Commit("$word "))
    }

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

    /** Backspace right after a space-correction restores the typed word (A10). */
    private fun revert(u: Pair<String, String>?, before: CharSequence): List<Out>? {
        val (typedWord, fixed) = u ?: return null
        val tail = "$fixed "
        if (!before.endsWith(tail)) return null
        rejected = typedWord
        return listOf(Out.DeleteBefore(tail.length), Out.Commit(typedWord))
    }

    private fun enter(): Out = when {
        field.multiLine -> Out.Commit("\n")
        field.action != ImeAction.NONE -> Out.Action(field.action)
        else -> Out.EnterKey
    }

    companion object {
        const val DOUBLE_TAP_MS = 400L
        const val DOUBLE_SPACE_MS = 1500L
    }
}
