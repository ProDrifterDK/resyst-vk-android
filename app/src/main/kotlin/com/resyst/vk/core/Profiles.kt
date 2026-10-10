package com.resyst.vk.core

enum class KeyShape(val radiusDp: Float) { SQUARE(2f), SOFT(7f), ROUND(14f) }
enum class KeyCap { RAISED, FLAT, OUTLINE }
enum class KeyFont { BRAND, TECH, HUMAN }
enum class Density(val gapDp: Float) { TIGHT(3f), NORMAL(5f), AIRY(8f) }
enum class SoundPack { CLICK, THOCK, TYPE, BUBBLE }
enum class SoundKind { KEY, MOD, SPACE, ENTER }

/**
 * The effective keyboard settings the IME and the view consume. Since r10 nobody stores a
 * whole [KbSettings]: its appearance part ([Look]) belongs to the active [Tema], the rest is
 * phone-wide ([ProfileStore.phone]), and an optional [Mode] overrides both by name.
 * [ProfileStore.settings] assembles it.
 */
data class KbSettings(
    val theme: String = "lab",
    val accent: Int? = null,
    val shape: KeyShape = KeyShape.SOFT,
    val cap: KeyCap = KeyCap.RAISED,
    val font: KeyFont = KeyFont.BRAND,
    val density: Density = Density.NORMAL,
    val subLegends: Boolean = true,
    val heightScale: Float = 1f,
    val sound: Boolean = false,
    val soundPack: SoundPack = SoundPack.CLICK,
    val volume: Float = 0.7f,
    val haptics: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.MEDIUM,
    val popups: Boolean = true,
    val longPressMs: Int = 350,
    val suggest: Boolean = true,
    /** Space replaces a typo with the confident correction (needs [suggest]); ⌫ undoes it. */
    val spaceCorrects: Boolean = true,
    /** Learn from what the user types (on-device only) and suggest it (needs [suggest]). */
    val personal: Boolean = true,
    val lang: Lang = Lang.ES,
    val topRow: TopRow = TopRow.ACCENTS,
    val autoCap: Boolean = true,
    val doubleSpace: Boolean = true,
    /** Hide the special-characters row above the letters. Off = row visible (default). */
    val hideTopRow: Boolean = false,
    /** r8: dedicated emoji key next to the comma (opens the emoji panel). */
    val emojiKey: Boolean = true,
    /**
     * r8: the sun/moon chip in the strip flips day/night ([DayNight]). r10: phone-wide, and OFF by
     * default — the strip is for typing; Día / noche lives in the quick panel under ⚙ (UX-3).
     */
    val dayNightChip: Boolean = false,
    /** r8: the theme the last day/night flip left (null = never flipped). Part of the [Look]. */
    val altTheme: String? = null,
    /** r10 (F-3): proposals (completions, predictions, corrections) skip offensive words. Phone-wide. */
    val profanityFilter: Boolean = true,
    /** r10 (UX-13): keys narrowed to one side, phone-wide. */
    val oneHanded: OneHand = OneHand.OFF,
    /** r10 (UX-8): closing a Spanish sentence with ?/! offers the missing ¿/¡ in the bar. */
    val autoOpeners: Boolean = true,
    /** r10 (bet 4): suggest + correct in the language of the last words (ES ⇄ EN), see [BiLang]. */
    val bilingual: Boolean = true,
    /** r10 (bet 4): which Spanish the lexicon is ranked for ([Regional]). */
    val region: Region = Region.ES_CL,
) {
    /** The row actually laid out: [topRow] unless hidden. */
    val effectiveTopRow: TopRow get() = if (hideTopRow) TopRow.NONE else topRow

    /** Letters-layer row count; keyboard height is anchored to it. */
    val baseRowCount: Int get() = if (effectiveTopRow == TopRow.NONE) 4 else 5
}

