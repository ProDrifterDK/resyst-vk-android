package com.resyst.vk.core

import kotlin.math.roundToInt

/**
 * r10 (UX-3): the strip is for typing. It keeps the suggestions, the contextual «Pegar» chip, the
 * r9 update chip, the «sin memoria» mark and ⚙; everything else lives in the quick panel ⚙ opens.
 */
object StripPlan {
    /** «Pegar» takes the third suggestion slot, never more (QS2). */
    fun suggestions(all: List<String>, paste: Boolean): List<String> =
        all.take(if (paste) Bar.LIMIT - 1 else Bar.LIMIT)
}

/** r10 (UX-13): the keys narrowed to one side, a rail on the other to move them back. */
enum class OneHand {
    OFF, RIGHT, LEFT;

    /** Quick-panel cycle: off → right (most thumbs) → left → off. */
    fun next(): OneHand = values()[(ordinal + 1) % values().size]

    /** Horizontal split of a [width] px keyboard: keys in [keysLeft, keysRight), rail beside them. */
    data class Split(val keysLeft: Float, val keysRight: Float, val railLeft: Float, val railWidth: Float)

    companion object {
        /** Share of the width the keys keep. */
        const val FRACTION = 0.85f
        /** Narrower than this the keys get too small to hit: stay full width instead (QO1). */
        const val MIN_FRACTION = 0.8f
        /** The rail holds two 48 dp buttons stacked: it is never narrower than a touch target. */
        const val RAIL_MIN_DP = 48f

        fun split(width: Float, side: OneHand, dp: Float): Split {
            val full = Split(0f, width, width, 0f)
            if (side == OFF || width <= 0f) return full
            val rail = maxOf(width * (1f - FRACTION), RAIL_MIN_DP * dp)
            val keys = width - rail
            if (keys < width * MIN_FRACTION) return full
            return if (side == LEFT) Split(0f, keys, keys, rail) else Split(rail, width, 0f, rail)
        }
    }
}

/** The eight quick-panel tiles, in the order they are drawn (2 × 4). */
enum class QuickAction(val label: String) {
    DAY_NIGHT("Día / noche"),
    TEMA("Tema"),
    MODE("Modo"),
    CLIPBOARD("Portapapeles"),
    ONE_HAND("Una mano"),
    HEIGHT("Altura"),
    EDIT("Edición"),
    SETTINGS("Ajustes"),
}

/**
 * The quick panel's model (r10, UX-3): what each tile shows and what a tap changes. A tile
 * changes exactly what its label says (QP1): Día/noche and Altura edit the active tema's look,
 * Tema switches tema, Modo only flips the named override, Una mano is phone-wide. Portapapeles,
 * Edición and Ajustes open something; they never change the store.
 */
object Quick {
    /** Altura steps (heightScale). A full turn returns to the start (QP2). */
    val HEIGHTS = listOf(0.9f, 1.0f, 1.1f, 1.2f)

    /** A tile must stay a 48 dp touch target on the smallest keyboard (QP4). */
    const val MIN_TILE_DP = 48f

    data class Tile(val action: QuickAction, val label: String, val state: String, val enabled: Boolean) {
        /** TalkBack: label, state, and why it is off. */
        val desc: String get() = "$label, $state" + if (enabled) "" else ", no disponible"
    }

    /** The next Altura step after [h]; an off-step value snaps up, the top wraps to the bottom. */
    fun nextHeight(h: Float): Float = HEIGHTS.firstOrNull { it > h + 0.001f } ?: HEIGHTS.first()

    fun nextMode(m: Mode): Mode = Mode.values()[(m.ordinal + 1) % Mode.values().size]

    fun apply(st: ProfileStore, a: QuickAction): ProfileStore = when (a) {
        QuickAction.DAY_NIGHT -> st.updateLook(st.activeTema.id) { DayNight.toggle(it) }
        QuickAction.TEMA -> st.withTema(st.nextTemaId())
        QuickAction.MODE -> st.withMode(nextMode(st.mode))
        QuickAction.HEIGHT -> st.updateLook(st.activeTema.id) { it.copy(heightScale = nextHeight(it.heightScale)) }
        QuickAction.ONE_HAND -> st.updatePhone { it.copy(oneHanded = it.oneHanded.next()) }
        QuickAction.CLIPBOARD, QuickAction.EDIT, QuickAction.SETTINGS -> st
    }

    /**
     * The tiles for [st] in a field with [policy]. [editPanel] = the editing panel exists in
     * this build (bet 5); until then the tile says so instead of doing nothing.
     */
    fun tiles(st: ProfileStore, policy: FieldPolicy, editPanel: Boolean = true): List<Tile> {
        val s = st.settings
        val clipOk = ClipRules.mayShowHistory(policy, st.clip)
        return QuickAction.values().map { a ->
            when (a) {
                QuickAction.DAY_NIGHT -> Tile(a, a.label, if (Themes.byId(s.theme).dark) "Oscuro" else "Claro", true)
                QuickAction.TEMA -> Tile(a, a.label, st.activeTema.name, st.temas.size > 1)
                QuickAction.MODE -> Tile(a, a.label, st.mode.label, true)
                QuickAction.CLIPBOARD -> Tile(a, a.label, when {
                    clipOk -> "Historial"
                    policy.secret -> "No en contraseñas"
                    else -> "Historial apagado"
                }, clipOk)
                QuickAction.ONE_HAND -> Tile(a, a.label, when (s.oneHanded) {
                    OneHand.OFF -> "Apagado"
                    OneHand.RIGHT -> "Derecha"
                    OneHand.LEFT -> "Izquierda"
                }, true)
                QuickAction.HEIGHT -> Tile(a, a.label, "${(st.activeTema.look.heightScale * 100).roundToInt()} %", true)
                QuickAction.EDIT -> Tile(a, a.label, if (editPanel) "Cursor y selección" else "Próximamente", editPanel)
                QuickAction.SETTINGS -> Tile(a, a.label, "Todos los ajustes", true)
            }
        }
    }

    /** The panel's update line when the strip chip stepped down (QP5); null = nothing announced. */
    fun updateLine(version: String?): String? = version?.let { "Resyst VK $it disponible · ver" }
}
