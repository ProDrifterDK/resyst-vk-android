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
 * their dependents (enum init order). [label] is the visible title (also the TalkBack name), a resource.
 */
enum class Ctl(
    val key: String?,
    val scope: Scope,
    val dependsOn: Ctl? = null,
    val destructive: Boolean = false,
) {
    // which tema / mode is in use
    TEMA(ProfileCodec.ACTIVE_KEY, Scope.DEVICE),
    MODE(ProfileCodec.MODE_KEY, Scope.DEVICE),
    /** r14 (FS1): the active tema follows the system's dark mode (device-wide; fresh installs on, upgrades off). */
    FOLLOW_SYSTEM(ProfileCodec.FOLLOW_KEY, Scope.DEVICE),
    /** r14 (PS3): create a tema from the one in use, rename it, delete one. */
    TEMA_MANAGE(null, Scope.DEVICE),

    // appearance (the active tema)
    /** r14 (PS2): curated looks, one tap on the active tema, with «Deshacer». */
    STYLES(null, Scope.TEMA),
    THEME("theme", Scope.TEMA),
    /** r8: "Modo día / noche" — one tap flips to the remembered light/dark partner. */
    DAY_NIGHT("altTheme", Scope.TEMA),
    ACCENT("accent", Scope.TEMA),
    /** r14 (LG2): the accent's hue in the surfaces. */
    TINT("tint", Scope.TEMA),
    SHAPE("shape", Scope.TEMA),
    CAP("cap", Scope.TEMA),
    FONT("font", Scope.TEMA),
    DENSITY("density", Scope.TEMA),
    HEIGHT("heightScale", Scope.TEMA),
    SUB_LEGENDS("subLegends", Scope.TEMA),
    /** r14: the legends' weight, size and case. */
    LEGEND_WEIGHT("legendWeight", Scope.TEMA),
    LEGEND_SCALE("legendScale", Scope.TEMA),
    UPPER_LEGENDS("upperLegends", Scope.TEMA),
    /** r14: the key preview's shape and the bar's style. */
    BUBBLE("bubble", Scope.TEMA),
    BAR_STYLE("barStyle", Scope.TEMA),

    // keys & language (phone)
    LANG("phone.lang", Scope.DEVICE),
    SYSTEM_LANGS(null, Scope.DEVICE),
    /** r10 (bet 4): ES ⇄ EN by the last words typed (suggestions; the layout stays). */
    BILINGUAL("phone.bilingual", Scope.DEVICE),
    /** r13h (LF1): a vertical swipe on the space bar switches ES ⇄ EN; off by default. */
    SPACE_FLICK("phone.spaceFlick", Scope.DEVICE),
    /** r10 (bet 4): Chile / Latinoamérica / España ranking of the Spanish lexicon. */
    REGION("phone.region", Scope.DEVICE),
    HIDE_TOP_ROW("phone.hideTopRow", Scope.DEVICE),
    TOP_ROW("phone.topRow", Scope.DEVICE, dependsOn = HIDE_TOP_ROW),
    EMOJI_KEY("phone.emojiKey", Scope.DEVICE),
    DAY_NIGHT_CHIP("phone.dayNightChip", Scope.DEVICE),
    /** r10 (UX-13): keys narrowed to one side; also a tile in the quick panel. */
    ONE_HANDED("phone.oneHanded", Scope.DEVICE),
    POPUPS("phone.popups", Scope.DEVICE),
    LONG_PRESS("phone.longPressMs", Scope.DEVICE),

    // writing (phone)
    SUGGEST("phone.suggest", Scope.DEVICE),
    SPACE_CORRECTS("phone.spaceCorrects", Scope.DEVICE, dependsOn = SUGGEST),
    PERSONAL("phone.personal", Scope.DEVICE, dependsOn = SUGGEST),
    AUTO_CAP("phone.autoCap", Scope.DEVICE),
    DOUBLE_SPACE("phone.doubleSpace", Scope.DEVICE),
    /** r10 (F-3): the keyboard never proposes offensive words (default on). */
    PROFANITY_FILTER("phone.profanityFilter", Scope.DEVICE, dependsOn = SUGGEST),
    /** r10 (UX-8): "¿…?" offer when a Spanish sentence closes without its opener. */
    AUTO_OPENERS("phone.autoOpeners", Scope.DEVICE),
    /** r13f (offers, PV4): the bar offers the likely word for its context (caza → casa); a tap replaces it. */
    CONTEXT_OFFERS("phone.contextOffers", Scope.DEVICE, dependsOn = SUGGEST),

    // feedback (phone)
    HAPTICS("phone.haptics", Scope.DEVICE),
    HAPTIC_STRENGTH("phone.hapticStrength", Scope.DEVICE, dependsOn = HAPTICS),
    /** r14 (HS1): haptics by key kind (default) or one pulse for every key (0.13.0). */
    HAPTIC_PROFILE("phone.hapticProfile", Scope.DEVICE, dependsOn = HAPTICS),
    SOUND("phone.sound", Scope.DEVICE),
    SOUND_PACK("phone.soundPack", Scope.DEVICE, dependsOn = SOUND),
    VOLUME("phone.volume", Scope.DEVICE, dependsOn = SOUND),
    /** r14 (MO2): Completas / Sutiles / Ninguna; Android's "Quitar animaciones" forces Ninguna. */
    MOTION("phone.motion", Scope.DEVICE),

    // clipboard + learned data
    CLIP_HISTORY("clip.history", Scope.DEVICE),
    CLIP_PURGE("clip.purge", Scope.DEVICE, dependsOn = CLIP_HISTORY),
    CLEAR_CLIP(null, Scope.DEVICE, destructive = true),
    FORGET_LEARNED(null, Scope.DEVICE, destructive = true),
    /**
     * r11b: the opt-in GIF search with KLIPY (OFF by default; on only through its disclosure).
     * Stored in its own prefs file (GifStore), not in the profile store: no key here.
     */
    GIF_SEARCH(null, Scope.DEVICE),
    /** r11b: replace the anonymous id sent to KLIPY (only while the GIF search is on). */
    GIF_NEW_ID(null, Scope.DEVICE),

    // r10 (F-4): "Lo que sé de ti" — see and delete, one by one, what the keyboard keeps
    KNOW_WORDS(null, Scope.DEVICE),
    /** r13i (KN1): the pair memory (files/personal/pairs.bin): size, top pairs, forget one, clear all. */
    KNOW_PAIRS(null, Scope.DEVICE),
    /** r13i (KN1): the pair memory's switch (default on). */
    PAIR_MEMORY("phone.pairMemory", Scope.DEVICE),
    /** r13 (LR5): the corrections the user undid, never applied again (files/personal/rejections.json). */
    KNOW_REJECTIONS(null, Scope.DEVICE),
    KNOW_EMAILS(null, Scope.DEVICE),
    KNOW_EMOJI(null, Scope.DEVICE),
    KNOW_CLIP(null, Scope.DEVICE),

    // about
    UPDATES(null, Scope.DEVICE),
    /** r9/r11c: one release.json check when the keyboard opens, at most every [OpenCheck.OK_HOURS] h. */
    UPDATE_AUTO(ProfileCodec.UPDATE_AUTO_KEY, Scope.DEVICE),
    /** r10 (F-5): every request the app ever made to the internet, counted and listed. */
    CONNECTIONS(null, Scope.DEVICE),
    RESET_ALL(null, Scope.DEVICE, destructive = true),
    ;

    /** The visible title (also the TalkBack name), in the language of [t] (`ctl_<name>`). */
    fun label(t: Txt): String = t.need("ctl_" + name.lowercase())
}