/** r10: the appearance half of [KbSettings], what a [Tema] owns. */
data class Look(
    val theme: String = "lab",
    val accent: Int? = null,
    val shape: KeyShape = KeyShape.SOFT,
    val cap: KeyCap = KeyCap.RAISED,
    val font: KeyFont = KeyFont.BRAND,
    val density: Density = Density.NORMAL,
    val subLegends: Boolean = true,
    val heightScale: Float = 1f,
    val altTheme: String? = null,
) {
    fun applyTo(s: KbSettings): KbSettings = s.copy(
        theme = theme, accent = accent, shape = shape, cap = cap, font = font, density = density,
        subLegends = subLegends, heightScale = heightScale, altTheme = altTheme,
    )

    companion object {
        /** Field names (codec keys) a tema owns; every other [KbSettings] field is phone-wide. */
        val FIELDS = setOf("theme", "accent", "shape", "cap", "font", "density", "subLegends", "heightScale", "altTheme")

        fun of(s: KbSettings) = Look(s.theme, s.accent, s.shape, s.cap, s.font, s.density, s.subLegends, s.heightScale, s.altTheme)
    }
}

/** r10: a named appearance (Resyst, Arcade, Pizarra…). Switching it never touches behavior. */
data class Tema(val id: String, val name: String, val icon: String, val look: Look)

/**
 * r10: an explicit, named override on top of the phone settings and the tema (UX-2). Off by
 * default; the user turns it on from the quick panel or the Modos page. Its effect is fixed
 * and spelled out in [summary], so a mode is never an opaque bag of settings.
 */
enum class Mode(val id: String, val label: String, val icon: String, val summary: String) {
    NONE("none", "Ninguno", "✦", "El teclado usa tus ajustes tal cual."),
    CODE("code", "Código", "⌘", "Sin sugerencias ni corrección, sin mayúscula automática ni doble espacio, fila de números y fuente mono. No aprende lo que escribes."),
    GAME("game", "Juego", "◆", "Compacto: sin fila superior, sin sugerencias ni burbujas, teclas más juntas y bajas.");

    fun apply(s: KbSettings): KbSettings = when (this) {
        NONE -> s
        CODE -> s.copy(
            suggest = false, spaceCorrects = false, personal = false, autoCap = false, doubleSpace = false, autoOpeners = false,
            hideTopRow = false, topRow = TopRow.NUMBERS, font = KeyFont.TECH,
        )
        GAME -> s.copy(
            suggest = false, autoCap = false, doubleSpace = false, popups = false, hideTopRow = true,
            density = Density.TIGHT, heightScale = minOf(s.heightScale, 0.9f).coerceAtLeast(ProfileCodec.HEIGHT_MIN),
        )
    }

    companion object {
        fun byId(id: String?): Mode? = values().firstOrNull { it.id == id }
    }
}

/**
 * Everything the user configures (r10, UX-2): the temas ([active] is the one in use), the
 * phone-wide behavior ([phone]; its appearance fields are ignored), the optional [mode], and
 * the device-wide clipboard / update switches. The class keeps its r1 name so call sites read
 * the same; "profile" now only survives in the v1 codec migration.
 */
data class ProfileStore(
    val temas: List<Tema>,
    val active: String,
    val phone: KbSettings = KbSettings(),
    val mode: Mode = Mode.NONE,
    val clip: ClipSettings = ClipSettings(),
    val autoUpdateCheck: Boolean = true,
) {
    val activeTema: Tema get() = byId(active) ?: temas.first()

    /** Phone behavior + active tema look, without the mode: what the settings screen edits. */
    val base: KbSettings get() = activeTema.look.applyTo(phone)

    /** What the keyboard runs with: [base] + [mode]'s overrides. */
    val settings: KbSettings get() = mode.apply(base)

    fun byId(id: String): Tema? = temas.firstOrNull { it.id == id }

    fun withTema(id: String): ProfileStore = if (byId(id) != null) copy(active = id) else this

    fun nextTemaId(): String {
        val i = temas.indexOfFirst { it.id == active }
        return temas[(i + 1).mod(temas.size)].id
    }

    fun withMode(m: Mode): ProfileStore = copy(mode = m)

    fun updateLook(id: String, f: (Look) -> Look): ProfileStore =
        copy(temas = temas.map { if (it.id == id) it.copy(look = f(it.look)) else it })

    fun updatePhone(f: (KbSettings) -> KbSettings): ProfileStore = copy(phone = NEUTRAL.applyTo(f(phone)))

    /**
     * Edit through the flat view ([base], never the mode-overridden one, so a mode can't leak
     * into what is stored): look fields land in the active tema, the rest in [phone].
     */
    fun edit(f: (KbSettings) -> KbSettings): ProfileStore {
        val next = f(base)
        return updateLook(activeTema.id) { Look.of(next) }.copy(phone = NEUTRAL.applyTo(next))
    }

    private companion object {
        /** [phone] never carries a look of its own (keeps equality and storage canonical). */
        val NEUTRAL = Look()
    }
}

