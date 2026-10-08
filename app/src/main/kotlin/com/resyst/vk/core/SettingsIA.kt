package com.resyst.vk.core

/** Whether a setting belongs to the profile being edited or to the whole phone. */
enum class Scope { PROFILE, DEVICE }

/**
 * Every control of the settings screen. [key] is the stored field it edits: the per-profile
 * `ProfileCodec` field name (`p.<id>.<key>`) for [Scope.PROFILE], the full key for
 * [Scope.DEVICE]; null for actions (links, buttons). [dependsOn] is the switch that reveals it.
 * Parents are declared before their dependents (enum init order).
 */
enum class Ctl(
    val key: String?,
    val scope: Scope,
    val dependsOn: Ctl? = null,
    val destructive: Boolean = false,
) {
    // appearance
    THEME("theme", Scope.PROFILE),
    /** r8: "Modo día / noche" — one tap flips to the remembered light/dark partner. */
    DAY_NIGHT("altTheme", Scope.PROFILE),
    DAY_NIGHT_CHIP("dayNightChip", Scope.PROFILE),
    ACCENT("accent", Scope.PROFILE),
    SHAPE("shape", Scope.PROFILE),
    CAP("cap", Scope.PROFILE),
    FONT("font", Scope.PROFILE),
    DENSITY("density", Scope.PROFILE),
    HEIGHT("heightScale", Scope.PROFILE),
    SUB_LEGENDS("subLegends", Scope.PROFILE),

    // keys & language
    LANG("lang", Scope.PROFILE),
    SYSTEM_LANGS(null, Scope.PROFILE),
    HIDE_TOP_ROW("hideTopRow", Scope.PROFILE),
    TOP_ROW("topRow", Scope.PROFILE, dependsOn = HIDE_TOP_ROW),
    EMOJI_KEY("emojiKey", Scope.PROFILE),
    POPUPS("popups", Scope.PROFILE),
    LONG_PRESS("longPressMs", Scope.PROFILE),

    // writing
    SUGGEST("suggest", Scope.PROFILE),
    SPACE_CORRECTS("spaceCorrects", Scope.PROFILE, dependsOn = SUGGEST),
    PERSONAL("personal", Scope.PROFILE, dependsOn = SUGGEST),
    AUTO_CAP("autoCap", Scope.PROFILE),
    DOUBLE_SPACE("doubleSpace", Scope.PROFILE),

    // feedback
    HAPTICS("haptics", Scope.PROFILE),
    HAPTIC_STRENGTH("hapticStrength", Scope.PROFILE, dependsOn = HAPTICS),
    SOUND("sound", Scope.PROFILE),
    SOUND_PACK("soundPack", Scope.PROFILE, dependsOn = SOUND),
    VOLUME("volume", Scope.PROFILE, dependsOn = SOUND),

    // phone-wide: clipboard + learned data
    CLIP_HISTORY("clip.history", Scope.DEVICE),
    CLIP_PURGE("clip.purge", Scope.DEVICE, dependsOn = CLIP_HISTORY),
    CLEAR_CLIP(null, Scope.DEVICE, destructive = true),
    FORGET_LEARNED(null, Scope.DEVICE, destructive = true),

    // about
    /** r9: "Buscar actualizaciones al iniciar" — one release.json check per process start. */
    UPDATE_AUTO(ProfileCodec.UPDATE_AUTO_KEY, Scope.DEVICE),
    UPDATES(null, Scope.DEVICE),
    RESET_PROFILES(null, Scope.DEVICE, destructive = true),
}

/** One settings page, one tap away from home. [preview] shows the live keyboard on top. */
data class SettingsPage(
    val id: String,
    val title: String,
    val scope: Scope,
    val controls: List<Ctl>,
    val preview: Boolean = false,
)

/**
 * Information architecture of the settings screen (r7): a home with the profile switcher, the
 * live preview and the settings used daily, then six pages, each one tap away. Every control
 * lives on exactly one page (pages are the complete map); home only repeats the essentials as
 * shortcuts. Checked by SettingsIATest (failure modes I1–I9).
 */
object SettingsIA {
    const val MAX_HOME = 8

    /** The settings Alan changes often — one tap from opening the app. */
    val HOME: List<Ctl> = listOf(
        Ctl.THEME, Ctl.DAY_NIGHT, Ctl.ACCENT, Ctl.LANG, Ctl.HIDE_TOP_ROW, Ctl.SUGGEST, Ctl.HAPTICS, Ctl.SOUND,
    )

    val PAGES: List<SettingsPage> = listOf(
        SettingsPage("apariencia", "Apariencia", Scope.PROFILE, listOf(
            Ctl.THEME, Ctl.DAY_NIGHT, Ctl.DAY_NIGHT_CHIP, Ctl.ACCENT, Ctl.SHAPE, Ctl.CAP, Ctl.FONT, Ctl.DENSITY, Ctl.HEIGHT, Ctl.SUB_LEGENDS,
        ), preview = true),
        SettingsPage("teclas", "Teclas e idioma", Scope.PROFILE, listOf(
            Ctl.LANG, Ctl.SYSTEM_LANGS, Ctl.HIDE_TOP_ROW, Ctl.TOP_ROW, Ctl.EMOJI_KEY, Ctl.POPUPS, Ctl.LONG_PRESS,
        ), preview = true),
        SettingsPage("escritura", "Escritura", Scope.PROFILE, listOf(
            Ctl.SUGGEST, Ctl.SPACE_CORRECTS, Ctl.PERSONAL, Ctl.AUTO_CAP, Ctl.DOUBLE_SPACE,
        )),
        SettingsPage("respuesta", "Sonido y vibración", Scope.PROFILE, listOf(
            Ctl.HAPTICS, Ctl.HAPTIC_STRENGTH, Ctl.SOUND, Ctl.SOUND_PACK, Ctl.VOLUME,
        )),
        SettingsPage("privacidad", "Portapapeles y privacidad", Scope.DEVICE, listOf(
            Ctl.CLIP_HISTORY, Ctl.CLIP_PURGE, Ctl.CLEAR_CLIP, Ctl.FORGET_LEARNED,
        )),
        SettingsPage("acerca", "Acerca de", Scope.DEVICE, listOf(
            Ctl.UPDATES, Ctl.UPDATE_AUTO, Ctl.RESET_PROFILES,
        )),
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
}
