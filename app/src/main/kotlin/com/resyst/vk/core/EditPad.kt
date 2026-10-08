package com.resyst.vk.core

import kotlin.math.abs

/**
 * r10 (bet 5, UX-7): the edit panel's layout and rules. The ops themselves ([EditOp]) and what
 * they do to the text live in [TextEdit] / the IME; this is only what the panel shows (QE1–QE5).
 */
object EditPad {
    /** The panel's header row (‹ Teclado · title), in dp. */
    const val HEADER_DP = 44f
    /** A button is never smaller than a touch target. */
    const val MIN_BUTTON_DP = 48f

    data class Button(val op: EditOp, val label: String, val col: Int, val row: Int)

    private fun label(op: EditOp): String = when (op) {
        EditOp.LEFT -> "←"
        EditOp.RIGHT -> "→"
        EditOp.UP -> "↑"
        EditOp.DOWN -> "↓"
        EditOp.HOME -> "Inicio"
        EditOp.END -> "Fin"
        EditOp.SELECT -> "Seleccionar"
        EditOp.ALL -> "Todo"
        EditOp.COPY -> "Copiar"
        EditOp.CUT -> "Cortar"
        EditOp.PASTE -> "Pegar"
        EditOp.DELETE_WORD -> "Borrar palabra"
    }

    /** Portrait: a d-pad around Seleccionar + a clipboard column. */
    private val TALL = listOf(
        listOf(EditOp.HOME, EditOp.UP, EditOp.END, EditOp.COPY),
        listOf(EditOp.LEFT, EditOp.SELECT, EditOp.RIGHT, EditOp.CUT),
        listOf(EditOp.ALL, EditOp.DOWN, EditOp.DELETE_WORD, EditOp.PASTE),
    )

    /** Short keyboards (landscape): two rows of six. */
    private val WIDE = listOf(
        listOf(EditOp.HOME, EditOp.UP, EditOp.END, EditOp.SELECT, EditOp.COPY, EditOp.CUT),
        listOf(EditOp.LEFT, EditOp.DOWN, EditOp.RIGHT, EditOp.ALL, EditOp.DELETE_WORD, EditOp.PASTE),
    )

    /** Three rows while each keeps ≥ [MIN_BUTTON_DP], else two (QE5). */
    fun rowsFor(gridHeightDp: Float): Int = if (gridHeightDp / 3 >= MIN_BUTTON_DP) 3 else 2

    fun grid(rows: Int): List<Button> = (if (rows >= 3) TALL else WIDE).flatMapIndexed { r, row ->
        row.mapIndexed { c, op -> Button(op, label(op), c, r) }
    }

    /** Copiar / Cortar never run in a secret field (QE2). */
    fun enabled(op: EditOp, secret: Boolean): Boolean = !(secret && (op == EditOp.COPY || op == EditOp.CUT))

    /** Moves and word deletes repeat while held; one-shot ops never do (QE3). */
    fun repeats(op: EditOp): Boolean = op == EditOp.LEFT || op == EditOp.RIGHT || op == EditOp.UP || op == EditOp.DOWN || op == EditOp.DELETE_WORD

    /** TalkBack: name, selection state, why it is off (QE4, QE5). */
    fun desc(op: EditOp, selecting: Boolean, secret: Boolean): String {
        val name = when (op) {
            EditOp.LEFT -> "Izquierda"
            EditOp.RIGHT -> "Derecha"
            EditOp.UP -> "Arriba"
            EditOp.DOWN -> "Abajo"
            EditOp.HOME -> "Inicio de línea"
            EditOp.END -> "Fin de línea"
            EditOp.SELECT -> "Seleccionar"
            EditOp.ALL -> "Seleccionar todo"
            EditOp.COPY -> "Copiar"
            EditOp.CUT -> "Cortar"
            EditOp.PASTE -> "Pegar"
            EditOp.DELETE_WORD -> "Borrar palabra"
        }
        return when {
            !enabled(op, secret) -> "$name, no disponible en contraseñas"
            op == EditOp.SELECT -> "$name, " + if (selecting) "activado" else "desactivado"
            selecting && op in MOVES -> "$name, extiende la selección"
            else -> name
        }
    }

    private val MOVES = setOf(EditOp.LEFT, EditOp.RIGHT, EditOp.UP, EditOp.DOWN, EditOp.HOME, EditOp.END)
}

/** r10 (bet 5, UX-6): a leftward swipe on ⌫ deletes the word before the cursor (QE6). */
object DeleteSwipe {
    const val MIN_DP = 24f

    fun isSwipe(dx: Float, dy: Float, dp: Float): Boolean = dx <= -MIN_DP * dp && abs(dx) > 2 * abs(dy)
}
