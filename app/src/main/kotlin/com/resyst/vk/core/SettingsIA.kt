package com.resyst.vk.core

/**
 * Where a setting is stored (r10, UX-2): [TEMA] = the active tema's look (`t.<id>.<key>`),
 * [DEVICE] = phone-wide (the full stored key: `phone.*`, `clip.*`, `update.*`, `mode`, `active`).
 */
enum class Scope { TEMA, DEVICE }

/**
 * Every control of the settings screen. [key] is the stored field it edits: the tema
 * field name for [Scope.TEMA], the full stored key for [Scope.DEVICE]; null for actions (links,
 * buttons, screens). [dependsOn] is the switch that enables it. Parents are declared before
 * their dependents (enum init order). [label] is the visible title (also the TalkBack name).
 */
enum class Ctl(
    val key: String?,
    val scope: Scope,
    val label: String,
    val dependsOn: Ctl? = null,
    val destructive: Boolean = false,
) {
    // which tema / mode is in use
    TEMA(ProfileCodec.ACTIVE_KEY, Scope.DEVICE, "Tema"),
    MODE(ProfileCodec.MODE_KEY, Scope.DEVICE, "Modo"),

    // appearance (the active tema)
    THEME("theme", Scope.TEMA, "Colores"),
    /** r8: "Modo día / noche" — one tap flips to the remembered light/dark partner. */
    DAY_NIGHT("altTheme", Scope.TEMA, "Día / noche"),
    ACCENT("accent", Scope.TEMA, "Acento"),
    SHAPE("shape", Scope.TEMA, "Forma"),
    CAP("cap", Scope.TEMA, "Tecla"),
    FONT("font", Scope.TEMA, "Fuente"),
    DENSITY("density", Scope.TEMA, "Densidad"),
    HEIGHT("heightScale", Scope.TEMA, "Altura"),
    SUB_LEGENDS("subLegends", Scope.TEMA, "Leyendas secundarias"),

    // keys & language (phone)
    LANG("phone.lang", Scope.DEVICE, "Idioma"),
    SYSTEM_LANGS(null, Scope.DEVICE, "Idiomas que ofrece Android"),
    HIDE_TOP_ROW("phone.hideTopRow", Scope.DEVICE, "Ocultar fila de caracteres especiales"),
    TOP_ROW("phone.topRow", Scope.DEVICE, "Fila superior", dependsOn = HIDE_TOP_ROW),
    EMOJI_KEY("phone.emojiKey", Scope.DEVICE, "Tecla de emojis"),
    DAY_NIGHT_CHIP("phone.dayNightChip", Scope.DEVICE, "Botón día / noche en el teclado"),
    POPUPS("phone.popups", Scope.DEVICE, "Vista previa de tecla"),
    LONG_PRESS("phone.longPressMs", Scope.DEVICE, "Pulsación larga"),

    // writing (phone)
    SUGGEST("phone.suggest", Scope.DEVICE, "Sugerencias"),
    SPACE_CORRECTS("phone.spaceCorrects", Scope.DEVICE, "El espacio aplica la corrección", dependsOn = SUGGEST),
    PERSONAL("phone.personal", Scope.DEVICE, "Sugerencias personales", dependsOn = SUGGEST),
    AUTO_CAP("phone.autoCap", Scope.DEVICE, "Mayúscula automática"),
    DOUBLE_SPACE("phone.doubleSpace", Scope.DEVICE, "Doble espacio = punto"),
    /** r10 (F-3): the keyboard never proposes offensive words (default on). */
    PROFANITY_FILTER("phone.profanityFilter", Scope.DEVICE, "Filtrar palabras ofensivas", dependsOn = SUGGEST),

    // feedback (phone)
    HAPTICS("phone.haptics", Scope.DEVICE, "Vibración"),
    HAPTIC_STRENGTH("phone.hapticStrength", Scope.DEVICE, "Intensidad", dependsOn = HAPTICS),
    SOUND("phone.sound", Scope.DEVICE, "Sonido de tecla"),
    SOUND_PACK("phone.soundPack", Scope.DEVICE, "Pack", dependsOn = SOUND),
    VOLUME("phone.volume", Scope.DEVICE, "Volumen", dependsOn = SOUND),

    // clipboard + learned data
    CLIP_HISTORY("clip.history", Scope.DEVICE, "Historial del portapapeles"),
    CLIP_PURGE("clip.purge", Scope.DEVICE, "Purgar tras 1 hora", dependsOn = CLIP_HISTORY),
    CLEAR_CLIP(null, Scope.DEVICE, "Borrar historial del portapapeles", destructive = true),
    FORGET_LEARNED(null, Scope.DEVICE, "Borrar lo aprendido", destructive = true),

    // r10 (F-4): "Lo que sé de ti" — see and delete, one by one, what the keyboard keeps
    KNOW_WORDS(null, Scope.DEVICE, "Palabras aprendidas"),
    KNOW_EMAILS(null, Scope.DEVICE, "Correos recordados"),
    KNOW_EMOJI(null, Scope.DEVICE, "Emojis recientes"),
    KNOW_CLIP(null, Scope.DEVICE, "Portapapeles guardado"),

    // about
    UPDATES(null, Scope.DEVICE, "Actualización"),
    /** r9: "Buscar actualizaciones al iniciar" — one release.json check per process start. */
    UPDATE_AUTO(ProfileCodec.UPDATE_AUTO_KEY, Scope.DEVICE, "Buscar actualizaciones al iniciar"),
    /** r10 (F-5): every request the app ever made to the internet, counted and listed. */
    CONNECTIONS(null, Scope.DEVICE, "Libro de conexiones"),
    RESET_ALL(null, Scope.DEVICE, "Restablecer ajustes y temas", destructive = true),
}