/**
 * Flat string map ⇄ [ProfileStore] (SharedPreferences friendly). Every value read back is
 * validated; anything unknown or out of range falls back to its default.
 *
 * v2 (r10) keys: `v=2`, `active`, `order`, `mode`, `clip.*`, `update.auto`,
 * `t.<id>.{name,icon,<look field>}`, `phone.<behavior field>`.
 * v1 (r1–r9) stored four whole profiles under `p.<id>.*`; [decode] migrates them ([migrateV1]).
 */
object ProfileCodec {
    const val HEIGHT_MIN = 0.8f
    const val HEIGHT_MAX = 1.3f
    const val VERSION = "2"
    /** r9: "Buscar actualizaciones automáticamente" (device-wide; key kept from r9). */
    const val UPDATE_AUTO_KEY = "update.auto"
    const val MODE_KEY = "mode"
    const val ACTIVE_KEY = "active"
    const val PHONE = "phone."
    const val TEMA = "t."
    private val ID = Regex("^[a-z0-9_-]{1,24}$")

    /** Keys of the r1–r9 layout; SettingsRepo drops them once a v2 store is written. */
    fun isLegacyKey(k: String) = k.startsWith("p.")

    /** Keys whose whole namespace the codec writes: stale ones may be removed on save. */
    fun owns(k: String) = isLegacyKey(k) || k.startsWith(TEMA) || k.startsWith(PHONE)

    fun seed(): ProfileStore {
        val base = Look()
        return ProfileStore(
            temas = listOf(
                Tema("resyst", "Resyst", "✦", base),
                Tema("arcade", "Arcade", "◆", base.copy(theme = "arcade", cap = KeyCap.FLAT, density = Density.TIGHT, heightScale = 0.9f)),
                Tema("pizarra", "Pizarra", "✎", base.copy(theme = "slate", font = KeyFont.HUMAN, subLegends = false, heightScale = 1.1f)),
            ),
            active = "resyst",
        )
    }

