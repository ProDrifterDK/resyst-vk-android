package com.resyst.vk.core

/**
 * r14 fix (docs/failure-modes.md LG6-LG10): the one table of what is drawn.
 *
 * Every surface the keyboard, its panels and Settings put text or an icon on is a [Surface], and its colour is computed
 * here ([surface]): the key faces per cap × kind (both ends of a gradient and the press), chips at rest and pressed,
 * pressed panel fills, cards, pills. [PAIRS] declares, per surface, the inks drawn on it and their minimum contrast.
 * The painters and Settings take their surface colours from [surface] and their inks from [ink] (which names the
 * surface it is drawn on); [Legibility.gate] corrects against exactly [PAIRS] and [Legibility.checks] checks exactly
 * [PAIRS]. Round14FixTest's source guard keeps colour arithmetic and raw theme colours out of the UI files and fails on
 * any (surface, ink) a painter names that [PAIRS] does not declare: a surface the painters can draw is one the gate sees.
 */

/** What a key's body is filled with (KeyPainter). */
enum class KeyKind { NORMAL, MOD, NUM, ACCENT_SOLID, SPACE }

/** A text or icon colour, by role. */
enum class Ink {
    TEXT, TEXT_MOD, MUTED, ACCENT, ACCENT_INK, OK, BAD, BAD_INK;

    fun of(p: Palette): Int = when (this) {
        TEXT -> p.theme.text
        TEXT_MOD -> p.theme.textMod
        MUTED -> p.theme.muted
        ACCENT -> p.accent
        ACCENT_INK -> p.accentInk
        OK -> p.theme.ok
        BAD -> p.theme.bad
        BAD_INK -> p.badInk
    }
}

/** Where text or an icon is drawn. The KEY_ surfaces depend on the cap and the key's kind; the others on the palette. */
enum class Surface(val key: Boolean = false) {
    /** A key's face at rest: the top of its gradient (the whole face for flat caps; the background for outline/borderless). */
    KEY_TOP(true),
    /** A key's face at rest: the bottom of its gradient. */
    KEY_BOTTOM(true),
    /** A key held down: what the press overlay reaches (keyHi; an accent key's pressed fill). */
    KEY_PRESSED(true),
    /** The keyboard, the bar, every panel and the Settings page. */
    BACKGROUND,
    /** Popups (variants, the floating preview), the one-hand dock, the clipboard preview, Settings cards, tema chips. */
    CARD,
    /** A key-coloured control: quick tiles, panel rows and buttons, «Pastillas», the GIF field, Settings pills and choices. */
    PLAIN,
    /** A modifier-coloured control: ABC / ⌫ in panels, edit buttons, the dock's buttons, GIF pills, a switch that is off. */
    MOD_PLAIN,
    /** Any pressed control that is not a key or an accent fill: a suggestion, a bar icon, a tile, a row, a button. */
    PRESSED,
    /** The connected key preview (bubble and neck): the pressed colour on dark palettes, the lifted face on light ones. */
    PREVIEW,
    /** An accent chip at rest: Pegar, offers, update, the first suggestion in «Pastillas», tabs, Settings banners. */
    CHIP,
    /** An accent fill: Enter, a pressed chip, the variant selection, «Ir», solid buttons, Settings pills, a switch on. */
    SOLID,
    /** An accent fill pressed: moved away from its ink (LG7). */
    SOLID_PRESSED,
    /** The quick panel tile's icon disc. */
    ICON_DISC,
    /** A destructive button («Sí, borrar»). */
    BAD_FILL,
}

object Drawn {
    const val LEGEND = 7.0
    const val TEXT = 4.5
    /** Non-text: icons, the knob of a switch (WCAG 1.4.11). */
    const val ICON = 3.0

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** One declared pair: [ink] drawn on [surface] (on keys of [kinds]) at ≥ [min]. */
    class Decl(val surface: Surface, val ink: Ink, val min: Double, val kinds: Set<KeyKind>, val where: String) {
        override fun toString() = "$ink on $surface ≥ $min (${if (surface.key) kinds.joinToString("/") + ": " else ""}$where)"
    }