/** One settings page, one tap away from home. [preview] shows the live keyboard on top. */
data class SettingsPage(
    val id: String,
    val scope: Scope,
    val controls: List<Ctl>,
    val preview: Boolean = false,
    /** r14: a group heading drawn before these controls (the long «Apariencia» page reads in sections). */
    val groups: Set<Ctl> = emptySet(),
) {
    /** The page title (`page_<id>`). */
    fun title(t: Txt): String = t.need("page_$id")

    /** One line under the title on home, saying what the page holds (`page_<id>_summary`). */
    fun summary(t: Txt): String = t.need("page_${id}_summary")
}

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
        SettingsPage("modos", Scope.DEVICE, listOf(
            Ctl.TEMA, Ctl.FOLLOW_SYSTEM, Ctl.TEMA_MANAGE, Ctl.MODE,
        )),
        SettingsPage("apariencia", Scope.TEMA, listOf(
            Ctl.STYLES,
            Ctl.THEME, Ctl.DAY_NIGHT, Ctl.ACCENT, Ctl.TINT,
            Ctl.SHAPE, Ctl.CAP, Ctl.DENSITY, Ctl.HEIGHT,
            Ctl.FONT, Ctl.LEGEND_WEIGHT, Ctl.LEGEND_SCALE, Ctl.UPPER_LEGENDS, Ctl.SUB_LEGENDS,
            Ctl.BUBBLE, Ctl.BAR_STYLE,
        ), preview = true,
            groups = setOf(Ctl.STYLES, Ctl.THEME, Ctl.SHAPE, Ctl.FONT, Ctl.BUBBLE)),
        SettingsPage("teclas", Scope.DEVICE, listOf(
            Ctl.LANG, Ctl.SYSTEM_LANGS, Ctl.BILINGUAL, Ctl.SPACE_FLICK, Ctl.REGION, Ctl.HIDE_TOP_ROW, Ctl.TOP_ROW, Ctl.EMOJI_KEY, Ctl.DAY_NIGHT_CHIP, Ctl.ONE_HANDED, Ctl.POPUPS, Ctl.LONG_PRESS,
        ), preview = true),
        SettingsPage("escritura", Scope.DEVICE, listOf(
            Ctl.SUGGEST, Ctl.SPACE_CORRECTS, Ctl.CONTEXT_OFFERS, Ctl.PERSONAL, Ctl.PROFANITY_FILTER, Ctl.AUTO_CAP, Ctl.DOUBLE_SPACE, Ctl.AUTO_OPENERS,
        )),
        SettingsPage("respuesta", Scope.DEVICE, listOf(
            Ctl.HAPTICS, Ctl.HAPTIC_STRENGTH, Ctl.HAPTIC_PROFILE, Ctl.SOUND, Ctl.SOUND_PACK, Ctl.VOLUME, Ctl.MOTION,
        )),
        SettingsPage("privacidad", Scope.DEVICE, listOf(
            Ctl.CLIP_HISTORY, Ctl.CLIP_PURGE, Ctl.GIF_SEARCH, Ctl.GIF_NEW_ID, Ctl.CLEAR_CLIP, Ctl.FORGET_LEARNED,
        )),
        SettingsPage("datos", Scope.DEVICE, listOf(
            Ctl.KNOW_WORDS, Ctl.KNOW_PAIRS, Ctl.PAIR_MEMORY, Ctl.KNOW_REJECTIONS, Ctl.KNOW_EMAILS, Ctl.KNOW_EMOJI, Ctl.KNOW_CLIP,
        )),
        SettingsPage("acerca", Scope.DEVICE, listOf(
            Ctl.UPDATES, Ctl.UPDATE_AUTO, Ctl.CONNECTIONS, Ctl.RESET_ALL,
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