    fun encode(st: ProfileStore): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["v"] = VERSION
        m["active"] = st.active
        m["order"] = st.temas.joinToString(",") { it.id }
        m[MODE_KEY] = st.mode.id
        m["clip.history"] = st.clip.history.toString()
        m["clip.purge"] = st.clip.purgeHour.toString()
        m[UPDATE_AUTO_KEY] = st.autoUpdateCheck.toString()
        for (t in st.temas) {
            val k = "$TEMA${t.id}."
            m[k + "name"] = t.name
            m[k + "icon"] = t.icon
            for ((f, v) in fields(t.look.applyTo(KbSettings()))) if (f in Look.FIELDS) m[k + f] = v
        }
        for ((f, v) in fields(st.phone)) if (f !in Look.FIELDS) m[PHONE + f] = v
        return m
    }

    /** Every [KbSettings] field as its stored string, keyed by the field name. */
    fun fields(s: KbSettings): Map<String, String> = linkedMapOf(
        "theme" to s.theme,
        "accent" to (s.accent?.let(ColorMath::toHex) ?: ""),
        "shape" to s.shape.name,
        "cap" to s.cap.name,
        "font" to s.font.name,
        "density" to s.density.name,
        "subLegends" to s.subLegends.toString(),
        "heightScale" to s.heightScale.toString(),
        "altTheme" to (s.altTheme ?: ""),
        "sound" to s.sound.toString(),
        "soundPack" to s.soundPack.name,
        "volume" to s.volume.toString(),
        "haptics" to s.haptics.toString(),
        "hapticStrength" to s.hapticStrength.name,
        "popups" to s.popups.toString(),
        "longPressMs" to s.longPressMs.toString(),
        "suggest" to s.suggest.toString(),
        "spaceCorrects" to s.spaceCorrects.toString(),
        "personal" to s.personal.toString(),
        "lang" to s.lang.name,
        "topRow" to s.topRow.name,
        "autoCap" to s.autoCap.toString(),
        "doubleSpace" to s.doubleSpace.toString(),
        "hideTopRow" to s.hideTopRow.toString(),
        "emojiKey" to s.emojiKey.toString(),
        "dayNightChip" to s.dayNightChip.toString(),
        "profanityFilter" to s.profanityFilter.toString(),
        "oneHanded" to s.oneHanded.name,
        "autoOpeners" to s.autoOpeners.toString(),
        "bilingual" to s.bilingual.toString(),
        "region" to s.region.name,
    )

    fun decode(raw: Map<String, *>): ProfileStore {
        val v2 = raw["v"]?.toString() == VERSION
        if (!v2 && raw.keys.any { it is String && isLegacyKey(it) }) return migrateV1(raw)
        val seed = seed()
        val ids = order(raw) ?: seed.temas.map { it.id }
        val temas = ids.map { id ->
            val fallback = seed.byId(id) ?: Tema(id, id, "✦", Look())
            val k = "$TEMA$id."
            val s = read({ raw[k + it]?.toString() }, fallback.look.applyTo(KbSettings()))
            Tema(id, name(raw[k + "name"], fallback.name), icon(raw[k + "icon"], fallback.icon), Look.of(s))
        }
        val phone = Look().applyTo(read({ raw[PHONE + it]?.toString() }, KbSettings()))
        return ProfileStore(
            temas = temas,
            active = activeOf(raw, temas),
            phone = phone,
            mode = Mode.byId(raw[MODE_KEY]?.toString()) ?: Mode.NONE,
            clip = clip(raw),
            autoUpdateCheck = flag(raw, UPDATE_AUTO_KEY, true),
        )
    }

    /**
     * r1–r9 → r10, tolerant and lossless for what the user sees: every stored profile becomes a
     * tema with the same id, name, icon and appearance (themes are never reset); the active
     * profile's behavior becomes the phone-wide behavior. The old "Juego" profile was a mode in
     * disguise: if it was active, the phone takes the behavior of the first other profile and
     * the Juego mode is turned on, so the keyboard still looks and behaves as it did.
     */
    fun migrateV1(raw: Map<String, *>): ProfileStore {
        val legacy = legacySeed()
        val ids = order(raw) ?: legacy.map { it.first }
        val settings = ids.associateWith { id ->
            val d = legacy.firstOrNull { it.first == id }?.third ?: KbSettings()
            read({ raw["p.$id.$it"]?.toString() }, d)
        }
        val temas = ids.map { id ->
            val l = legacy.firstOrNull { it.first == id }
            Tema(id, name(raw["p.$id.name"], l?.second ?: id), icon(raw["p.$id.icon"], legacyIcon(id)), Look.of(settings.getValue(id)))
        }
        val active = activeOf(raw, temas)
        val gaming = active == LEGACY_GAME
        val behaviorFrom = if (gaming) ids.firstOrNull { it != LEGACY_GAME } else active
        // r10 (QS3): r8 defaulted the strip's sun/moon ON; the strip is now for typing and Día /
        // noche lives in the quick panel. The option stays in Teclas e idioma.
        val phone = Look().applyTo(behaviorFrom?.let { settings[it] } ?: KbSettings()).copy(dayNightChip = false)
        return ProfileStore(
            temas = temas,
            active = active,
            phone = phone,
            mode = if (gaming) Mode.GAME else Mode.NONE,
            clip = clip(raw),
            autoUpdateCheck = flag(raw, UPDATE_AUTO_KEY, true),
        )
    }

    private const val LEGACY_GAME = "juego"

    /** The r1–r9 seed: the per-field fallbacks a v1 store was written against. */
    private fun legacySeed(): List<Triple<String, String, KbSettings>> {
        val base = KbSettings()
        return listOf(
            Triple("noche", "Noche", base),
            Triple("dia", "Día", base.copy(theme = "paper")),
            Triple("juego", "Juego", base.copy(
                theme = "arcade", hideTopRow = true, suggest = false, sound = false,
                cap = KeyCap.FLAT, heightScale = 0.9f, density = Density.TIGHT, popups = false,
                autoCap = false, doubleSpace = false,
            )),
            Triple("escritura", "Escritura", base.copy(
                theme = "slate", font = KeyFont.HUMAN, suggest = true, topRow = TopRow.ACCENTS,
                sound = true, soundPack = SoundPack.THOCK, subLegends = false, heightScale = 1.1f,
            )),
        )
    }

    private fun legacyIcon(id: String) = when (id) { "noche" -> "☾"; "dia" -> "☀"; "juego" -> "◆"; "escritura" -> "✎"; else -> "✦" }

    private fun order(raw: Map<String, *>): List<String>? = (raw["order"] as? String)?.split(',')?.map { it.trim() }
        ?.filter { ID.matches(it) }?.distinct()?.takeIf { it.isNotEmpty() }

    private fun activeOf(raw: Map<String, *>, temas: List<Tema>): String =
        (raw["active"] as? String)?.takeIf { a -> temas.any { it.id == a } } ?: temas.first().id

    private fun name(v: Any?, d: String) = v?.toString()?.trim()?.take(18)?.ifEmpty { null } ?: d
    private fun icon(v: Any?, d: String) = v?.toString()?.take(2)?.ifEmpty { null } ?: d

    private fun flag(raw: Map<String, *>, key: String, d: Boolean) = when (raw[key]?.toString()) { "true" -> true; "false" -> false; else -> d }

    private fun clip(raw: Map<String, *>): ClipSettings {
        val dc = ClipSettings()
        return ClipSettings(history = flag(raw, "clip.history", dc.history), purgeHour = flag(raw, "clip.purge", dc.purgeHour))
    }

    /** Reads every field through [str]; missing or invalid values fall back to [d]'s. */
    private fun read(str: (String) -> String?, d: KbSettings): KbSettings {
        fun bool(name: String, dv: Boolean) = when (str(name)) { "true" -> true; "false" -> false; else -> dv }
        fun float(name: String, dv: Float, lo: Float, hi: Float) =
            str(name)?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(lo, hi) ?: dv
        fun int(name: String, dv: Int, lo: Int, hi: Int) = str(name)?.toIntOrNull()?.coerceIn(lo, hi) ?: dv
        fun themeId(v: String?) = v?.takeIf { t -> Themes.ALL.any { it.id == t } }
        val accentRaw = str("accent")
        // r1 stored "no top row" as topRow = NONE; r2 keeps the row content + a hide flag.
        val rawTop = enumOr(str("topRow"), d.topRow)
        val legacyNone = rawTop == TopRow.NONE
        val topRow = if (legacyNone) d.topRow.takeIf { it != TopRow.NONE } ?: TopRow.ACCENTS else rawTop
        return KbSettings(
            theme = themeId(str("theme")) ?: d.theme,
            accent = if (accentRaw == null) d.accent else ColorMath.parseHex(accentRaw),
            shape = enumOr(str("shape"), d.shape),
            cap = enumOr(str("cap"), d.cap),
            font = enumOr(str("font"), d.font),
            density = enumOr(str("density"), d.density),
            subLegends = bool("subLegends", d.subLegends),
            heightScale = float("heightScale", d.heightScale, HEIGHT_MIN, HEIGHT_MAX),
            sound = bool("sound", d.sound),
            soundPack = enumOr(str("soundPack"), d.soundPack),
            volume = float("volume", d.volume, 0f, 1f),
            haptics = bool("haptics", d.haptics),
            hapticStrength = enumOr(str("hapticStrength"), d.hapticStrength),
            popups = bool("popups", d.popups),
            longPressMs = int("longPressMs", d.longPressMs, 150, 900),
            suggest = bool("suggest", d.suggest),
            spaceCorrects = bool("spaceCorrects", d.spaceCorrects),
            personal = bool("personal", d.personal),
            lang = enumOr(str("lang"), d.lang),
            topRow = topRow,
            autoCap = bool("autoCap", d.autoCap),
            doubleSpace = bool("doubleSpace", d.doubleSpace),
            hideTopRow = legacyNone || bool("hideTopRow", d.hideTopRow),
            emojiKey = bool("emojiKey", d.emojiKey),
            dayNightChip = bool("dayNightChip", d.dayNightChip),
            altTheme = themeId(str("altTheme")) ?: d.altTheme,
            profanityFilter = bool("profanityFilter", d.profanityFilter),
            oneHanded = enumOr(str("oneHanded"), d.oneHanded),
            autoOpeners = bool("autoOpeners", d.autoOpeners),
            bilingual = bool("bilingual", d.bilingual),
            region = enumOr(str("region"), d.region),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E =
        enumValues<E>().firstOrNull { it.name == v } ?: d
}