    private val TEXT_KINDS = setOf(KeyKind.NORMAL, KeyKind.NUM)
    private val QUIET_KINDS = setOf(KeyKind.MOD, KeyKind.SPACE)
    private val SOLID_KIND = setOf(KeyKind.ACCENT_SOLID)
    private val KEY_SURFACES = listOf(Surface.KEY_TOP, Surface.KEY_BOTTOM, Surface.KEY_PRESSED)

    private fun keys(ink: Ink, min: Double, kinds: Set<KeyKind>, where: String) = KEY_SURFACES.map { Decl(it, ink, min, kinds, where) }
    private fun d(surface: Surface, ink: Ink, min: Double, where: String) = Decl(surface, ink, min, emptySet(), where)

    /** Every (surface, ink) the UI draws. Adding a surface or an ink to a painter means adding it here (the guard says so). */
    val PAIRS: List<Decl> = keys(Ink.TEXT, LEGEND, TEXT_KINDS, "letter and number legends") +
        keys(Ink.MUTED, TEXT, TEXT_KINDS, "sub-legends (top-row hints)") +
        keys(Ink.ACCENT, TEXT, setOf(KeyKind.NUM), "accent-row legends") +
        keys(Ink.TEXT_MOD, TEXT, QUIET_KINDS, "modifier legends and icons, the space bar's language") +
        keys(Ink.ACCENT, ICON, QUIET_KINDS, "Shift on (icon), the space bar's ✦") +
        keys(Ink.MUTED, ICON, setOf(KeyKind.SPACE), "the space bar's globe") +
        keys(Ink.ACCENT_INK, TEXT, SOLID_KIND, "Enter, Shift locked") +
        listOf(
            d(Surface.BACKGROUND, Ink.TEXT, LEGEND, "suggestions, panel and Settings text"),
            d(Surface.BACKGROUND, Ink.ACCENT, TEXT, "first suggestion, titles, links, bar icons"),
            d(Surface.BACKGROUND, Ink.MUTED, TEXT, "captions, status, bar icons, the emoji search band's line"),
            d(Surface.BACKGROUND, Ink.TEXT_MOD, TEXT, "headers, Settings titles, the GIF and emoji search ← and ✕"),
            d(Surface.BACKGROUND, Ink.OK, TEXT, "Settings status"),
            d(Surface.BACKGROUND, Ink.BAD, TEXT, "Settings warnings, the clipboard's «¿Borrar todo?»"),
            d(Surface.CARD, Ink.TEXT, LEGEND, "variants, the floating preview, Settings cards, tema chips"),
            d(Surface.CARD, Ink.TEXT_MOD, TEXT, "Settings cards, tema card sublabels"),
            d(Surface.CARD, Ink.MUTED, TEXT, "Settings captions (the hero's «Vista previa»)"),
            d(Surface.CARD, Ink.ACCENT, TEXT, "Settings card labels and links"),
            d(Surface.CARD, Ink.OK, TEXT, "a done setup step"),
            d(Surface.CARD, Ink.BAD, TEXT, "Settings warnings on cards"),
            d(Surface.PLAIN, Ink.TEXT, LEGEND, "«Pastillas», tiles, rows, buttons, Settings pills and choices, the emoji search query"),
            d(Surface.PLAIN, Ink.ACCENT, TEXT, "an active tile, a pinned clip, selecting arrows, setup steps, the search boxes' caret"),
            d(Surface.PLAIN, Ink.MUTED, TEXT, "tile states, clip times, the GIF and emoji search placeholders, «Buscar emoji»"),
            d(Surface.PLAIN, Ink.BAD, TEXT, "«Borrar»"),
            d(Surface.MOD_PLAIN, Ink.TEXT_MOD, TEXT, "ABC, ⌫, edit buttons, the dock's arrows"),
            d(Surface.MOD_PLAIN, Ink.TEXT, TEXT, "GIF pills"),
            d(Surface.MOD_PLAIN, Ink.MUTED, ICON, "a switch's knob when off"),
            d(Surface.PRESSED, Ink.TEXT, LEGEND, "a pressed suggestion, tile, row or button"),
            d(Surface.PRESSED, Ink.ACCENT, TEXT, "the pressed first suggestion, pressed bar icons, an active tile pressed"),
            d(Surface.PRESSED, Ink.MUTED, TEXT, "a pressed tile's state, a pressed field's placeholder, a pressed row's time"),
            d(Surface.PRESSED, Ink.TEXT_MOD, TEXT, "pressed ABC / ⌫ / headers, the update chip pressed"),
            d(Surface.PREVIEW, Ink.TEXT, LEGEND, "the connected preview's legend"),
            d(Surface.CHIP, Ink.ACCENT, TEXT, "chip text and icons"),
            d(Surface.CHIP, Ink.TEXT_MOD, TEXT, "the update chip's action, its ✕"),
            d(Surface.CHIP, Ink.TEXT, TEXT, "Settings' mode banner"),
            d(Surface.SOLID, Ink.ACCENT_INK, TEXT, "Enter (flat), a pressed chip, the selected variant, «Ir», buttons, pills, switches"),
            d(Surface.SOLID_PRESSED, Ink.ACCENT_INK, TEXT, "an accent key or «Ir» pressed"),
            d(Surface.ICON_DISC, Ink.TEXT_MOD, ICON, "a quick tile's icon"),
            d(Surface.BAD_FILL, Ink.BAD_INK, TEXT, "«Sí, borrar»"),
        )

