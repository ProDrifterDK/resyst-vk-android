package com.resyst.vk.core

enum class KeyShape(val radiusDp: Float) { SQUARE(2f), SOFT(7f), ROUND(14f) }
enum class KeyCap { RAISED, FLAT, OUTLINE }
enum class KeyFont { BRAND, TECH, HUMAN }
enum class Density(val gapDp: Float) { TIGHT(3f), NORMAL(5f), AIRY(8f) }
enum class SoundPack { CLICK, THOCK, TYPE, BUBBLE }
enum class SoundKind { KEY, MOD, SPACE, ENTER }

/** Per-profile settings (desktop "perfil" model, adapted to touch). */
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
    val popups: Boolean = true,
    val longPressMs: Int = 350,
    val suggest: Boolean = true,
    val lang: Lang = Lang.ES,
    val topRow: TopRow = TopRow.ACCENTS,
    val autoCap: Boolean = true,
    val doubleSpace: Boolean = true,
    /** Hide the special-characters row above the letters. Off = row visible (default). */
    val hideTopRow: Boolean = false,
) {
    /** The row actually laid out: [topRow] unless hidden. */
    val effectiveTopRow: TopRow get() = if (hideTopRow) TopRow.NONE else topRow

    /** Letters-layer row count; keyboard height is anchored to it. */
    val baseRowCount: Int get() = if (effectiveTopRow == TopRow.NONE) 4 else 5
}

data class Profile(val id: String, val name: String, val icon: String, val settings: KbSettings)

data class ProfileStore(val profiles: List<Profile>, val active: String) {
    val activeProfile: Profile get() = byId(active) ?: profiles.first()

    fun byId(id: String): Profile? = profiles.firstOrNull { it.id == id }

    fun update(id: String, f: (KbSettings) -> KbSettings): ProfileStore =
        copy(profiles = profiles.map { if (it.id == id) it.copy(settings = f(it.settings)) else it })

    fun withActive(id: String): ProfileStore = if (byId(id) != null) copy(active = id) else this

    fun nextId(): String {
        val i = profiles.indexOfFirst { it.id == active }
        return profiles[(i + 1).mod(profiles.size)].id
    }
}

/**
 * Flat string map ⇄ [ProfileStore] (SharedPreferences friendly). Every value read back is
 * validated; anything unknown or out of range falls back to that profile's seed value.
 */
object ProfileCodec {
    const val HEIGHT_MIN = 0.8f
    const val HEIGHT_MAX = 1.3f

    fun seed(): ProfileStore {
        val base = KbSettings()
        return ProfileStore(
            profiles = listOf(
                Profile("noche", "Noche", "☾", base),
                Profile("dia", "Día", "☀", base.copy(theme = "paper")),
                Profile("juego", "Juego", "◆", base.copy(
                    theme = "arcade", hideTopRow = true, suggest = false, sound = false,
                    cap = KeyCap.FLAT, heightScale = 0.9f, density = Density.TIGHT, popups = false,
                    autoCap = false, doubleSpace = false,
                )),
                Profile("escritura", "Escritura", "✎", base.copy(
                    theme = "slate", font = KeyFont.HUMAN, suggest = true, topRow = TopRow.ACCENTS,
                    sound = true, soundPack = SoundPack.THOCK, subLegends = false, heightScale = 1.1f,
                )),
            ),
            active = "noche",
        )
    }

    fun encode(st: ProfileStore): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["v"] = "1"
        m["active"] = st.active
        m["order"] = st.profiles.joinToString(",") { it.id }
        for (p in st.profiles) {
            val k = "p.${p.id}."
            val s = p.settings
            m[k + "name"] = p.name
            m[k + "icon"] = p.icon
            m[k + "theme"] = s.theme
            m[k + "accent"] = s.accent?.let(ColorMath::toHex) ?: ""
            m[k + "shape"] = s.shape.name
            m[k + "cap"] = s.cap.name
            m[k + "font"] = s.font.name
            m[k + "density"] = s.density.name
            m[k + "subLegends"] = s.subLegends.toString()
            m[k + "heightScale"] = s.heightScale.toString()
            m[k + "sound"] = s.sound.toString()
            m[k + "soundPack"] = s.soundPack.name
            m[k + "volume"] = s.volume.toString()
            m[k + "haptics"] = s.haptics.toString()
            m[k + "popups"] = s.popups.toString()
            m[k + "longPressMs"] = s.longPressMs.toString()
            m[k + "suggest"] = s.suggest.toString()
            m[k + "lang"] = s.lang.name
            m[k + "topRow"] = s.topRow.name
            m[k + "autoCap"] = s.autoCap.toString()
            m[k + "doubleSpace"] = s.doubleSpace.toString()
            m[k + "hideTopRow"] = s.hideTopRow.toString()
        }
        return m
    }

    fun decode(raw: Map<String, *>): ProfileStore {
        val seed = seed()
        val ids = (raw["order"] as? String)?.split(',')?.map { it.trim() }
            ?.filter { Regex("^[a-z0-9_-]{1,24}$").matches(it) }?.distinct()?.takeIf { it.isNotEmpty() }
            ?: seed.profiles.map { it.id }
        val profiles = ids.map { id ->
            val fallback = seed.byId(id) ?: Profile(id, id, "✦", KbSettings())
            val k = "p.$id."
            fun str(name: String): String? = raw[k + name]?.toString()
            fun bool(name: String, d: Boolean) = when (str(name)) { "true" -> true; "false" -> false; else -> d }
            fun float(name: String, d: Float, lo: Float, hi: Float) =
                str(name)?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(lo, hi) ?: d
            fun int(name: String, d: Int, lo: Int, hi: Int) = str(name)?.toIntOrNull()?.coerceIn(lo, hi) ?: d
            val d = fallback.settings
            val theme = str("theme")?.takeIf { t -> Themes.ALL.any { it.id == t } } ?: d.theme
            val accentRaw = str("accent")
            val accent = when {
                accentRaw == null -> d.accent
                else -> ColorMath.parseHex(accentRaw)
            }
            // r1 stored "no top row" as topRow = NONE; r2 keeps the row content + a hide flag.
            val rawTop = enumOr(str("topRow"), d.topRow)
            val legacyNone = rawTop == TopRow.NONE
            val topRow = if (legacyNone) d.topRow.takeIf { it != TopRow.NONE } ?: TopRow.ACCENTS else rawTop
            val hideTopRow = legacyNone || bool("hideTopRow", d.hideTopRow)
            val s = KbSettings(
                theme = theme,
                accent = accent,
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
                popups = bool("popups", d.popups),
                longPressMs = int("longPressMs", d.longPressMs, 150, 900),
                suggest = bool("suggest", d.suggest),
                lang = enumOr(str("lang"), d.lang),
                topRow = topRow,
                autoCap = bool("autoCap", d.autoCap),
                doubleSpace = bool("doubleSpace", d.doubleSpace),
                hideTopRow = hideTopRow,
            )
            Profile(
                id = id,
                name = str("name")?.trim()?.take(18)?.ifEmpty { null } ?: fallback.name,
                icon = str("icon")?.take(2)?.ifEmpty { null } ?: fallback.icon,
                settings = s,
            )
        }
        val active = (raw["active"] as? String)?.takeIf { a -> profiles.any { it.id == a } } ?: profiles.first().id
        return ProfileStore(profiles, active)
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String?, d: E): E =
        enumValues<E>().firstOrNull { it.name == v } ?: d
}
