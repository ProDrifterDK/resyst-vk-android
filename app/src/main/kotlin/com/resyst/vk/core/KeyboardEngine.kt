package com.resyst.vk.core

/**
 * Pure keyboard state machine: shift / caps lock / layers / enter semantics / double-space.
 * Knows nothing about Android; the IME service turns [Out] into InputConnection calls.
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

    private var shiftHeld = false
    private var chordUsed = false
    private var lastShiftTap = Long.MIN_VALUE / 2
    private var lastWasSpace = false
    private var lastSpaceAt = Long.MIN_VALUE / 2

    val upper: Boolean get() = shift != ShiftState.OFF

    fun start(info: FieldInfo) {
        field = info
        layer = if (info.kind == FieldKind.NUMBER || info.kind == FieldKind.PHONE) Layer.NUMPAD else Layer.LETTERS
        shift = ShiftState.OFF
        shiftHeld = false
        lastWasSpace = false
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

    fun press(key: Key, before: CharSequence, now: Long): List<Out> {
        val out = when (key.type) {
            KeyType.CHAR -> listOf(Out.Commit(typed(key.text)))
            KeyType.SPACE -> space(before, now)
            KeyType.ENTER -> listOf(enter())
            KeyType.BACKSPACE -> listOf(Out.Backspace)
            KeyType.LAYER -> { layer = key.target ?: Layer.LETTERS; emptyList() }
            KeyType.SWITCH_IME -> listOf(Out.SwitchIme)
            KeyType.SHIFT, KeyType.SPACER -> emptyList()
        }
        if (key.type != KeyType.SPACE) lastWasSpace = false
        return out
    }

    fun variant(text: String): List<Out> {
        lastWasSpace = false
        return listOf(Out.Commit(typed(text)))
    }

    fun pickSuggestion(word: String, typedWord: String): List<Out> {
        lastWasSpace = true
        lastSpaceAt = Long.MIN_VALUE / 2 // a picked word's space must not arm double-space
        return if (typedWord.isEmpty()) listOf(Out.Commit("$word "))
        else listOf(Out.DeleteBefore(typedWord.length), Out.Commit("$word "))
    }

    private fun typed(text: String): String {
        val t = if (upper) Variants.shiftChar(text) else text
        if (shiftHeld) chordUsed = true
        else if (shift == ShiftState.ONCE || shift == ShiftState.AUTO) shift = ShiftState.OFF
        return t
    }

    private fun space(before: CharSequence, now: Long): List<Out> {
        val armed = doubleSpacePeriod && lastWasSpace && now - lastSpaceAt <= DOUBLE_SPACE_MS &&
            before.length >= 2 && before[before.length - 1] == ' ' && before[before.length - 2].isLetterOrDigit()
        lastWasSpace = !armed
        lastSpaceAt = now
        return if (armed) listOf(Out.DeleteBefore(1), Out.Commit(". ")) else listOf(Out.Commit(" "))
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