    // ── surfaces ─────────────────────────────────────────────────────────
    /** The colour of [s] for palette [p] (key surfaces: under [cap], for a key of [kind]). */
    fun surface(p: Palette, s: Surface, cap: KeyCap = KeyCap.FLAT, kind: KeyKind = KeyKind.NORMAL): Int {
        val t = p.theme
        return when (s) {
            Surface.KEY_TOP -> keyTop(p, cap, kind)
            Surface.KEY_BOTTOM -> keyBottom(p, cap, kind)
            Surface.KEY_PRESSED -> keyPressed(p, kind)
            Surface.BACKGROUND -> t.bg
            Surface.CARD -> t.bg2
            Surface.PLAIN -> t.key
            Surface.MOD_PLAIN -> t.keyMod
            Surface.PRESSED -> t.keyHi
            Surface.PREVIEW -> if (t.dark) t.keyHi else t.keyTop
            Surface.CHIP -> p.chipFill
            Surface.SOLID -> p.accent
            Surface.SOLID_PRESSED -> p.solidPressed
            Surface.ICON_DISC -> ColorMath.mix(t.key, t.text, 0.09f)
            Surface.BAD_FILL -> t.bad
        }
    }

    /** The colour of [ink] where it is drawn on [surface]: the surface is named so the pair is declared, checked and gated. */
    @Suppress("UNUSED_PARAMETER")
    fun ink(p: Palette, surface: Surface, ink: Ink): Int = ink.of(p)

    /** A key's base fill (the bottom of RAISED's gradient). */
    fun keyBase(p: Palette, kind: KeyKind): Int = when (kind) {
        KeyKind.MOD -> p.theme.keyMod
        KeyKind.NUM -> p.theme.keyNum
        KeyKind.ACCENT_SOLID -> p.accent
        KeyKind.NORMAL, KeyKind.SPACE -> p.theme.key
    }

