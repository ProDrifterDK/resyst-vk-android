package com.resyst.vk.core

import com.resyst.vk.R
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
enum class QuickAction {
    DAY_NIGHT, TEMA, MODE, CLIPBOARD, ONE_HAND, HEIGHT, EDIT, SETTINGS;

    /** The tile's name (`qa_<name>`). */
    fun label(t: Txt): String = t.need("qa_" + name.lowercase())
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

    /** [Tile.mark]: what a tile's look depends on, whatever language its state is written in. */
    const val MARK_DARK = "dark"
    const val MARK_OFF = "off"

    /** A tile must stay a 48 dp touch target on the smallest keyboard (QP4). */
    const val MIN_TILE_DP = 48f

    data class Tile(val action: QuickAction, val label: String, val state: String, val enabled: Boolean, val mark: String = "") {
        /** TalkBack: label, state, and why it is off. */
        fun desc(t: Txt): String = t.get(R.string.quick_tile_desc, label, state) + if (enabled) "" else t.get(R.string.quick_tile_off)
    }

    /** The next Altura step after [h]; an off-step value snaps up, the top wraps to the bottom. */
    fun nextHeight(h: Float): Float = HEIGHTS.firstOrNull { it > h + 0.001f } ?: HEIGHTS.first()

    fun nextMode(m: Mode): Mode = Mode.values()[(m.ordinal + 1) % Mode.values().size]

    /**
     * r14 (FS2): [systemDark] = the phone's dark mode when known. While the tema follows the system, Día / noche
     * stops following and shows the opposite of what is on screen (the explicit choice wins); with no system answer
     * (tests, previews) it flips the stored look as in 0.13.0.
     */
    fun apply(st: ProfileStore, a: QuickAction, systemDark: Boolean? = null): ProfileStore = when (a) {
        QuickAction.DAY_NIGHT -> if (st.followSystem && systemDark != null) {
            val shown = st.lookFor(systemDark)
            st.copy(followSystem = false).updateLook(st.activeTema.id) { DayNight.toggle(shown) }
        } else st.updateLook(st.activeTema.id) { DayNight.toggle(it) }
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
    fun tiles(t: Txt, st: ProfileStore, policy: FieldPolicy, editPanel: Boolean = true, systemDark: Boolean? = null): List<Tile> {
        val s = st.settingsFor(systemDark)
        val auto = st.followSystem && systemDark != null
        val clipOk = ClipRules.mayShowHistory(policy, st.clip)
        return QuickAction.values().map { a ->
            val label = a.label(t)
            when (a) {
                QuickAction.DAY_NIGHT -> {
                    val side = t.get(if (Themes.byId(s.theme).dark) R.string.qs_dark else R.string.qs_light)
                    Tile(a, label, if (auto) t.get(R.string.qs_auto, side) else side, true, if (Themes.byId(s.theme).dark) MARK_DARK else "light")
                }
                QuickAction.TEMA -> Tile(a, label, SeedNames.display(t, st.activeTema), st.temas.size > 1)
                QuickAction.MODE -> Tile(a, label, st.mode.label(t), true)
                QuickAction.CLIPBOARD -> Tile(a, label, t.get(when {
                    clipOk -> R.string.qs_clip_on
                    policy.secret -> R.string.qs_clip_secret
                    else -> R.string.qs_clip_off
                }), clipOk)
                QuickAction.ONE_HAND -> Tile(a, label, t.get(when (s.oneHanded) {
                    OneHand.OFF -> R.string.qs_off
                    OneHand.RIGHT -> R.string.qs_right
                    OneHand.LEFT -> R.string.qs_left
                }), true, if (s.oneHanded == OneHand.OFF) MARK_OFF else "on")
                QuickAction.HEIGHT -> Tile(a, label, t.get(R.string.qs_height, (st.activeTema.look.heightScale * 100).roundToInt()), true)
                QuickAction.EDIT -> Tile(a, label, t.get(if (editPanel) R.string.qs_edit else R.string.qs_soon), editPanel)
                QuickAction.SETTINGS -> Tile(a, label, t.get(R.string.qs_all_settings), true)
            }
        }
    }

    /** The panel's update line when the strip chip stepped down (QP5); null = nothing announced. */
    fun updateLine(t: Txt, version: String?): String? = version?.let { t.get(R.string.quick_update_line, it) }
}
