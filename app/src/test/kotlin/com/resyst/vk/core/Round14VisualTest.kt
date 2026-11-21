package com.resyst.vk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * r14 (docs/failure-modes.md, round 14): LG legibility of everything a person can build, FS following the system's
 * dark mode, the 0.13.0 → 0.14.0 upgrade, MO motion, HS haptics, PS personalisation. Written before the r14 code.
 */
class Round14VisualTest {

    // ── LG: the legibility gate ─────────────────────────────────────────
    /** A colour from hue (0-360), saturation and lightness (0-1): the sweep a "Más colores" picker can reach. */
    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f); h < 120 -> Triple(x, c, 0f); h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c); h < 300 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return ColorMath.rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private val sweep: List<Int?> = listOf<Int?>(null) + Themes.ACCENTS.map { it.first } +
        (0 until 36).flatMap { i -> listOf(0.25f, 0.5f, 0.8f).map { l -> hsl(i * 10f, 0.85f, l) } } +
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF777777.toInt())

    @Test fun lg1EveryPaletteAccentTintCapAndContrastModeIsLegible() {
        var checked = 0
        val fails = ArrayList<String>()
        for (t in Themes.CATALOG) for (acc in sweep) for (tint in Tint.values()) for (hc in listOf(false, true)) {
            val p = Palette.of(t.id, acc, tint, hc)
            for (cap in KeyCap.values()) {
                for (f in Legibility.failures(p, cap)) if (fails.size < 20) fails += "${t.id} ${acc?.let(ColorMath::toHex)} $tint hc=$hc $cap: $f"
                checked++
            }
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
        assertTrue(checked > 10_000)
    }

    @Test fun lg1TheAudited0130FailuresAreFixed() {
        // Papiro: chip text 3.52:1, pressed chip 2.83:1, first suggestion on the bar 4.05:1, accent row 4.23:1;
        // Sepia: chip text 3.92:1, pressed 3.06:1 (0.13.0, scratch audit on the Pixel 6 renders)
        for (id in listOf("paper", "sepia")) {
            val p = Palette.of(id, null)
            val t = p.theme
            assertTrue("$id chip", ColorMath.contrast(p.accent, p.chipFill) >= 4.5)
            assertTrue("$id pressed chip", ColorMath.contrast(p.chipPressedInk, p.chipPressedFill) >= 4.5)
            assertTrue("$id bar", ColorMath.contrast(p.accent, t.bg) >= 4.5)
            assertTrue("$id accent row", ColorMath.contrast(p.accent, t.keyNum) >= 4.5)
        }
        // a dark palette whose accent already passed keeps it exactly (no drift on the owner's Midnight Laboratory)
        assertEquals(Themes.byId("lab").accent, Palette.of("lab", null).accent)
        assertEquals(Themes.byId("arcade").accent, Palette.of("arcade", null).accent)
    }

    @Test fun lg1InkReachesAaOnEveryAccent() {
        for (t in Themes.CATALOG) for (acc in sweep) {
            val p = Palette.of(t.id, acc)
            assertTrue("${t.id} ${acc?.let(ColorMath::toHex)}", ColorMath.contrast(p.accentInk, p.accent) >= 4.5)
        }
    }

    @Test fun lg2TintKeepsTheHueAndTheGateFixesOnlyText() {
        val plain = Palette.of("lab", ColorMath.parseHex("#5b9cf0"), Tint.NONE)
        val strong = Palette.of("lab", ColorMath.parseHex("#5b9cf0"), Tint.STRONG)
        assertNotEquals("the keys took the accent's hue", plain.theme.key, strong.theme.key)
        assertTrue("blue went into the key", ColorMath.b(strong.theme.key) > ColorMath.b(plain.theme.key))
        assertTrue(ColorMath.contrast(strong.theme.text, strong.theme.key) >= 7.0)
        assertEquals(Tint.NONE, KbSettings().tint)
    }

    @Test fun lg3SystemColoursGoThroughTheGateAndHaveAFallback() {
        assertTrue(Themes.byId("you").dark); assertFalse(Themes.byId("youlight").dark)
        assertEquals("youlight", Themes.twin("you")); assertEquals("you", Themes.twin("youlight"))
        // garbage tones (all mid grey) still give a legible keyboard
        val grey = SystemColors.uniform(0xFF777777.toInt())
        for (id in listOf("you", "youlight")) {
            val p = Palette.of(id, null, system = grey)
            for (cap in KeyCap.values()) assertTrue("$id $cap ${Legibility.failures(p, cap)}", Legibility.failures(p, cap).isEmpty())
        }
        // a real-looking Material You set: the accent follows the wallpaper
        val sys = SystemColors.sample()
        assertEquals(sys.accent1(200), Palette.of("you", null, system = sys).theme.accent)
        // no system colours: the fixed stand-in, not a crash
        assertEquals(Themes.byId("you").bg, Palette.of("you", null, system = null).theme.bg)
    }

    @Test fun lg4LegendsFollowTheFontScaleButStayInTheKey() {
        val dp = 2.625f
        val rowH = 54 * dp
        // 0.13.0 sizes at scale 1, font scale ≤ 1 (the test phone runs 0.85: legends do not shrink below 0.13.0)
        assertEquals(minOf(rowH * 0.42f, 23 * dp), Legend.letter(rowH, 37 * dp, rowH, small = false, scale = 1f, fontScale = 1f, dp = dp), 0.01f)
        assertEquals(Legend.letter(rowH, 37 * dp, rowH, false, 1f, 1f, dp), Legend.letter(rowH, 37 * dp, rowH, false, 1f, 0.85f, dp), 0.01f)
        assertTrue(Legend.letter(rowH, 37 * dp, rowH, false, 1f, 1.3f, dp) > Legend.letter(rowH, 37 * dp, rowH, false, 1f, 1f, dp))
        // the smallest key a user can get (landscape 40 dp rows × 0.8, tight gaps) at font 2.0 × size 1.3 still fits
        val small = 40 * dp * 0.8f
        for (fs in listOf(1f, 1.3f, 2f)) for (sc in listOf(0.85f, 1f, 1.3f)) {
            val px = Legend.letter(small, 30 * dp, small - 4 * dp, false, sc, fs, dp)
            assertTrue("$fs $sc $px", px <= (small - 4 * dp) * Legend.MAX_HEIGHT + 0.01f)
            assertTrue(px > 6 * dp)
        }
        assertTrue(Legend.label(rowH, 60 * dp, "?123", 1f, 2f, dp) { s, size -> s.length * size * 0.6f } <= 60 * dp - 8 * dp + 0.01f)
    }

    @Test fun lg5HighContrastOutlinesAndMaximisesLegends() {
        for (t in Themes.CATALOG) {
            val p = Palette.of(t.id, null, highContrast = true)
            assertTrue(p.highContrast)
            assertTrue("${t.id} outline/bg", ColorMath.contrast(p.outline, p.theme.bg) >= 3.0)
            assertTrue("${t.id} outline/key", ColorMath.contrast(p.outline, p.theme.key) >= 3.0)
            val best = maxOf(ColorMath.contrast(0xFFFFFFFF.toInt(), p.theme.key), ColorMath.contrast(0xFF000000.toInt(), p.theme.key))
            assertEquals("${t.id} legend at the maximum", best, ColorMath.contrast(p.theme.text, p.theme.key), 0.01)
        }
    }

    @Test fun newPalettesMeetTheClassicBarWithoutTheGate() {
        assertEquals(listOf("lab", "slate", "arcade", "paper", "hc", "sepia", "amoled", "eink"), Themes.ALL.map { it.id })
        assertEquals(Themes.ALL + Themes.MORE, Themes.CATALOG)
        assertEquals(Themes.CATALOG.size, Themes.CATALOG.map { it.id }.toSet().size)
        for (t in Themes.MORE) {
            assertTrue("${t.id} text/key", ColorMath.contrast(t.text, t.key) >= 7.0)
            assertTrue("${t.id} textMod/keyMod", ColorMath.contrast(t.textMod, t.keyMod) >= 4.5)
            assertTrue("${t.id} muted/bg", ColorMath.contrast(t.muted, t.bg) >= 4.5)
            assertNotEquals("${t.id} twin", t.dark, Themes.byId(Themes.twin(t.id)).dark)
            assertTrue(t.id, listOf(TestTxt.ES, TestTxt.EN).all { Names.palette(it, t.id) != "palette_" + t.id })
        }
        assertTrue(Themes.MORE.count { it.dark } >= 4 && Themes.MORE.count { !it.dark } >= 4)
    }

    // ── FS: follow the system's dark mode; the upgrade ──────────────────
    /** A store exactly as 0.13.0's SettingsRepo wrote it: v2 keys, three temas, user edits, no r14 key. */
    private fun store0130(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = "2"; m["active"] = "pizarra"; m["order"] = "resyst,arcade,pizarra"; m["mode"] = "none"
        m["clip.history"] = "true"; m["clip.purge"] = "true"; m["update.auto"] = "false"
        val looks = mapOf(
            "resyst" to Triple("Resyst", "✦", KbSettings(theme = "paper", accent = ColorMath.parseHex("#e06c9f"), shape = KeyShape.ROUND, altTheme = "lab")),
            "arcade" to Triple("Arcade", "◆", KbSettings(theme = "arcade", cap = KeyCap.FLAT, density = Density.TIGHT, heightScale = 0.9f)),
            "pizarra" to Triple("Mi pizarra", "✎", KbSettings(theme = "slate", font = KeyFont.HUMAN, subLegends = false, heightScale = 1.15f, accent = ColorMath.parseHex("#4fc58f"))),
        )
        val r13Look = setOf("theme", "accent", "shape", "cap", "font", "density", "subLegends", "heightScale", "altTheme")
        for ((id, v) in looks) {
            m["t.$id.name"] = v.first; m["t.$id.icon"] = v.second
            for ((k, x) in ProfileCodec.fields(v.third)) if (k in r13Look) m["t.$id.$k"] = x
        }
        val phone = KbSettings(sound = true, soundPack = SoundPack.THOCK, haptics = true, hapticStrength = HapticStrength.HIGH, longPressMs = 300, lang = Lang.ES, spaceFlick = true)
        val r13Phone = listOf("sound", "soundPack", "volume", "haptics", "hapticStrength", "popups", "longPressMs", "suggest", "spaceCorrects", "personal", "lang",
            "topRow", "autoCap", "doubleSpace", "hideTopRow", "emojiKey", "dayNightChip", "profanityFilter", "oneHanded", "autoOpeners", "contextOffers", "pairMemory",
            "bilingual", "spaceFlick", "region")
        for ((k, x) in ProfileCodec.fields(phone)) if (k in r13Phone) m["phone.$k"] = x
        return m
    }

    @Test fun fs1TheUpgradeKeepsEveryTemaAccentAndSettingAndDoesNotFollow() {
        val st = ProfileCodec.decode(store0130())
        assertFalse("an upgraded keyboard never changes colour on its own", st.followSystem)
        assertEquals(listOf("resyst", "arcade", "pizarra"), st.temas.map { it.id })
        assertEquals("pizarra", st.active)
        assertEquals("Mi pizarra", st.activeTema.name)
        val r = st.byId("resyst")!!.look
        assertEquals("paper", r.theme); assertEquals(ColorMath.parseHex("#e06c9f"), r.accent); assertEquals(KeyShape.ROUND, r.shape); assertEquals("lab", r.altTheme)
        val p = st.settings
        assertEquals("slate", p.theme); assertEquals(KeyFont.HUMAN, p.font); assertEquals(1.15f, p.heightScale, 0f); assertFalse(p.subLegends)
        assertEquals(ColorMath.parseHex("#4fc58f"), p.accent)
        assertTrue(p.sound); assertEquals(SoundPack.THOCK, p.soundPack); assertEquals(HapticStrength.HIGH, p.hapticStrength); assertEquals(300, p.longPressMs); assertTrue(p.spaceFlick)
        // every r14 option lands on its default
        assertEquals(KbSettings().let { listOf(it.tint, it.legendWeight, it.legendScale, it.upperLegends, it.bubble, it.barStyle, it.motion, it.hapticProfile) },
            listOf(p.tint, p.legendWeight, p.legendScale, p.upperLegends, p.bubble, p.barStyle, p.motion, p.hapticProfile))
        // and the first 0.14.0 save round-trips it unchanged
        assertEquals(st, ProfileCodec.decode(ProfileCodec.encode(st)))
        // a 0.13.0 accent that was readable is drawn exactly as before on its dark tema
        assertEquals(ColorMath.parseHex("#4fc58f"), Palette.of(p.theme, p.accent).accent)
    }

    @Test fun fs1AFreshInstallFollowsTheSystem() {
        assertTrue(ProfileCodec.seed().followSystem)
        assertTrue(ProfileCodec.decode(emptyMap<String, Any?>()).followSystem)
        val off = ProfileCodec.seed().copy(followSystem = false)
        assertFalse(ProfileCodec.decode(ProfileCodec.encode(off)).followSystem)
        assertEquals(Scope.DEVICE, Ctl.FOLLOW_SYSTEM.scope)
        assertEquals(ProfileCodec.FOLLOW_KEY, Ctl.FOLLOW_SYSTEM.key)
    }

    @Test fun fs2TheEffectivePaletteMatchesTheSystem() {
        for (t in Themes.CATALOG) for (sysDark in listOf(false, true)) {
            val st = ProfileCodec.seed().updateLook("resyst") { it.copy(theme = t.id) }
            val eff = st.settingsFor(sysDark)
            assertEquals("${t.id} sys=$sysDark", sysDark, Themes.byId(eff.theme).dark)
            if (t.dark == sysDark) assertEquals(t.id, eff.theme)
            // not following: the stored look, whatever the system says
            assertEquals(t.id, st.copy(followSystem = false).settingsFor(sysDark).theme)
            // unknown system state (tests, previews): the stored look
            assertEquals(t.id, st.settingsFor(null).theme)
        }
        // the remembered partner wins over the default twin
        val st = ProfileCodec.seed().updateLook("resyst") { it.copy(theme = "slate", altTheme = "eink") }
        assertEquals("eink", st.settingsFor(false).theme)
    }

    @Test fun fs2AManualFlipStopsFollowingAndShowsTheOpposite() {
        val st = ProfileCodec.seed() // follows; the resyst tema is lab (dark)
        val day = st.settingsFor(false).theme
        assertFalse(Themes.byId(day).dark)
        val flipped = Quick.apply(st, QuickAction.DAY_NIGHT, systemDark = false)
        assertFalse(flipped.followSystem)
        assertTrue("the opposite of what was on screen", Themes.byId(flipped.settingsFor(false).theme).dark)
        // the tile says it follows
        val tile = Quick.tiles(TestTxt.ES, st, FieldPolicy(FieldKind.TEXT, suggestions = true, incognito = false), systemDark = true).first { it.action == QuickAction.DAY_NIGHT }
        assertTrue(tile.state, tile.state.startsWith("Auto"))
        // without a system answer the 0.13.0 behaviour (tests, previews)
        assertEquals(Quick.apply(st.copy(followSystem = false), QuickAction.DAY_NIGHT).temas, Quick.apply(st, QuickAction.DAY_NIGHT).temas)
    }

    // ── MO: motion ──────────────────────────────────────────────────────
    @Test fun mo2ReducedMotionWins() {
        assertEquals(Motion.FULL, KbSettings().motion)
        for (m in Motion.values()) assertEquals(Motion.OFF, Motion.effective(m, 0f))
        assertEquals(Motion.SUBTLE, Motion.effective(Motion.SUBTLE, 1f))
        assertEquals(0L, Motion.OFF.ms(160))
        assertTrue(Motion.SUBTLE.ms(160) in 1 until Motion.FULL.ms(160))
        assertTrue(Motion.FULL.ms(160) <= 180)
        assertEquals(320L, Motion.FULL.ms(160, animatorScale = 2f).coerceAtMost(320L))
    }

    // ── HS: haptics follow the sound kinds ──────────────────────────────
    @Test fun hs1KeysFeelLikeTheySoundAndUniformIsThe0130Feel() {
        assertEquals(HapticProfile.BY_KEY, KbSettings().hapticProfile)
        for (f in KeyFeel.values()) assertEquals("uniform = 0.13.0", HapticPulse.CLICK, Haptics.pulseForKey(f, true, HapticProfile.UNIFORM))
        for (f in KeyFeel.values()) assertNull(Haptics.pulseForKey(f, false, HapticProfile.BY_KEY))
        assertEquals(HapticPulse.CLICK, Haptics.pulseForKey(KeyFeel.LETTER, true, HapticProfile.BY_KEY))
        assertEquals(HapticPulse.TICK, Haptics.pulseForKey(KeyFeel.MOD, true, HapticProfile.BY_KEY))
        assertEquals(HapticPulse.SOFT_CLICK, Haptics.pulseForKey(KeyFeel.SPACE, true, HapticProfile.BY_KEY))
        assertEquals(HapticPulse.FIRM_CLICK, Haptics.pulseForKey(KeyFeel.ENTER, true, HapticProfile.BY_KEY))
        assertEquals(HapticPulse.TICK, Haptics.pulseForKey(KeyFeel.REPEAT, true, HapticProfile.BY_KEY))
        for (st in HapticStrength.values()) {
            val amp = listOf(HapticPulse.TICK, HapticPulse.SOFT_CLICK, HapticPulse.CLICK, HapticPulse.FIRM_CLICK, HapticPulse.HEAVY_CLICK).map { Haptics.spec(it, st).amplitude }
            assertEquals("$st: tick < soft < click < firm < heavy (long press stays the heaviest)", amp.sorted(), amp)
            assertEquals(amp.size, amp.toSet().size)
        }
        // the key feel of each key matches its sound kind
        assertEquals(KeyFeel.SPACE, KeyFeel.of(KeyType.SPACE, KeyStyle.NORMAL)); assertEquals(KeyFeel.ENTER, KeyFeel.of(KeyType.ENTER, KeyStyle.ACTION))
        assertEquals(KeyFeel.MOD, KeyFeel.of(KeyType.SHIFT, KeyStyle.MOD)); assertEquals(KeyFeel.LETTER, KeyFeel.of(KeyType.CHAR, KeyStyle.NORMAL))
    }

    // ── PS: personalisation ─────────────────────────────────────────────
    @Test fun ps1EveryNewLookOptionIsATemaFieldAndRoundTrips() {
        for (f in listOf("tint", "legendWeight", "legendScale", "upperLegends", "bubble", "barStyle")) assertTrue(f, f in Look.FIELDS)
        val st = ProfileCodec.seed().edit {
            it.copy(tint = Tint.SOFT, legendWeight = LegendWeight.BOLD, legendScale = 1.2f, upperLegends = true, bubble = Bubble.FLOATING, barStyle = BarStyle.CHIPS,
                shape = KeyShape.PILL, cap = KeyCap.KEYCAP, motion = Motion.SUBTLE, hapticProfile = HapticProfile.UNIFORM)
        }
        val back = ProfileCodec.decode(ProfileCodec.encode(st))
        assertEquals(st, back)
        assertEquals("look stays in its tema", Look(), Look.of(back.phone))
        assertEquals(KeyCap.RAISED, back.withTema("arcade").settings.cap.let { if (it == KeyCap.FLAT) KeyCap.RAISED else it })
        assertEquals(Motion.SUBTLE, back.withTema("arcade").settings.motion) // phone-wide
        // junk falls back per field
        val junk = ProfileCodec.encode(st).toMutableMap<String, Any?>().apply {
            put("t.resyst.legendScale", "9"); put("t.resyst.tint", "NEON"); put("phone.motion", "WARP")
        }
        val j = ProfileCodec.decode(junk).settings
        assertEquals(Legend.SCALE_MAX, j.legendScale, 0f); assertEquals(Tint.NONE, j.tint); assertEquals(Motion.FULL, j.motion)
    }

    @Test fun ps2AStyleSetsOnlyLookFieldsAndUndoes() {
        val st = ProfileCodec.seed().updatePhone { it.copy(sound = true, lang = Lang.EN) }
        assertTrue(Styles.ALL.size >= 6)
        assertEquals(Styles.ALL.size, Styles.ALL.map { it.id }.toSet().size)
        for (s in Styles.ALL) {
            val (next, undo) = Styles.apply(st, s)
            assertEquals("${s.id}: behaviour untouched", st.phone, next.phone)
            assertEquals("${s.id}: other temas untouched", st.temas.drop(1), next.temas.drop(1))
            assertEquals("${s.id}: undo", st, Styles.undo(next, undo))
            for (cap in KeyCap.values()) assertTrue(Legibility.failures(Palette.of(next.settings.theme, next.settings.accent, next.settings.tint), cap).isEmpty())
        }
    }

    @Test fun ps3TemaManagementKeepsTheStoreValid() {
        var st = ProfileCodec.seed()
        st = TemaOps.create(st, "  Mi tema nocturno larguísimo  ", "☾")
        val made = st.activeTema
        assertEquals("Mi tema nocturno l", made.name) // trimmed to 18
        assertTrue(Regex("^[a-z0-9_-]{1,24}$").matches(made.id))
        assertEquals(ProfileCodec.seed().activeTema.look, made.look) // a copy of the tema it came from
        val twice = TemaOps.create(st, "Mi tema nocturno larguísimo", "☾")
        assertNotEquals(made.id, twice.activeTema.id) // unique ids
        st = TemaOps.rename(st, made.id, "Noche", "✦")
        assertEquals("Noche", st.byId(made.id)!!.name)
        assertEquals(st, ProfileCodec.decode(ProfileCodec.encode(st)))
        val del = TemaOps.delete(st, made.id)
        assertNull(del.byId(made.id))
        assertNotEquals(made.id, del.active) // the next one took over
        var one = ProfileCodec.seed()
        for (t in one.temas.drop(1)) one = TemaOps.delete(one, t.id)
        assertEquals(1, one.temas.size)
        assertEquals(one, TemaOps.delete(one, one.temas.first().id)) // never the last
        assertEquals("blank names are refused", st, TemaOps.rename(st, made.id, "   ", "✦"))
        var many = ProfileCodec.seed()
        repeat(20) { many = TemaOps.create(many, "t$it", "✦") }
        assertEquals(TemaOps.MAX_TEMAS, many.temas.size)
    }
}