    fun keyTop(p: Palette, cap: KeyCap, kind: KeyKind): Int {
        val t = p.theme
        if (kind == KeyKind.ACCENT_SOLID) return if (cap == KeyCap.KEYCAP) p.solidTop else p.accent
        val base = keyBase(p, kind)
        return when (cap) {
            KeyCap.RAISED -> if (kind == KeyKind.MOD) ColorMath.mix(base, t.keyTop, 0.5f) else t.keyTop
            // dark palettes: the keycap's face lifts toward the pressed colour so it reads on near-black keys
            KeyCap.KEYCAP -> if (t.dark) ColorMath.mix(base, t.keyHi, 0.95f) else t.keyTop
            KeyCap.FLAT -> base
            KeyCap.OUTLINE, KeyCap.BORDERLESS -> t.bg
        }
    }

    fun keyBottom(p: Palette, cap: KeyCap, kind: KeyKind): Int {
        val t = p.theme
        if (kind == KeyKind.ACCENT_SOLID) return p.accent
        val base = keyBase(p, kind)
        return when (cap) {
            KeyCap.RAISED, KeyCap.FLAT -> base
            KeyCap.KEYCAP -> if (t.dark) ColorMath.mix(base, t.keyHi, 0.45f) else base
            KeyCap.OUTLINE, KeyCap.BORDERLESS -> t.bg
        }
    }

    fun keyPressed(p: Palette, kind: KeyKind): Int = if (kind == KeyKind.ACCENT_SOLID) p.solidPressed else p.theme.keyHi

    // ── decoration: colours no ink is drawn on ──────────────────────────
    /** The skirt under a RAISED or KEYCAP face (no legend sits on it). */
    fun skirt(p: Palette, cap: KeyCap, kind: KeyKind): Int {
        val t = p.theme
        val solid = kind == KeyKind.ACCENT_SOLID
        return when (cap) {
            KeyCap.KEYCAP -> when {
                solid -> ColorMath.mix(p.accent, BLACK, 0.3f)
                t.dark -> ColorMath.mix(keyBase(p, kind), BLACK, 0.55f)
                else -> ColorMath.mix(keyBase(p, kind), t.edge, 0.85f)
            }
            else -> if (solid) ColorMath.mix(p.accent, t.bg, 0.35f) else t.edge
        }
    }

    /** The KEYCAP face's hairline of light along its top edge (a 1 dp line, no ink). */
    fun keycapHairline(p: Palette, cap: KeyCap, kind: KeyKind): Int =
        ColorMath.withAlpha(ColorMath.mix(keyTop(p, cap, kind), WHITE, 0.5f), if (p.theme.dark) 0.22f else 0.5f)

    /** The accent ring around a pressed key, [press] 0…1 (a 1.2 dp stroke, no ink). */
    fun pressRing(p: Palette, press: Float): Int = ColorMath.withAlpha(p.accent, 0.45f * press)

    /** An outline or border that turns toward the accent while pressed (OUTLINE caps, the borderless space bar). */
    fun pressEdge(p: Palette, press: Float): Int = ColorMath.mix(p.theme.edgeHi, p.accent, press)

    /** A translucent wash over a GIF thumbnail while it is pressed (no ink). */
    fun thumbPressed(p: Palette): Int = ColorMath.withAlpha(p.accent, 0.28f)

    /** The chosen skin tone's wash: translucent accent over the key-coloured tone box, under a colour emoji (no ink). */
    fun chosenWash(p: Palette): Int = p.accentSoft

    /** The update chip's divider before its ✕ (no ink). */
    fun chipDivider(p: Palette): Int = ColorMath.withAlpha(p.accent, 0.3f)

    /** r15 (QP6): the transparent end of the tema row's edge fade — the background at alpha 0 (no ink on it). */
    fun clearOf(p: Palette): Int = ColorMath.withAlpha(p.theme.bg, 0f)

    /** The hairline around a quick-panel tema miniature's keys (no ink). */
    fun miniKeyEdge(p: Palette): Int = ColorMath.withAlpha(p.theme.text, 0.45f)

    /** A disabled control's ink (WCAG 1.4.3 exempts inactive components). */
    fun disabled(p: Palette, alpha: Float = 0.55f): Int = ColorMath.withAlpha(p.theme.muted, alpha)

