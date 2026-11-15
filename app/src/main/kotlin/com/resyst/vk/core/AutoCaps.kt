package com.resyst.vk.core

/**
 * r13f (docs/failure-modes.md CL2, OP1-OP4): the IME's own additions to the editor's caps answer
 * (InputConnection.getCursorCapsMode). The result only feeds [KeyboardEngine.updateAutoCap], which keeps its gates
 * (the field asks for capitals, the setting is on, the letters layer is showing).
 */
object AutoCaps {
    /** Marks that may stand between the opener run and the word (skipped like Android skips " ' ( [ {). */
    private const val AROUND = "\"'«“‘"

    /**
     * The caps hint for the cursor: the [editor]'s answer, or a cursor at offset 0 with nothing before it (CL2: a
     * field emptied from outside, the chat page clearing its composer after a send), or a sentence-initial opener
     * right before the cursor (OP1). [before] = the text before the cursor as read (a window), [cursor] = the
     * selection start the editor reported (-1 = unknown).
     */
    fun hint(editor: Boolean, before: CharSequence, cursor: Int): Boolean =
        editor || (cursor == 0 && before.isEmpty()) || afterOpener(before)

    /**
     * OP1/OP2: [before] ends with one or more ¿ / ¡ (with quotes or opening brackets around them) and the text before
     * them is a sentence start by Android's own rule (TextUtils.getCapsMode, CAP_MODE_SENTENCES): the start of the
     * text or a new line (after optional blanks), or `.` `?` `!` (closing quotes / brackets allowed) and at least
     * one blank — not the period of an abbreviation or an ellipsis (`a.m.`, `...`), Android's own check.
     */
    fun afterOpener(before: CharSequence): Boolean {
        var i = before.length
        var openers = 0
        while (i > 0) {
            val c = before[i - 1]
            if (c == '¿' || c == '¡') openers++
            else if (c !in AROUND && Character.getType(c) != Character.START_PUNCTUATION.toInt()) break
            i--
        }
        if (openers == 0) return false
        var j = i
        while (j > 0 && (before[j - 1] == ' ' || before[j - 1] == '\t')) j--
        if (j == 0 || before[j - 1] == '\n') return true
        if (i == j) return false // no blank between the previous text and the opener
        while (j > 0) {
            val c = before[j - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) break
            j--
        }
        if (j == 0) return false
        val c = before[j - 1]
        if (c == '?' || c == '!') return true
        if (c != '.') return false
        var k = j - 2
        while (k >= 0) {
            val d = before[k]
            if (d == '.') return false
            if (!Character.isLetter(d)) break
            k--
        }
        return true
    }
}
