package com.resyst.vk.core

/** Keyboard language: drives the letter layout (ñ) and the suggestion lexicon. */
enum class Lang(val code: String) { ES("es"), EN("en") }

/** Optional row above the letters (desktop "fila superior"). */
enum class TopRow { ACCENTS, NUMBERS, NONE }

/** What kind of text the focused field expects (derived from EditorInfo). */
enum class FieldKind { TEXT, EMAIL, URL, NUMBER, PHONE, PASSWORD }

enum class Layer { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }

enum class KeyType { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, LAYER, SPACER, EMOJI }

enum class KeyStyle { NORMAL, MOD, ACCENT, ACTION, NUM }

/** Editor action, decoupled from android.view.inputmethod.EditorInfo so the core stays JVM-pure. */
enum class ImeAction { NONE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

/**
 * One key. [width] is in layout units (a row is [KeyboardLayouts.ROW_UNITS] wide).
 * [text] is what a CHAR key commits; [variants] are the long-press alternatives.
 */
data class Key(
    val type: KeyType,
    val label: String,
    val text: String = "",
    val width: Float = 1f,
    val target: Layer? = null,
    val hint: String? = null,
    val variants: List<String> = emptyList(),
    val style: KeyStyle = KeyStyle.NORMAL,
)

data class LayoutSpec(
    val lang: Lang = Lang.ES,
    val topRow: TopRow = TopRow.ACCENTS,
    val field: FieldKind = FieldKind.TEXT,
    /** r8: dedicated emoji key in the bottom row (setting, default on; never in email/URL fields). */
    val emojiKey: Boolean = false,
)

data class FieldInfo(
    val kind: FieldKind = FieldKind.TEXT,
    val multiLine: Boolean = false,
    val action: ImeAction = ImeAction.NONE,
    val autoCap: Boolean = true,
)

enum class ShiftState { OFF, ONCE, AUTO, LOCKED }

/** Side effects the engine asks the IME to perform on the InputConnection. */
sealed class Out {
    data class Commit(val text: String) : Out()
    data class DeleteBefore(val chars: Int) : Out()
    data class Action(val action: ImeAction) : Out()
    data object Backspace : Out()
    data object EnterKey : Out()
}