    // ── the keys: which kind and which ink (KeyboardView draws exactly these; LG1 enumerates them) ──
    fun kindOf(type: KeyType, style: KeyStyle, shift: ShiftState): KeyKind = when {
        type == KeyType.ENTER || (type == KeyType.SHIFT && shift == ShiftState.LOCKED) -> KeyKind.ACCENT_SOLID
        type == KeyType.SPACE -> KeyKind.SPACE
        style == KeyStyle.MOD -> KeyKind.MOD
        style == KeyStyle.NUM || style == KeyStyle.ACCENT -> KeyKind.NUM
        else -> KeyKind.NORMAL
    }

    /** The legend's ink (letters, labels, icons) of a key. */
    fun legendInk(type: KeyType, style: KeyStyle, shift: ShiftState): Ink = when {
        type == KeyType.ENTER -> Ink.ACCENT_INK
        type == KeyType.SHIFT && shift == ShiftState.LOCKED -> Ink.ACCENT_INK
        type == KeyType.SPACE -> SPACE_LABEL
        style == KeyStyle.MOD -> Ink.TEXT_MOD
        style == KeyStyle.ACCENT -> Ink.ACCENT
        else -> Ink.TEXT
    }

    /** A character key's sub-legend (the top-row digit on a letter). */
    val HINT = Ink.MUTED
    /** The space bar's language, its ✦, its globe. */
    val SPACE_LABEL = Ink.TEXT_MOD
    val SPACE_STAR = Ink.ACCENT
    val SPACE_GLOBE = Ink.MUTED
    /** Shift once / auto: the filled arrow in the accent. */
    val SHIFT_ON = Ink.ACCENT

    /** Every ink a key of [type] / [style] draws at [shift] (legend and extras), for LG1's enumeration. */
    fun keyInks(type: KeyType, style: KeyStyle, shift: ShiftState): List<Ink> {
        val out = ArrayList<Ink>(4)
        out += legendInk(type, style, shift)
        when (type) {
            KeyType.CHAR -> out += HINT
            KeyType.SPACE -> { out += SPACE_STAR; out += SPACE_GLOBE }
            KeyType.SHIFT -> if (shift == ShiftState.ONCE || shift == ShiftState.AUTO) out += SHIFT_ON
            else -> Unit
        }
        return out
    }

    // ── the checked set ──────────────────────────────────────────────────
    /** One drawn pair, resolved for a palette. */
    class Check(val decl: Decl, val cap: KeyCap?, val kind: KeyKind?, val fg: Int, val bg: Int) {
        val ink: Ink get() = decl.ink
        val min: Double get() = decl.min
        val ratio: Double get() = ColorMath.contrast(fg, bg)
        val ok: Boolean get() = ratio >= min - 1e-9
        val name: String get() = "${decl.ink} on ${decl.surface}" + (if (cap != null) "/$cap/$kind" else "")
    }

    /** Every pair [decls] declares, resolved for [p] under each of [caps] (non-key pairs once). */
    fun checks(p: Palette, caps: Collection<KeyCap> = KeyCap.values().asList(), decls: List<Decl> = PAIRS): List<Check> {
        val out = ArrayList<Check>(decls.size * 4)
        for (d in decls) {
            val fg = d.ink.of(p)
            if (d.surface.key) {
                for (cap in caps) for (k in d.kinds) out += Check(d, cap, k, fg, surface(p, d.surface, cap, k))
            } else out += Check(d, null, null, fg, surface(p, d.surface))
        }
        return out
    }

    /**
     * What one configuration draws: the keys under [cap] and every bar / panel / Settings pair. The shape and the bar
     * style change geometry and which control shows a surface, never a colour (every surface they use is also drawn by
     * a panel), so they select the same set; LG1 sweeps them to prove it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun checks(p: Palette, cap: KeyCap, shape: KeyShape, bar: BarStyle): List<Check> = checks(p, listOf(cap))
}