/** One settings page, one tap away from home. [preview] shows the live keyboard on top. */
data class SettingsPage(
    val id: String,
    val title: String,
    val scope: Scope,
    val controls: List<Ctl>,
    val preview: Boolean = false,
    /** One line under the title on home, saying what the page holds. */
    val summary: String = "",
)

/**
 * Information architecture of the settings screen (r7, rebuilt on temas + modos in r10): a home
 * with the tema picker, the mode, the live preview and the settings used daily, then seven
 * pages, each one tap away. Every control lives on exactly one page (pages are the complete
 * map); home only repeats the essentials as shortcuts. Checked by SettingsIATest (I1–I9, T1–T9).
 */
object SettingsIA {
    const val MAX_HOME = 8

    /** The settings Alan changes often — one tap from opening the app. */
    val HOME: List<Ctl> = listOf(
        Ctl.TEMA, Ctl.MODE, Ctl.DAY_NIGHT, Ctl.LANG, Ctl.HIDE_TOP_ROW, Ctl.SUGGEST, Ctl.HAPTICS, Ctl.SOUND,
    )

    val PAGES: List<SettingsPage> = listOf(
        SettingsPage("modos", "Temas y modos", Scope.DEVICE, listOf(
            Ctl.TEMA, Ctl.MODE,
        ), summary = "Qué tema usas y si hay un modo encendido (Código, Juego)"),
        SettingsPage("apariencia", "Apariencia del tema", Scope.TEMA, listOf(
            Ctl.THEME, Ctl.DAY_NIGHT, Ctl.ACCENT, Ctl.SHAPE, Ctl.CAP, Ctl.FONT, Ctl.DENSITY, Ctl.HEIGHT, Ctl.SUB_LEGENDS,
        ), preview = true, summary = "Colores, acento, forma, fuente y altura del tema activo"),
        SettingsPage("teclas", "Teclas e idioma", Scope.DEVICE, listOf(
            Ctl.LANG, Ctl.SYSTEM_LANGS, Ctl.HIDE_TOP_ROW, Ctl.TOP_ROW, Ctl.EMOJI_KEY, Ctl.DAY_NIGHT_CHIP, Ctl.POPUPS, Ctl.LONG_PRESS,
        ), preview = true, summary = "Idioma, fila superior, emojis, burbujas, pulsación larga"),
        SettingsPage("escritura", "Escritura", Scope.DEVICE, listOf(
            Ctl.SUGGEST, Ctl.SPACE_CORRECTS, Ctl.PERSONAL, Ctl.PROFANITY_FILTER, Ctl.AUTO_CAP, Ctl.DOUBLE_SPACE,
        ), summary = "Sugerencias, corrección, filtro de groserías, mayúsculas"),
        SettingsPage("respuesta", "Sonido y vibración", Scope.DEVICE, listOf(
            Ctl.HAPTICS, Ctl.HAPTIC_STRENGTH, Ctl.SOUND, Ctl.SOUND_PACK, Ctl.VOLUME,
        ), summary = "Vibración, intensidad, sonido y volumen"),
        SettingsPage("privacidad", "Portapapeles y privacidad", Scope.DEVICE, listOf(
            Ctl.CLIP_HISTORY, Ctl.CLIP_PURGE, Ctl.CLEAR_CLIP, Ctl.FORGET_LEARNED,
        ), summary = "Historial del portapapeles y lo que el teclado aprendió"),
        SettingsPage("datos", "Lo que sé de ti", Scope.DEVICE, listOf(
            Ctl.KNOW_WORDS, Ctl.KNOW_EMAILS, Ctl.KNOW_EMOJI, Ctl.KNOW_CLIP,
        ), summary = "Ver y borrar, una a una, las palabras, correos y emojis que guardo · solo en este teléfono"),
        SettingsPage("acerca", "Acerca de", Scope.DEVICE, listOf(
            Ctl.UPDATES, Ctl.UPDATE_AUTO, Ctl.CONNECTIONS, Ctl.RESET_ALL,
        ), summary = "Versión, actualizaciones, libro de conexiones, restablecer"),
    )

    fun page(id: String?): SettingsPage? = PAGES.firstOrNull { it.id == id }

    fun pageOf(ctl: Ctl): SettingsPage? = PAGES.firstOrNull { ctl in it.controls }

    /**
     * Taps from the settings home to operate [ctl]: 1 for a home shortcut, 2 for a page control
     * (open the page, then the control); null if no surface shows it.
     */
    fun taps(ctl: Ctl): Int? = when {
        ctl in HOME -> 1
        pageOf(ctl) != null -> 2
        else -> null
    }

    /**
     * A page's controls in render order with their state: a dependent whose parent switch is
     * off stays visible but disabled (the user sees what the switch unlocks), indented one level
     * per parent. Destructive actions always render last on their page.
     */
    fun rows(page: SettingsPage, on: (Ctl) -> Boolean): List<Row> {
        val (danger, normal) = page.controls.partition { it.destructive }
        return (normal + danger).map { c -> Row(c, indent = depth(c), enabled = enabled(c, on)) }
    }

    /** "Ocultar fila…" is the one inverted switch: its dependent (Fila superior) needs it OFF. */
    fun enabled(c: Ctl, on: (Ctl) -> Boolean): Boolean {
        val p = c.dependsOn ?: return true
        val parentOn = if (p == Ctl.HIDE_TOP_ROW) !on(p) else on(p)
        return parentOn && enabled(p, on)
    }

    private fun depth(c: Ctl): Int = c.dependsOn?.let { depth(it) + 1 } ?: 0

    data class Row(val ctl: Ctl, val indent: Int, val enabled: Boolean)
}
