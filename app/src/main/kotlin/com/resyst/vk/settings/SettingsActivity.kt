package com.resyst.vk.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.resyst.vk.R
import com.resyst.vk.core.ClipSettings
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.ClipboardHistory
import com.resyst.vk.core.ConnectionLog
import com.resyst.vk.core.EmojiRecents
import com.resyst.vk.core.EmojiTones
import com.resyst.vk.core.PersonalSummary
import com.resyst.vk.core.FieldKind
import com.resyst.vk.core.GifCopy
import com.resyst.vk.core.Ctl
import com.resyst.vk.core.DayNight
import com.resyst.vk.core.Density
import com.resyst.vk.core.HapticEvent
import com.resyst.vk.core.HapticStrength
import com.resyst.vk.core.Haptics
import com.resyst.vk.core.KbSettings
import com.resyst.vk.core.KeyCap
import com.resyst.vk.core.KeyFont
import com.resyst.vk.core.KeyShape
import com.resyst.vk.core.KeyboardLayouts
import com.resyst.vk.core.Look
import com.resyst.vk.core.Lang
import com.resyst.vk.core.Region
import com.resyst.vk.core.Layer
import com.resyst.vk.core.LayoutSpec
import com.resyst.vk.core.Mode
import com.resyst.vk.core.OneHand
import com.resyst.vk.core.Palette
import com.resyst.vk.core.ProfileCodec
import com.resyst.vk.core.ProfileStore
import com.resyst.vk.core.Scope
import com.resyst.vk.core.SettingsIA
import com.resyst.vk.core.SettingsPage
import com.resyst.vk.core.ShiftState
import com.resyst.vk.core.SoundPack
import com.resyst.vk.core.Themes
import com.resyst.vk.core.TopRow
import com.resyst.vk.core.UpdateDecision
import com.resyst.vk.core.UpdateNotice
import com.resyst.vk.ime.ClipStore
import com.resyst.vk.ime.EmojiAsset
import com.resyst.vk.ime.Fonts
import com.resyst.vk.ime.HapticPlayer
import com.resyst.vk.ime.KeyboardView
import com.resyst.vk.ime.PersonalStore
import com.resyst.vk.ime.ResystImeService
import com.resyst.vk.ime.SubtypeSync
import com.resyst.vk.ime.SystemLook
import kotlin.math.roundToInt

/**
 * Setup + settings screen (r10): a home and the pages of [SettingsIA], rendered from that data.
 * Every control is one [Ctl] → one widget tagged with `ctl.name` (the E2E walks the tree by it).
 * Built in code (no XML) so every color comes from the palette the keyboard uses: the screen
 * re-skins itself with the active tema.
 */
class SettingsActivity : Activity() {

    private lateinit var repo: SettingsRepo
    private lateinit var store: ProfileStore
    /** null = home; otherwise a [SettingsPage.id]. */
    private var page: String? = null
    private lateinit var root: LinearLayout
    /** Where the widget helpers add their views: [root], or a control's own box while it renders. */
    private lateinit var into: LinearLayout
    private lateinit var scroll: ScrollView
    private var preview: KeyboardView? = null
    private var haptics: HapticPlayer? = null

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepo(this)
        store = repo.load()
        PersonalStore.init(this)
        ClipStore.init(this)
        scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18f), px(22f), px(18f), px(28f))
        }
        into = root
        scroll.addView(root)
        setContentView(scroll)
        fitSystemBars()
        page = savedInstanceState?.getString(STATE_PAGE)?.takeIf { SettingsIA.page(it) != null }
        route(intent)
        render()
        syncBack()
        if (revealUpdate) revealUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
        render()
        syncBack()
        if (revealUpdate) revealUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page)
    }

    private var revealUpdate = false

    /** The keyboard's update chip (r9) lands on "Acerca de"; other surfaces may name a page. */
    private fun route(i: Intent?) {
        revealUpdate = false
        if (i?.getStringExtra(EXTRA_SECTION) == SECTION_UPDATE) {
            page = SettingsIA.pageOf(Ctl.UPDATES)?.id
            revealUpdate = true
        } else {
            i?.getStringExtra(EXTRA_PAGE)?.takeIf { SettingsIA.page(it) != null }?.let { page = it }
        }
    }

    /** The "Actualización" block, set by [render] when the page shows it. */
    private var updateAnchor: View? = null

    private fun revealUpdates() {
        revealUpdate = false
        scroll.post {
            val v = updateAnchor ?: return@post
            scroll.smoothScrollTo(0, (v.top - px(8f)).coerceAtLeast(0))
        }
    }

    // ── navigation ──────────────────────────────────────────────────────
    private fun go(id: String?) {
        undoStyle = null
        page = id?.takeIf { SettingsIA.page(it) != null }
        render()
        scroll.scrollTo(0, 0)
        syncBack()
    }

    private var backCallback: Any? = null
    private var backRegistered = false

    /** API 33+ (predictive back, default on for targetSdk 36): a page goes back to home. */
    private fun syncBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = (backCallback as? OnBackInvokedCallback) ?: OnBackInvokedCallback { go(null) }.also { backCallback = it }
        val want = page != null
        if (want && !backRegistered) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        if (!want && backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        backRegistered = want
    }

    @Deprecated("API < 33 path; 33+ uses syncBack()")
    override fun onBackPressed() {
        if (page != null) go(null) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * targetSdk 35+ forces edge-to-edge: the window draws under the status bar, the navigation
     * bar and the keyboard (adjustResize no longer shrinks it). Pad the activity root by those
     * insets; clipToPadding keeps scrolled content out of the status bar. API 30–34 opt in to
     * the same model so every version behaves alike; API 26–29 keep the legacy fitted window.
     */
    private fun fitSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return
        window.setDecorFitsSystemWindows(false)
        scroll.clipToPadding = true
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            val imeGrew = ime.bottom > 0 && bottom > v.paddingBottom
            v.setPadding(bars.left, bars.top, bars.right, bottom)
            if (imeGrew) v.post { revealFocused() }
            WindowInsets.CONSUMED
        }
    }

    /** The keyboard no longer resizes the window: scroll the focused field above it ourselves. */
    private fun revealFocused() {
        val f = currentFocus ?: return
        val r = Rect()
        f.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(f, r)
        val visibleBottom = scroll.height - scroll.paddingBottom - px(12f)
        val need = r.bottom - scroll.scrollY - visibleBottom
        if (need > 0) scroll.smoothScrollBy(0, need)
    }

    override fun onResume() {
        super.onResume()
        store = repo.load()
        Updater.listeners += updaterListener
        Updater.resume(this)
        ClipStore.listeners += clipListener
        PersonalStore.wiped += personalListener
        PersonalStore.pairsWiped += personalListener
        ConnectionBook.listeners += bookListener
        render()
        maybeAutoInstall()
    }

    private val updaterListener: () -> Unit = {
        rerender()
        maybeAutoInstall()
    }

    private val clipListener: () -> Unit = { rerender() }

    /** r11a-fix (F2): the wipe finished on disk: re-count what files/personal/ still holds. */
    private val personalListener: () -> Unit = { rerender() }

    /** r11b: a GIF request logged while the book is on screen re-renders it. */
    private val bookListener: () -> Unit = { if (page == SettingsIA.pageOf(Ctl.CONNECTIONS)?.id) rerender() }

    override fun onPause() {
        ConnectionBook.listeners -= bookListener
        ClipStore.listeners -= clipListener
        PersonalStore.wiped -= personalListener
        PersonalStore.pairsWiped -= personalListener
        Updater.listeners -= updaterListener
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            render() // IME picker closed → refresh the setup status
            maybeAutoInstall() // the download finished while a dialog/notification shade had focus
        }
    }

    /** r14: the palette the screen wears, computed once per render (the keyboard's: resolved, tinted, gated). */
    private lateinit var palNow: Palette
    private val pal get() = palNow
    private val t get() = pal.theme
    /** r14 (PS2): the style just applied and the look it replaced («Deshacer»); cleared when the page changes. */
    private var undoStyle: Pair<String, Look>? = null
    /** r14: «Más colores» open under the accent swatches. */
    private var moreAccents = false

    /** What the user edits: phone behavior + active tema look, never the mode-overridden view. */
    private val b get() = store.base

    /** Edits through [ProfileStore.edit]: look fields land in the active tema, the rest phone-wide. */
    private fun commit(f: (KbSettings) -> KbSettings) = commitStore(store.edit(f))

    private fun commitStore(next: ProfileStore) {
        store = next
        repo.save(store)
        rerender()
    }

    /** Re-render in place (same page, same scroll position). */
    private fun rerender() {
        val y = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ── rendering ───────────────────────────────────────────────────────
    private fun render() {
        palNow = SystemLook.palette(this, shownSettings())
        val p = pal
        window.statusBarColor = p.theme.bg
        window.navigationBarColor = p.theme.bg
        scroll.setBackgroundColor(p.theme.bg) // also paints behind the transparent system bars
        if (Build.VERSION.SDK_INT >= 30) {
            // light themes (Papiro) need dark status/nav icons
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.theme.dark) 0 else light, light)
        }
        root.removeAllViews()
        into = root
        updateAnchor = null
        val pg = SettingsIA.page(page)
        if (pg == null) {
            page = null
            renderHome()
        } else {
            renderPage(pg)
        }
    }

    /** Home: setup, tema + mode, live preview, the daily essentials, then one row per page. */
    private fun renderHome() {
        header()
        setupCard()
        // r14: the keyboard first — its live look, Personalizar and Día / noche one tap away
        heroCard()
        section("Tema y modo")
        ctl(Ctl.TEMA)
        ctl(Ctl.MODE)
        section(getString(R.string.group_styles))
        ctl(Ctl.STYLES)
        section("Ajustes rápidos")
        for (c in SettingsIA.HOME) if (c != Ctl.TEMA && c != Ctl.MODE && c != Ctl.DAY_NIGHT) ctl(c)
        section("Todos los ajustes")
        for (pg in SettingsIA.PAGES) navRow(pg)
        section("Probar")
        tryField()
        footer()
    }

    private fun renderPage(pg: SettingsPage) {
        pageHeader(pg)
        if (store.mode != Mode.NONE && pg.id in MODE_AFFECTED) modeBanner()
        if (pg.preview) livePreview()
        if (pg.id == PAGE_KNOW) { knowIntro(); knowDashboard() }
        val hinted = HashSet<Ctl>()
        for (row in SettingsIA.rows(pg) { on(it) }) {
            if (row.ctl in pg.groups) groupTitle(row.ctl)?.let { into = root; groupHeader(it) }
            val parent = row.ctl.dependsOn
            val hint = if (!row.enabled && parent != null && hinted.add(parent)) parent else null
            ctl(row.ctl, row.enabled, row.indent, hint)
        }
        root.addView(label("", 1f, t.bg).apply { typeface = Typeface.DEFAULT }, lp(bottom = 40f))
    }

    /** Value of a switch control, for [SettingsIA.rows] (dependents follow their switch). */
    private fun on(c: Ctl): Boolean = when (c) {
        Ctl.HIDE_TOP_ROW -> b.hideTopRow
        Ctl.SUGGEST -> b.suggest
        Ctl.HAPTICS -> b.haptics
        Ctl.SOUND -> b.sound
        Ctl.CLIP_HISTORY -> store.clip.history
        else -> true
    }

    /**
     * Renders one control into its own box tagged `ctl.name`. A disabled dependent stays
     * visible (the user sees what its switch unlocks), dimmed and not actionable; [hint] adds
     * one "Activa «X» para…" line under the first one of a group.
     */
    private fun ctl(ctl: Ctl, enabled: Boolean = true, indent: Int = 0, hint: Ctl? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = ctl.name }
        val prev = into
        into = box
        try {
            widget(ctl)
        } finally {
            into = prev
        }
        if (box.childCount == 0) return
        if (!enabled) {
            box.alpha = 0.42f
            disable(box, ctl)
        } else if (overridden(ctl)) {
            // The mode wins while it is on: say so on the control itself, keep it editable
            // (the stored value is what comes back when the mode is turned off).
            box.alpha = 0.6f
            box.addView(label("En pausa por el modo ${store.mode.label} · vuelve al apagarlo", 12f, pal.accent, 550).apply {
                tag = "paused.${ctl.name}"
            }, lp(bottom = 6f))
            box.contentDescription = "${ctl.label}: en pausa por el modo ${store.mode.label}"
        }
        if (indent > 0) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(View(this).apply { setBackgroundColor(t.edgeHi) }, LinearLayout.LayoutParams(px(2f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = px(6f + 14f * (indent - 1)); marginEnd = px(12f)
            })
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            into.addView(row, lp())
        } else {
            into.addView(box, lp())
        }
        if (hint != null) {
            val what = if (hint == Ctl.HIDE_TOP_ROW) "Desactiva «${hint.label}»" else "Activa «${hint.label}»"
            into.addView(label("$what para cambiar esto.", 12f, t.muted).apply { tag = "hint.${ctl.name}" }, lp(bottom = 4f).apply { marginStart = px(20f) })
        }
    }

    /** True when the active mode replaces this control's stored value ([Mode.apply] changes its field). */
    private fun overridden(ctl: Ctl): Boolean {
        if (store.mode == Mode.NONE) return false
        val field = ctl.key?.removePrefix(ProfileCodec.PHONE) ?: return false
        val stored = ProfileCodec.fields(store.base)[field] ?: return false
        return stored != ProfileCodec.fields(store.settings)[field]
    }

    private fun disable(v: View, ctl: Ctl) {
        v.isEnabled = false
        if (v.isClickable) {
            v.isClickable = false
            v.contentDescription = (v.contentDescription ?: ctl.label).toString() + ", no disponible"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) disable(v.getChildAt(i), ctl)
    }

    /** One widget per control. Exhaustive: a new [Ctl] without a widget does not compile. */
    private fun widget(ctl: Ctl) {
        val s = b
        when (ctl) {
            Ctl.TEMA -> temaPicker()
            Ctl.MODE -> modePicker()
            Ctl.FOLLOW_SYSTEM -> toggle(getString(R.string.follow_system_title), getString(R.string.follow_system_summary), store.followSystem) { v -> commitStore(store.copy(followSystem = v)) }
            Ctl.TEMA_MANAGE -> temaManage()
            Ctl.STYLES -> stylesGallery()
            Ctl.TINT -> {
                choice(getString(R.string.tint_title), listOf(com.resyst.vk.core.Tint.NONE to getString(R.string.tint_none), com.resyst.vk.core.Tint.SOFT to getString(R.string.tint_soft),
                    com.resyst.vk.core.Tint.STRONG to getString(R.string.tint_strong)), s.tint) { v -> commit { it.copy(tint = v) } }
                into.addView(label(getString(R.string.tint_summary), 12f, t.muted), lp(bottom = 4f))
            }
            Ctl.LEGEND_WEIGHT -> keyTiles(getString(R.string.legend_weight_title), listOf(
                Triple(com.resyst.vk.core.LegendWeight.LIGHT, getString(R.string.weight_light), KeyTile(weight = com.resyst.vk.core.LegendWeight.LIGHT.wght, legend = "Aa")),
                Triple(com.resyst.vk.core.LegendWeight.REGULAR, getString(R.string.weight_regular), KeyTile(weight = com.resyst.vk.core.LegendWeight.REGULAR.wght, legend = "Aa")),
                Triple(com.resyst.vk.core.LegendWeight.MEDIUM, getString(R.string.weight_medium), KeyTile(weight = com.resyst.vk.core.LegendWeight.MEDIUM.wght, legend = "Aa")),
                Triple(com.resyst.vk.core.LegendWeight.BOLD, getString(R.string.weight_bold), KeyTile(weight = com.resyst.vk.core.LegendWeight.BOLD.wght, legend = "Aa")),
            ), s.legendWeight) { v -> commit { it.copy(legendWeight = v) } }
            Ctl.LEGEND_SCALE -> {
                slider(getString(R.string.legend_scale_title), 85, 130, (s.legendScale * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(legendScale = v / 100f) } }
                into.addView(label(getString(R.string.legend_scale_summary), 12f, t.muted), lp(bottom = 4f))
            }
            Ctl.UPPER_LEGENDS -> toggle(getString(R.string.upper_legends_title), getString(R.string.upper_legends_summary), s.upperLegends) { v -> commit { it.copy(upperLegends = v) } }
            Ctl.BUBBLE -> choice(getString(R.string.bubble_title), listOf(com.resyst.vk.core.Bubble.CONNECTED to getString(R.string.bubble_connected),
                com.resyst.vk.core.Bubble.FLOATING to getString(R.string.bubble_floating)), s.bubble) { v -> commit { it.copy(bubble = v) } }
            Ctl.BAR_STYLE -> choice(getString(R.string.bar_style_title), listOf(com.resyst.vk.core.BarStyle.TEXT to getString(R.string.bar_text),
                com.resyst.vk.core.BarStyle.CHIPS to getString(R.string.bar_chips)), s.barStyle) { v -> commit { it.copy(barStyle = v) } }
            Ctl.HAPTIC_PROFILE -> {
                choice(getString(R.string.haptic_profile_title), listOf(com.resyst.vk.core.HapticProfile.BY_KEY to getString(R.string.haptic_by_key),
                    com.resyst.vk.core.HapticProfile.UNIFORM to getString(R.string.haptic_uniform)), s.hapticProfile) { v -> commit { it.copy(hapticProfile = v) } }
                into.addView(label(getString(R.string.haptic_profile_summary), 12f, t.muted), lp(bottom = 4f))
            }
            Ctl.MOTION -> {
                choice(getString(R.string.motion_title), listOf(com.resyst.vk.core.Motion.FULL to getString(R.string.motion_full),
                    com.resyst.vk.core.Motion.SUBTLE to getString(R.string.motion_subtle), com.resyst.vk.core.Motion.OFF to getString(R.string.motion_off)), s.motion) { v -> commit { it.copy(motion = v) } }
                into.addView(label(getString(R.string.motion_summary), 12f, t.muted), lp(bottom = 4f))
            }
            Ctl.THEME -> paletteGrid(s)
            Ctl.DAY_NIGHT -> dayNightPill(s)
            Ctl.ACCENT -> accentRow(s)
            Ctl.SHAPE -> keyTiles(getString(R.string.shape_title), listOf(
                Triple(KeyShape.SQUARE, getString(R.string.shape_square), KeyTile(shape = KeyShape.SQUARE)),
                Triple(KeyShape.SOFT, getString(R.string.shape_soft), KeyTile(shape = KeyShape.SOFT)),
                Triple(KeyShape.ROUND, getString(R.string.shape_round), KeyTile(shape = KeyShape.ROUND)),
                Triple(KeyShape.PILL, getString(R.string.shape_pill), KeyTile(shape = KeyShape.PILL)),
                Triple(KeyShape.CHAMFER, getString(R.string.shape_chamfer), KeyTile(shape = KeyShape.CHAMFER)),
            ), s.shape) { v -> commit { it.copy(shape = v) } }
            Ctl.CAP -> keyTiles(getString(R.string.cap_title), listOf(
                Triple(KeyCap.RAISED, getString(R.string.cap_raised), KeyTile(cap = KeyCap.RAISED)),
                Triple(KeyCap.FLAT, getString(R.string.cap_flat), KeyTile(cap = KeyCap.FLAT)),
                Triple(KeyCap.OUTLINE, getString(R.string.cap_outline), KeyTile(cap = KeyCap.OUTLINE)),
                Triple(KeyCap.KEYCAP, getString(R.string.cap_keycap), KeyTile(cap = KeyCap.KEYCAP)),
                Triple(KeyCap.BORDERLESS, getString(R.string.cap_borderless), KeyTile(cap = KeyCap.BORDERLESS)),
            ), s.cap) { v -> commit { it.copy(cap = v) } }
            Ctl.FONT -> keyTiles(getString(R.string.font_title), listOf(
                Triple(KeyFont.BRAND, getString(R.string.font_brand), KeyTile(font = KeyFont.BRAND, legend = "Añ")),
                Triple(KeyFont.LEGIBLE, getString(R.string.font_legible), KeyTile(font = KeyFont.LEGIBLE, legend = "Añ")),
                Triple(KeyFont.ROUNDED, getString(R.string.font_rounded), KeyTile(font = KeyFont.ROUNDED, legend = "Añ")),
                Triple(KeyFont.TECH, getString(R.string.font_tech), KeyTile(font = KeyFont.TECH, legend = "Añ")),
                Triple(KeyFont.HUMAN, getString(R.string.font_human), KeyTile(font = KeyFont.HUMAN, legend = "Añ")),
            ), s.font) { v -> commit { it.copy(font = v) } }
            Ctl.DENSITY -> choice(ctl.label, listOf(Density.TIGHT to "Compacta", Density.NORMAL to "Normal", Density.AIRY to "Aireada"), s.density) { v -> commit { it.copy(density = v) } }
            Ctl.HEIGHT -> slider(ctl.label, 80, 130, (s.heightScale * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(heightScale = v / 100f) } }
            Ctl.SUB_LEGENDS -> toggle(ctl.label, "Dígitos sobre la fila superior y punto de variantes", s.subLegends) { v -> commit { it.copy(subLegends = v) } }

            Ctl.LANG -> choice(ctl.label, listOf(Lang.ES to "Español", Lang.EN to "English"), s.lang) { v -> commit { it.copy(lang = v) } }
            Ctl.SYSTEM_LANGS -> link("${ctl.label} ›") { SubtypeSync(this).openSubtypeSettings() }
            Ctl.BILINGUAL -> toggle(ctl.label, "Escribe en español o inglés: las sugerencias siguen tus últimas palabras · el teclado cambia de idioma con «Idioma» o manteniendo pulsado el espacio", s.bilingual) { v -> commit { it.copy(bilingual = v) } }
            Ctl.SPACE_FLICK -> toggle(getString(R.string.space_flick_title), getString(R.string.space_flick_summary), s.spaceFlick) { v -> commit { it.copy(spaceFlick = v) } }
            Ctl.REGION -> choice(ctl.label, Region.values().map { it to it.label }, s.region) { v -> commit { it.copy(region = v) } }
            Ctl.HIDE_TOP_ROW -> toggle(ctl.label, "Quita la fila de acentos / números sobre las letras", s.hideTopRow) { v -> commit { it.copy(hideTopRow = v) } }
            Ctl.TOP_ROW -> choice(ctl.label, listOf(TopRow.ACCENTS to "Acentos", TopRow.NUMBERS to "Números"), s.topRow) { v -> commit { it.copy(topRow = v) } }
            Ctl.EMOJI_KEY -> toggle(ctl.label, "Junto a la coma · abre el panel de emojis con recientes", s.emojiKey) { v -> commit { it.copy(emojiKey = v) } }
            Ctl.DAY_NIGHT_CHIP -> toggle(ctl.label, "Sol / luna en la barra, además del panel ⚙ · un toque cambia entre claro y oscuro", s.dayNightChip) { v -> commit { it.copy(dayNightChip = v) } }
            Ctl.ONE_HANDED -> choice(ctl.label, listOf(OneHand.OFF to "Apagado", OneHand.RIGHT to "Derecha", OneHand.LEFT to "Izquierda"), s.oneHanded) { v -> commit { it.copy(oneHanded = v) } }
            Ctl.POPUPS -> toggle(ctl.label, "Burbuja sobre la tecla al pulsar · nunca en contraseñas", s.popups) { v -> commit { it.copy(popups = v) } }
            Ctl.LONG_PRESS -> slider(ctl.label, 150, 900, s.longPressMs, { "$it ms" }, step = 25) { v -> commit { it.copy(longPressMs = v) } }

            Ctl.SUGGEST -> toggle(ctl.label, "Léxico offline por frecuencia (sin red)", s.suggest) { v -> commit { it.copy(suggest = v) } }
            Ctl.SPACE_CORRECTS -> toggle(ctl.label, "Solo si la corrección es segura · ⌫ la deshace", s.spaceCorrects) { v -> commit { it.copy(spaceCorrects = v) } }
            // r13f (offers, PV4): title and line from the string resources (Spanish, English)
            Ctl.CONTEXT_OFFERS -> toggle(getString(R.string.context_offers_title), getString(R.string.context_offers_summary), s.contextOffers) { v -> commit { it.copy(contextOffers = v) } }
            Ctl.PERSONAL -> toggle(ctl.label, "Aprende tus palabras y correos frecuentes · solo en este teléfono", s.personal) { v -> commit { it.copy(personal = v) } }
            Ctl.AUTO_CAP -> toggle(ctl.label, "Al inicio de frase", s.autoCap) { v -> commit { it.copy(autoCap = v) } }
            Ctl.DOUBLE_SPACE -> toggle(ctl.label, "Dos espacios seguidos escriben «. »", s.doubleSpace) { v -> commit { it.copy(doubleSpace = v) } }
            Ctl.PROFANITY_FILTER -> toggle(ctl.label, "El teclado no propone groserías · lo que tú escribes no se toca, y si una la usas seguido vuelve a aparecer", s.profanityFilter) { v -> commit { it.copy(profanityFilter = v) } }
            Ctl.AUTO_OPENERS -> toggle(ctl.label, "Al cerrar una pregunta con ? sin su ¿, la barra ofrece «¿…?» · solo en español", s.autoOpeners) { v -> commit { it.copy(autoOpeners = v) } }

            Ctl.HAPTICS -> toggle(ctl.label, null, s.haptics) { v -> commit { it.copy(haptics = v) } }
            Ctl.HAPTIC_STRENGTH -> choice(ctl.label, listOf(HapticStrength.LOW to "Suave", HapticStrength.MEDIUM to "Media", HapticStrength.HIGH to "Fuerte"), s.hapticStrength) { v ->
                commit { it.copy(hapticStrength = v) }
                previewHaptic(v)
            }
            Ctl.SOUND -> toggle(ctl.label, "Sintetizado en el dispositivo, sin archivos", s.sound) { v -> commit { it.copy(sound = v) } }
            Ctl.SOUND_PACK -> choice(ctl.label, listOf(SoundPack.CLICK to "Click", SoundPack.THOCK to "Thock", SoundPack.TYPE to "Máquina", SoundPack.BUBBLE to "Burbuja"), s.soundPack) { v -> commit { it.copy(soundPack = v) } }
            Ctl.VOLUME -> slider(ctl.label, 0, 100, (s.volume * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(volume = v / 100f) } }

            Ctl.CLIP_HISTORY -> toggle(ctl.label, "Guarda lo que copias (hasta ${ClipboardHistory.MAX_ITEMS}) · nunca en campos de contraseña · solo en este teléfono", store.clip.history) { v ->
                commitClip { it.copy(history = v) }
            }
            Ctl.CLIP_PURGE -> toggle(ctl.label, "Borra solo lo no fijado que copiaste hace más de una hora", store.clip.purgeHour) { v -> commitClip { it.copy(purgeHour = v) } }
            Ctl.GIF_SEARCH -> gifRow()
            Ctl.GIF_NEW_ID -> gifNewIdRow()
            Ctl.CLEAR_CLIP -> clearClipRow()
            Ctl.FORGET_LEARNED -> forgetRow()

            Ctl.KNOW_WORDS -> knowWords()
            Ctl.KNOW_PAIRS -> knowPairs()
            Ctl.PAIR_MEMORY -> toggle(getString(R.string.pair_memory_title), getString(R.string.pair_memory_summary), s.pairMemory) { v -> commit { it.copy(pairMemory = v) } }
            Ctl.KNOW_REJECTIONS -> knowRejections()
            Ctl.KNOW_EMAILS -> knowEmails()
            Ctl.KNOW_EMOJI -> knowEmoji()
            Ctl.KNOW_CLIP -> knowClip()

            Ctl.UPDATES -> {
                into.addView(label(ctl.label, 14f, t.textMod, 550), lp(top = 4f))
                updateSection()
            }
            Ctl.UPDATE_AUTO -> toggle(ctl.label, UpdateNotice.autoSubtitle(), store.autoUpdateCheck) { v ->
                commitStore(store.copy(autoUpdateCheck = v))
            }
            Ctl.CONNECTIONS -> connectionsBook()
            Ctl.RESET_ALL -> resetRow()
        }
        if (ctl == Ctl.UPDATES) updateAnchor = into
    }

    // ── temas + modos ───────────────────────────────────────────────────

    /** The temas as cards in their own colors; tapping one puts it in use right away. */
    private fun temaPicker() {
        into.addView(label(Ctl.TEMA.label, 14f, t.textMod, 550), lp(top = 4f, bottom = 8f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (tm in store.temas) {
            val sel = tm.id == store.active
            // r14: each card is the tema in miniature (its palette as drawn now, its shape and cap); the one in use ringed
            val look = if (store.followSystem) DayNight.resolve(tm.look, SystemLook.dark(this)) else tm.look
            val pp = paletteOf(look)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = rounded(pp.theme.bg2, pp.theme.edgeHi, 14f).apply { if (sel) setStroke(px(2f), pp.accent) }
                setPadding(px(8f), px(8f), px(8f), px(8f))
                isClickable = true
                isLongClickable = true
                tag = "tema.${tm.id}"
                setOnClickListener { if (!sel) commitStore(store.withTema(tm.id)) }
                setOnLongClickListener { renameTema(tm.id); true }
                contentDescription = "Tema ${tm.name}, ${Themes.byId(tm.look.theme).label}" + if (sel) ", en uso" else ""
            }
            card.addView(MiniKeyboardView(this, pp, look.shape, look.cap), LinearLayout.LayoutParams(px(112f), px(58f)))
            card.addView(label("${tm.icon} ${tm.name}" + if (sel) "  ●" else "", 14f, pp.theme.text, if (sel) 700 else 550).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(top = 6f))
            card.addView(label(Themes.byId(look.theme).label, 11f, pp.theme.textMod, 500).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(top = 1f))
            row.addView(card, LinearLayout.LayoutParams(px(128f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) })
        }
        if (store.temas.size < com.resyst.vk.core.TemaOps.MAX_TEMAS) {
            row.addView(label(getString(R.string.tema_new), 14f, pal.accent, 650).apply {
                gravity = Gravity.CENTER
                background = rounded(t.bg, pal.accentGlow, 14f)
                isClickable = true
                tag = "tema-new"
                contentDescription = getString(R.string.tema_new_title)
                setOnClickListener { createTema() }
            }, LinearLayout.LayoutParams(px(112f), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = px(8f) })
        }
        into.addView(bleedRow(row), bleedLp())
        into.addView(label("Un tema es solo la apariencia. Idioma, sugerencias, vibración y sonido valen para todos los temas.", 12f, t.muted), lp(top = 4f, bottom = 6f))
    }

    /** "Modo": off, Código or Juego, each with its fixed, spelled-out effect. */
    private fun modePicker() {
        choice("Modo", Mode.values().map { it to (if (it == Mode.NONE) "Ninguno" else it.label) }, store.mode) { m ->
            commitStore(store.withMode(m))
        }
        into.addView(label(store.mode.summary, 12f, if (store.mode == Mode.NONE) t.muted else pal.accent, 500).apply { tag = "mode-summary" }, lp(bottom = 6f))
    }

    /** On a page the mode overrides: say so, and offer the way out, instead of silently ignoring edits. */
    private fun modeBanner() {
        val m = store.mode
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.accentSoft, pal.accentGlow, 12f)
            setPadding(px(14f), px(10f), px(14f), px(6f))
            tag = "mode-banner"
        }
        card.addView(label("${m.icon}  Modo ${m.label} encendido", 14f, t.text, 650))
        card.addView(label("${m.summary} Lo que cambies aquí se aplica al apagarlo.", 12f, t.textMod), lp(top = 2f))
        card.addView(label("Apagar el modo", 13f, pal.accent, 650).apply {
            minHeight = px(44f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { commitStore(store.withMode(Mode.NONE)) }
        })
        root.addView(card, lp(bottom = 12f))
    }

    private fun dayNightPill(s0: KbSettings) {
        // r14 (FS2): what is on screen flips; while following the system the flip also stops following
        val s = shownSettings()
        val dark = Themes.byId(s.theme).dark
        val toLabel = Themes.byId(DayNight.toggle(s).theme).label
        into.addView(label(Ctl.DAY_NIGHT.label, 14f, t.textMod, 550), lp(top = 8f))
        into.addView(pill(if (dark) "☀  Modo día · $toLabel" else "☾  Modo noche · $toLabel") {
            commitStore(com.resyst.vk.core.Quick.apply(store, com.resyst.vk.core.QuickAction.DAY_NIGHT, SystemLook.dark(this)))
        }.apply { tag = "daynight"; contentDescription = "Cambiar a modo ${if (dark) "día" else "noche"}: $toLabel" }, lp(top = 8f, bottom = 4f))
    }

    // ── updates (the app's only network use: these buttons + the keyboard-open check, r11c) ──

    private fun updateSection() {
        val inst = Updater.installed(this)
        val st = Updater.state
        // r9: an available update reads as a card (amber edge, ✦) — the same mark as the keyboard chip
        val available = ((st as? Updater.State.Checked)?.decision as? UpdateDecision.Available)?.release
        if (available != null) updateCard(available, inst.versionName)
        into.addView(label("Versión instalada: ${inst.versionName}", 14f, t.textMod, 550).apply { tag = "update-installed" }, lp(top = 4f))
        into.addView(label(UpdateNotice.promise(store.autoUpdateCheck), 12f, t.muted).apply { tag = "update-promise" }, lp(top = 2f, bottom = 10f))
        when (st) {
            Updater.State.Idle -> checkButton("Buscar actualizaciones")
            Updater.State.Checking -> status("Buscando actualizaciones…")
            is Updater.State.Checked -> when (val d = st.decision) {
                is UpdateDecision.UpToDate -> {
                    status("✓ Ya tienes la última versión (${d.latest}).", t.ok)
                    status(checkedWhen(), t.muted, 12f)
                    checkButton("Buscar de nuevo", quiet = true)
                }
                is UpdateDecision.Available -> Unit // the card above

                is UpdateDecision.Incompatible -> {
                    status("La versión ${d.release.version} requiere Android ${d.release.minAndroid ?: "API ${d.minSdk}"} o superior; este teléfono no puede instalarla.", t.bad)
                    checkButton("Buscar de nuevo", quiet = true)
                }
                UpdateDecision.NotPublished -> {
                    status("Todavía no hay una versión publicada.")
                    checkButton("Buscar de nuevo", quiet = true)
                }
                is UpdateDecision.Error -> {
                    status("No se pudo leer la información de la versión publicada.", t.bad)
                    checkButton("Reintentar", quiet = true)
                }
            }
            is Updater.State.Downloading -> {
                status("Descargando ${st.release.version}… El progreso aparece en las notificaciones.")
                link("Cancelar la descarga") { Updater.cancel(this) }
            }
            is Updater.State.Verifying -> status("Comprobando la huella SHA-256…")
            is Updater.State.Ready -> {
                status("✓ ${st.release.version} descargada y verificada (SHA-256 coincide).", t.ok)
                into.addView(pill("Instalar ${st.release.version}") { install(st) }.apply { tag = "update-install" }, lp(top = 8f))
            }
            is Updater.State.Failed -> {
                status(st.message, t.bad)
                checkButton("Reintentar", quiet = true)
            }
        }
    }

    /** "Comprobado al abrir el teclado · hace 3 min" — where the shown state came from. */
    private fun checkedWhen(): String {
        val mins = ((System.currentTimeMillis() - Updater.checkedAt) / 60_000).coerceAtLeast(0)
        val ago = when {
            mins < 1 -> "ahora mismo"
            mins < 60 -> "hace $mins min"
            mins < 48 * 60 -> "hace ${mins / 60} h"
            else -> "hace ${mins / (24 * 60)} días"
        }
        return com.resyst.vk.core.UpdateOrigin.checkedLabel(Updater.checkedBy) + " · $ago"
    }

    /**
     * r9: the update-available card. Amber edge + ✦ badge on the surface color, the version as the
     * headline, facts in muted, one primary pill and a quiet "Ahora no" (which also silences the
     * keyboard chip for this version).
     */
    private fun updateCard(r: com.resyst.vk.core.Release, installed: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.bg2, pal.accent, 14f)
            setPadding(px(16f), px(14f), px(16f), px(10f))
            tag = "update-card"
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("✦", 15f, pal.accentInk, 700).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent) }
        }, LinearLayout.LayoutParams(px(30f), px(30f)).apply { marginEnd = px(12f) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("Nueva versión disponible: ${r.version}", 16f, t.text, 650).apply { tag = "update-status" })
        col.addView(label(checkedWhen() + " · tienes $installed", 12f, t.muted, 450))
        head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        val facts = listOfNotNull(r.size, r.date?.take(10), r.minAndroid?.let { "Android $it o superior" })
        if (facts.isNotEmpty()) card.addView(label(facts.joinToString(" · "), 12f, t.textMod, 500), lp(top = 10f))
        card.addView(pill("Descargar e instalar ${r.version}") { confirmDownload(r) }.apply {
            tag = "update-download"
            contentDescription = "Descargar e instalar Resyst VK ${r.version}"
        }, lp(top = 12f))
        card.addView(label("Ahora no", 13f, pal.accent, 600).apply {
            gravity = Gravity.CENTER
            minHeight = px(44f)
            isClickable = true
            tag = "update-later"
            contentDescription = "Ahora no. El teclado no volverá a avisar de la versión ${r.version}"
            setOnClickListener { Updater.dismissNotice(this@SettingsActivity, r.version); Updater.dismiss() }
        }, lp(top = 2f))
        into.addView(card, lp(top = 4f, bottom = 12f))
    }

    private fun status(text: String, color: Int = t.text, size: Float = 14f) {
        into.addView(label(text, size, color, 500).apply { tag = "update-status" }, lp(top = 4f))
    }

    private fun checkButton(text: String, quiet: Boolean = false) {
        if (quiet) link(text) { Updater.check(this) }
        else into.addView(pill(text) { Updater.check(this) }.apply { tag = "update-check" }, lp(top = 4f))
    }

    private fun confirmDownload(r: com.resyst.vk.core.Release) {
        AlertDialog.Builder(this)
            .setTitle("¿Descargar Resyst VK ${r.version}?")
            .setMessage(
                "Se descarga desde kv.resyst.cl" + (r.size?.let { " ($it)" } ?: "") + ". " +
                    "Antes de instalar se comprueba que el archivo coincide con la huella SHA-256 publicada, " +
                    "y Android verifica que está firmado por Resyst. Tus ajustes y lo aprendido se conservan.",
            )
            .setPositiveButton("Descargar") { _, _ -> autoInstall = true; Updater.download(this, r) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** After "Descargar", open the installer by itself once the file is verified (screen visible). */
    private var autoInstall = false

    private fun maybeAutoInstall() {
        val st = Updater.state
        if (autoInstall && st is Updater.State.Ready && hasWindowFocus()) {
            autoInstall = false
            install(st)
        }
    }

    private fun install(st: Updater.State.Ready) {
        if (!Updater.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permite instalar la actualización")
                .setMessage("Android pide que autorices a Resyst VK a instalar apps. Activa «Permitir de esta fuente», vuelve aquí y toca «Instalar».")
                .setPositiveButton("Abrir ajustes") { _, _ -> startActivity(Updater.unknownSourcesSettings(this)) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        try {
            startActivity(Updater.installIntent(this, st.file))
        } catch (e: android.content.ActivityNotFoundException) {
            AlertDialog.Builder(this).setMessage("No se encontró el instalador de Android.").setPositiveButton("OK", null).show()
        }
    }

    // ── destructive rows (always last on their page, confirmed, off when there is nothing to erase) ──

    private fun dangerRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit, summaryTag: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = px(52f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = enabled
            isEnabled = enabled
            contentDescription = "$title. $summary" + if (enabled) "" else ", no disponible"
            if (enabled) setOnClickListener { onClick() }
        }
        row.addView(label(title, 15f, if (enabled) t.bad else t.muted, 600))
        row.addView(label(summary, 12f, t.muted).apply { if (summaryTag != null) tag = summaryTag })
        into.addView(row, lp(top = 6f))
    }

    /**
     * Wipes everything files/personal/ keeps (shared by every tema), after a confirm: learned words,
     * remembered emails, emoji recents and chosen skin tones (r11a-fix F1: each one alone enables it).
     */
    private fun forgetRow() {
        val w = PersonalStore.words
        val v = PersonalStore.values
        if (w == null || v == null) {
            dangerRow(Ctl.FORGET_LEARNED.label, "Cargando…", enabled = false, onClick = {})
            return
        }
        val counts = PersonalSummary.Counts(
            words = Lang.values().sumOf { w.vocabCount(it) },
            emails = v.values(FieldKind.EMAIL).size,
            emojiRecents = emojiRecents().items().size,
            tones = emojiTones()?.size(), // r12 (SA2): null = still loading the catalog, "…"
            rejections = PersonalStore.rejections?.size ?: 0, // r13 (LR5): files/personal/rejections.json
            pairs = PersonalStore.pairs?.let { if (it.isEmpty()) 0 else it.pairCount().coerceAtLeast(1) } ?: 0, // r13i: pairs.bin
        )
        dangerRow(Ctl.FORGET_LEARNED.label, PersonalSummary.text(counts), enabled = !counts.empty, onClick = { confirmForget() })
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar lo aprendido?")
            .setMessage("Se olvidan las palabras, los pares de palabras, las correcciones que deshiciste, los correos, los emojis recientes y los tonos de piel que el teclado guardó de ti, en todos los temas. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                PersonalStore.clear(this) // the row re-counts when the files are gone (personalListener)
                rerender()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── r10 (F-4): "Lo que sé de ti" — everything the keyboard keeps, deletable one by one ──

    /** How many learned words each language list shows before "Mostrar todas". */
    private var wordsShown = KNOW_WORDS_STEP

    private fun knowIntro() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.bg2, t.edge, 12f)
            setPadding(px(14f), px(12f), px(14f), px(12f))
            tag = "know-intro"
        }
        card.addView(label("Esto es todo lo que Resyst VK guarda de ti.", 14f, t.text, 600))
        card.addView(label("Vive solo en este teléfono, fuera de las copias de seguridad, y nunca se envía a ningún lado. Toca ✕ para borrar una cosa; lo demás se queda igual.", 12f, t.textMod), lp(top = 4f))
        root.addView(card, lp(bottom = 10f))
    }

    /** A listed item with its own delete target (48 dp), named for TalkBack ([deleteDesc] = the target's whole name). */
    private fun knowRow(text: String, meta: String?, desc: String, tagId: String, deleteDesc: String? = null, onDelete: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48f)
            tag = "know.$tagId"
        }
        row.addView(label(text, 15f, t.text, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (meta != null) row.addView(label(meta, 12f, t.textMod, 500).apply { setPadding(px(8f), 0, px(4f), 0) })
        // 48 dp target; the glyph's visual edge sits on the content margin (no ragged right edge)
        row.addView(label("✕", 18f, t.bad, 500).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = deleteDesc ?: "Borrar $desc"
            tag = "forget.$tagId"
            setOnClickListener { onDelete() }
        }, LinearLayout.LayoutParams(px(48f), px(48f)))
        into.addView(row, lp())
        into.addView(View(this).apply { setBackgroundColor(t.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)))
    }

    private fun knowHead(title: String, summary: String) {
        into.addView(label(title, 15f, t.text, 600), lp(top = 22f))
        into.addView(label(summary, 12f, t.muted).apply { tag = "know-summary" }, lp(bottom = 4f))
    }

    private fun knowWords() {
        val w = PersonalStore.words
        if (w == null) { knowHead(Ctl.KNOW_WORDS.label, "Cargando…"); return }
        val total = Lang.values().sumOf { w.vocabCount(it) }
        knowHead(Ctl.KNOW_WORDS.label, if (total == 0) "Ninguna todavía. Las aprende al escribir, solo en campos normales: nunca en contraseñas ni en modo incógnito, y donde la app pidió no recibir sugerencias usa lo que ya sabe pero no guarda nada." else "$total palabras y cuántas veces las usaste")
        val langs = Lang.values().filter { w.vocabCount(it) > 0 }
        for (lang in langs) {
            val n = w.vocabCount(lang)
            // the model is per keyboard language: say which keyboard learned them (only when both did)
            if (langs.size > 1) into.addView(label((if (lang == Lang.ES) "CON EL TECLADO EN ESPAÑOL · $n" else "CON EL TECLADO EN INGLÉS · $n"), 11f, t.muted, 700).apply { letterSpacing = 0.1f }, lp(top = 10f, bottom = 2f))
            for (e in w.words(lang, wordsShown)) {
                // r11 (K5): a word the user kept by undoing its correction says so
                val uses = if (e.count == 1) "1 vez" else "${e.count} veces"
                knowRow(e.form, if (e.kept) "$uses · conservada" else uses, "la palabra ${e.form}", "${lang.code}.${e.key}") {
                    if (w.forget(lang, e.key)) { PersonalStore.changed(); rerender() }
                }
            }
            if (n > wordsShown) link("Mostrar más (${n - wordsShown}) ›") { wordsShown += KNOW_WORDS_STEP * 4; rerender() }
        }
    }

    /** r13i (KN1): how many pairs the section lists before "Mostrar más". */
    private var pairsShown = KNOW_PAIRS_STEP
    /** r13i (KN3): the memory's file size in bytes, kept per memory and [com.resyst.vk.core.PairMemory.version]. */
    private var pairsSize: Triple<com.resyst.vk.core.PairMemory, Long, Int>? = null

    private fun pairsBytes(p: com.resyst.vk.core.PairMemory): Int {
        val v = p.version
        pairsSize?.let { (m, ver, n) -> if (m === p && ver == v) return n }
        return p.toBytes().size.also { pairsSize = Triple(p, v, it) }
    }

    /**
     * r13i (KN1): the pair memory — its size, whether the offers use it yet (cold start, PM6) or the switch is off, the
     * most used pairs with ✕ (forget one: the pair and its triples), and "Borrar todos los pares" (memory + file).
     */
    private fun knowPairs() {
        val title = getString(R.string.know_pairs_title)
        val p = PersonalStore.pairs
        if (p == null) { knowHead(title, "Cargando…"); return }
        val n = p.pairCount()
        val state = when {
            !b.pairMemory -> getString(R.string.know_pairs_off)
            p.active -> getString(R.string.know_pairs_active)
            else -> getString(R.string.know_pairs_learning, (100 * p.tokens / com.resyst.vk.core.PairMemory.ACTIVE_TOKENS).toInt().coerceIn(0, 99))
        }
        knowHead(title, if (p.isEmpty()) getString(R.string.know_pairs_empty) + if (!b.pairMemory) " · " + getString(R.string.know_pairs_off) else ""
            else getString(R.string.know_pairs_summary, n, p.wordCount, (pairsBytes(p) + 1023) / 1024) + " · " + state)
        for (e in p.topPairs(pairsShown)) {
            val pair = "${e.first} ${e.second}"
            knowRow(pair, "×${e.count.roundToInt().coerceAtLeast(1)}", pair, "pair.${e.first}.${e.second}", deleteDesc = getString(R.string.know_pairs_forget_desc, pair)) {
                if (p.forget(e.first, e.second)) { PersonalStore.changed(); rerender() }
            }
        }
        if (n > pairsShown) link(getString(R.string.know_pairs_more, n - pairsShown)) { pairsShown += KNOW_PAIRS_STEP * 4; rerender() }
        if (!p.isEmpty()) dangerRow(getString(R.string.know_pairs_clear), getString(R.string.know_pairs_clear_summary, n), enabled = true, onClick = { confirmClearPairs() }, summaryTag = "know-pairs-clear")
    }

    private fun confirmClearPairs() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.know_pairs_clear_title))
            .setMessage(getString(R.string.know_pairs_clear_message))
            .setPositiveButton(getString(R.string.know_pairs_clear_ok)) { _, _ ->
                PersonalStore.clearPairs(this) // memory and file (PM5); the page re-renders when the file is gone
                rerender()
            }
            .setNegativeButton(getString(R.string.know_pairs_cancel), null)
            .show()
    }

    /**
     * r13 (LR5): each correction the user undid (typed → fix), with ✕ to let space apply it again.
     * r13c (TS5): the ones undone once are listed too — space still applies them in other fields
     * until the second time.
     */
    private fun knowRejections() {
        val r = PersonalStore.rejections
        if (r == null) { knowHead(Ctl.KNOW_REJECTIONS.label, "Cargando…"); return }
        val list = r.items()
        val once = r.strikes()
        val forGood = when (list.size) { 0 -> null; 1 -> "1 · el espacio ya no la aplica"; else -> "${list.size} · el espacio ya no las aplica" }
        val first = when (once.size) { 0 -> null; 1 -> "1 deshecha una vez"; else -> "${once.size} deshechas una vez" }
        knowHead(Ctl.KNOW_REJECTIONS.label, if (forGood == null && first == null) "Ninguna. Si deshaces una corrección (⌫ justo después, ↶, o borras la palabra y la escribes de nuevo), el espacio la deja como la escribiste en ese campo; si la deshaces otra vez en otro campo, no la vuelve a aplicar."
            else listOfNotNull(forGood, first).joinToString(" · "))
        for (e in list) knowRow("${e.typed} → ${e.fix}", null, "la corrección de ${e.typed} a ${e.fix}", "rejection.${e.typed}.${e.fix}") {
            if (r.forget(e.typed, e.fix)) { PersonalStore.changed(); rerender() }
        }
        for (e in once) knowRow("${e.typed} → ${e.fix}", "una vez · si la deshaces otra vez, el espacio deja de aplicarla", "la corrección de ${e.typed} a ${e.fix}", "strike.${e.typed}.${e.fix}") {
            if (r.forget(e.typed, e.fix)) { PersonalStore.changed(); rerender() }
        }
    }

    private fun knowEmails() {
        val v = PersonalStore.values
        if (v == null) { knowHead(Ctl.KNOW_EMAILS.label, "Cargando…"); return }
        val list = v.values(FieldKind.EMAIL)
        knowHead(Ctl.KNOW_EMAILS.label, if (list.isEmpty()) "Ninguno. Recuerda una dirección cuando la escribes completa en un campo de correo." else "${list.size} · se ofrecen al escribir en campos de correo")
        for (e in list) knowRow(e, null, "el correo $e", "email.$e") {
            if (v.forget(FieldKind.EMAIL, e)) { PersonalStore.changed(); rerender() }
        }
    }

    /** Same file the keyboard reads (files/personal/emoji_recents.txt); it re-reads on every panel open. */
    private val emojiFile get() = java.io.File(java.io.File(filesDir, "personal"), "emoji_recents.txt")

    /** Read like the keyboard reads it (junk entries dropped). */
    private fun emojiRecents(): EmojiRecents =
        runCatching { EmojiRecents.decode(emojiFile.takeIf { it.exists() }?.readText()) }.getOrDefault(EmojiRecents())

    private fun knowEmoji() {
        val r = emojiRecents()
        val list = r.items()
        knowHead(Ctl.KNOW_EMOJI.label, if (list.isEmpty()) "Ninguno. Nunca se guardan los de campos en modo incógnito." else "${list.size} · los últimos que usaste, para el panel de emojis")
        if (list.isEmpty()) { knowTones(); return } // F3: tones are listed even with no recents
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (e in list) {
            row.addView(label(e, 22f, t.text).apply {
                gravity = Gravity.CENTER
                isClickable = true
                background = rounded(t.key, t.edge, 12f)
                contentDescription = "Borrar el emoji $e de recientes"
                tag = "forget.emoji"
                setOnClickListener {
                    if (r.remove(e)) {
                        runCatching { emojiFile.parentFile?.mkdirs(); emojiFile.writeText(r.encode()) }
                        rerender()
                    }
                }
            }, LinearLayout.LayoutParams(px(48f), px(48f)).apply { marginEnd = px(6f) })
        }
        hs.addView(row)
        into.addView(hs, lp(top = 4f))
        into.addView(label("Toca un emoji para quitarlo de recientes.", 11f, t.muted), lp(top = 4f, bottom = 4f))
        knowTones()
    }

    /** r11 (ET2): the skin tones chosen by long-press are kept too (files/personal/emoji_tones.txt): listed, tap to forget one. */
    private val tonesFile get() = java.io.File(java.io.File(filesDir, "personal"), "emoji_tones.txt")

    /**
     * r11a-fix (F3 / m1): decoded exactly like the keyboard does ([EmojiTones.decode] against the
     * catalog), so a stale or junk line is neither listed nor counted; forgetting one rewrites the
     * file with the remaining valid pairs only.
     */
    private fun emojiTones(): EmojiTones? {
        val raw = runCatching { tonesFile.takeIf { it.exists() }?.readText() }.getOrNull()
        if (raw.isNullOrBlank()) return EmojiTones() // nothing kept: no catalog needed
        // r12 (SA1): the 49 KB catalog is parsed off the main thread; until then the tones are unknown (SA2)
        val catalog = EmojiAsset.cached() ?: run { loadCatalog(); return null }
        return runCatching { EmojiTones.decode(raw, catalog) }.getOrDefault(EmojiTones())
    }

    private var catalogAsked = false

    /** SA1/SA3: one background parse per activity; its answer re-renders the rows that show tones, if still alive. */
    private fun loadCatalog() {
        if (catalogAsked) return
        catalogAsked = true
        EmojiAsset.load(this) {
            if (isFinishing || isDestroyed) return@load
            val pg = page
            if (pg == SettingsIA.pageOf(Ctl.FORGET_LEARNED)?.id || pg == SettingsIA.pageOf(Ctl.KNOW_EMOJI)?.id) rerender()
        }
    }

    private fun knowTones() {
        val tones = emojiTones()
        if (tones == null) { // SA2: kept tones are being read; never "0" / nothing while loading
            into.addView(label("Tonos de piel elegidos · ${PersonalSummary.LOADING}", 12f, t.muted, 600).apply { tag = "know-tones-loading" }, lp(top = 10f))
            return
        }
        val pairs = tones.entries()
        if (pairs.isEmpty()) return
        into.addView(label("Tonos de piel elegidos · ${pairs.size}", 12f, t.muted, 600), lp(top = 10f))
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((base, tone) in pairs) {
            row.addView(label(tone, 22f, t.text).apply {
                gravity = Gravity.CENTER
                isClickable = true
                background = rounded(t.key, t.edge, 12f)
                contentDescription = "Olvidar el tono elegido para $base"
                tag = "forget.tone"
                setOnClickListener {
                    if (tones.forget(base)) runCatching { tonesFile.writeText(tones.encode()) }
                    rerender()
                }
            }, LinearLayout.LayoutParams(px(48f), px(48f)).apply { marginEnd = px(6f) })
        }
        hs.addView(row)
        into.addView(hs, lp(top = 4f))
        into.addView(label("Toca uno para volver al tono amarillo.", 11f, t.muted), lp(top = 4f, bottom = 4f))
    }

    private fun knowClip() {
        val h = ClipStore.history
        if (h == null) { knowHead(Ctl.KNOW_CLIP.label, "Cargando…"); return }
        val items = h.items()
        knowHead(Ctl.KNOW_CLIP.label, when {
            !store.clip.history -> "El historial está apagado: no se guarda nada de lo que copias."
            items.isEmpty() -> "Vacío. Nunca guarda lo que copias desde un campo de contraseña."
            else -> "${items.size} elementos" + if (h.pinCount > 0) " · ${h.pinCount} fijados" else ""
        })
        val now = System.currentTimeMillis()
        for (e in items) {
            val meta = (if (e.pinned) "fijado · " else "") + com.resyst.vk.core.ClipRules.ago(now, e.at)
            knowRow(com.resyst.vk.core.ClipRules.label(e.text, false), meta, "del portapapeles: ${com.resyst.vk.core.ClipRules.label(e.text, false)}", "clip.${e.id}") {
                if (h.delete(e.id)) ClipStore.changed() // listeners → rerender
            }
        }
    }

    // ── r10 (F-5): "Libro de conexiones" — the promise, counted ──────────
    private fun connectionsBook() {
        val log = Updater.connections(this)
        into.addView(label(Ctl.CONNECTIONS.label, 14f, t.textMod, 600), lp(top = 10f))
        val head = when (log.total) {
            0 -> "Ninguna conexión a internet desde la instalación."
            1 -> "1 conexión a internet desde la instalación."
            else -> "${log.total} conexiones a internet desde la instalación."
        }
        into.addView(label(head, 16f, t.text, 650).apply { tag = "connections-total" }, lp(top = 2f))
        // r11b (GT2): two clients now, each named with where it goes
        val gifOn = KlipyClient.available && GifStore.read(this).active
        val why = "Para comprobar o descargar actualizaciones de Resyst VK en kv.resyst.cl" +
            (if (KlipyClient.available) ", y para la búsqueda de GIF con KLIPY solo si la activas (ahora ${if (gifOn) "activada" else "apagada"})" else "") +
            ". Nunca se envía lo que escribes en las apps, lo que copias ni lo aprendido."
        into.addView(label(why, 12f, t.muted).apply { tag = "connections-why" }, lp(top = 2f, bottom = 6f))
        val fmt = java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("es", "CL"))
        for ((i, e) in log.recent().withIndex()) {
            val media = when (e.media) { 0 -> ""; 1 -> " · 1 archivo"; else -> " · ${e.media} archivos" }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = px(48f)
                gravity = Gravity.CENTER_VERTICAL
                tag = "connection.$i"
                isFocusable = true
                contentDescription = "${e.what.label}, a ${e.what.host}, ${fmt.format(java.util.Date(e.at))}, ${e.why.label}. ${e.outcome}$media"
            }
            col.addView(label("${e.what.label} · ${e.what.host} · ${e.why.label}", 14f, t.text, 550))
            col.addView(label("${fmt.format(java.util.Date(e.at))} · ${e.outcome}$media", 12f, t.muted))
            into.addView(col, lp())
        }
        if (log.total > log.recent().size) {
            into.addView(label("Se muestran las ${log.recent().size} más recientes.", 11f, t.muted), lp(top = 4f))
        }
    }

    // ── r11b: opt-in GIF search (KLIPY) ─────────────────────────────────
    /**
     * Hidden entirely in a build without a KLIPY key (GK2). OFF → the disclosure dialog, whose
     * «Activar» is the only way on (back / outside / «Cancelar» leave it off, GO2). ON → «Apagar»
     * deletes the anonymous id at once.
     */
    private fun gifRow() {
        if (!KlipyClient.available) return
        val st = GifStore.read(this)
        toggle(Ctl.GIF_SEARCH.label, if (st.active) "Activada · pestaña GIF en el panel de emojis · cada conexión va al Libro de conexiones"
            else "Apagada · el teclado no se conecta a KLIPY · al activarla te digo qué se envía", st.active) { v ->
            if (v) confirmGif() else { GifStore.turnOff(this); rerender() }
        }
    }

    private fun gifNewIdRow() {
        if (!KlipyClient.available) return
        val st = GifStore.read(this)
        if (!st.active) return
        val id = st.customerId ?: return
        link("${Ctl.GIF_NEW_ID.label} ›") {
            GifStore.newId(this)
            android.widget.Toast.makeText(this, "Listo: KLIPY verá un ID nuevo", android.widget.Toast.LENGTH_SHORT).show()
            rerender()
        }
        // the id itself, abbreviated: enough to see it change, nothing to copy
        into.addView(label("ID actual: ${id.take(8)}…", 11f, t.muted).apply { tag = "gif-id" }, lp(bottom = 4f))
    }

    private fun confirmGif() {
        val body = GifCopy.DISCLOSURE.joinToString("\n\n") { "•  $it" }
        AlertDialog.Builder(this)
            .setTitle(GifCopy.TITLE)
            .setMessage(body)
            .setPositiveButton(GifCopy.ACCEPT) { _, _ -> GifStore.accept(this); rerender() }
            .setNegativeButton(GifCopy.CANCEL, null)
            .show()
    }

    // ── clipboard (device-wide) ─────────────────────────────────────────
    private fun commitClip(f: (ClipSettings) -> ClipSettings) = commitStore(store.copy(clip = f(store.clip)))

    private fun clearClipRow() {
        val h = ClipStore.history
        val summary = when {
            h == null -> "Cargando…"
            h.isEmpty() -> "Vacío · nada sale del teléfono"
            else -> "${h.size} elementos" + (if (h.pinCount > 0) " · ${h.pinCount} fijados" else "") + " · nada sale del teléfono"
        }
        dangerRow(Ctl.CLEAR_CLIP.label, summary, enabled = h != null && !h.isEmpty(), onClick = { confirmClearClipboard() }, summaryTag = "clip-summary")
        into.addView(label("«Pegar» aparece en la barra del teclado al copiar algo. Android solo avisa al teclado de lo que copias mientras está activo.", 12f, t.muted), lp(top = 2f, bottom = 4f))
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar el historial del portapapeles?")
            .setMessage("Se borra todo lo guardado, también lo fijado. Lo que esté ahora en el portapapeles de Android no cambia. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ -> ClipStore.clear(this) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resetRow() {
        dangerRow(Ctl.RESET_ALL.label, "Vuelven los temas de fábrica y los ajustes por defecto · lo aprendido y el portapapeles no se tocan", enabled = true, onClick = {
            AlertDialog.Builder(this)
                .setTitle("¿Restablecer ajustes y temas?")
                .setMessage("Los temas, el modo y todos los ajustes vuelven a como venían. Lo aprendido y el historial del portapapeles se conservan.")
                .setPositiveButton("Restablecer") { _, _ -> repo.reset(); store = repo.load(); go(null) }
                .setNegativeButton("Cancelar", null)
                .show()
        })
    }

    /** Picking a level plays one key click at that level, so the choice is felt, not guessed. */
    private fun previewHaptic(strength: HapticStrength) {
        val player = haptics ?: HapticPlayer(this).also { haptics = it }
        Haptics.pulseFor(HapticEvent.KEY, enabled = true)?.let { player.play(it, strength) }
    }

    // ── r14: the look, seen ──────────────────────────────────────────────

    /** The settings the keyboard shows right now: the stored look resolved against the system's dark mode (FS2). */
    private fun shownSettings(): KbSettings = store.settingsFor(SystemLook.dark(this))

    /** The palette a look would be drawn with on this phone now (tint, high contrast, the wallpaper's colours). */
    private fun paletteOf(l: Look): Palette = SystemLook.palette(this, l.applyTo(store.phone))

    /** «Tu teclado»: the live keyboard of the tema in use, its name, and the two things people change most. */
    private fun heroCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.bg2, t.edge, 18f)
            setPadding(px(12f), px(12f), px(12f), px(12f))
            tag = "hero"
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(getString(R.string.home_your_keyboard).uppercase(), 11f, pal.accent, 700).apply { letterSpacing = 0.14f })
        col.addView(label("${store.activeTema.icon}  ${store.activeTema.name}", 18f, t.text, 700), lp(top = 2f))
        head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head, lp(bottom = 8f))
        val prev = into
        into = card
        try {
            livePreviewInto(card)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actions.addView(pill(getString(R.string.home_customize)) { go(PAGE_LOOK) }.apply {
                tag = "customize"
                contentDescription = getString(R.string.home_customize_desc)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8f) })
            actions.addView(dayNightButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(actions, lp(top = 8f))
            ctl(Ctl.FOLLOW_SYSTEM)
        } finally {
            into = prev
        }
        root.addView(card, lp(bottom = 6f))
    }

    /** Día / noche as a quiet pill; while following the system it stops following and shows the opposite (FS2). */
    private fun dayNightButton(): View {
        val s = shownSettings()
        val dark = Themes.byId(s.theme).dark
        val toLabel = Themes.byId(DayNight.toggle(s).theme).label
        return label(if (dark) "☀  $toLabel" else "☾  $toLabel", 14f, t.text, 600).apply {
            gravity = Gravity.CENTER
            minHeight = px(46f)
            maxLines = 1
            setPadding(px(14f), px(10f), px(14f), px(10f))
            background = rounded(t.key, t.edgeHi, 23f)
            isClickable = true
            tag = "daynight"
            contentDescription = "Cambiar a modo ${if (dark) "día" else "noche"}: $toLabel"
            setOnClickListener { commitStore(com.resyst.vk.core.Quick.apply(store, com.resyst.vk.core.QuickAction.DAY_NIGHT, SystemLook.dark(this@SettingsActivity))) }
        }
    }

    /** A group heading inside a page (the long «Apariencia» reads in sections). */
    private fun groupHeader(title: String) {
        into.addView(label(title.uppercase(), 11f, t.muted, 700).apply { letterSpacing = 0.14f; tag = "group" }, lp(top = 20f, bottom = 2f))
        into.addView(View(this).apply { setBackgroundColor(t.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)).apply { bottomMargin = px(4f) })
    }

    private fun groupTitle(ctl: Ctl): String? = when (ctl) {
        Ctl.STYLES -> getString(R.string.group_styles)
        Ctl.THEME -> getString(R.string.group_colors)
        Ctl.SHAPE -> getString(R.string.group_keys)
        Ctl.FONT -> getString(R.string.group_type)
        Ctl.BUBBLE -> getString(R.string.group_details)
        else -> null
    }

    /** A card that is selected wears a 2 dp accent ring; the others a hairline (one signal, LG). */
    private fun cardBg(sel: Boolean, fill: Int = t.bg2) = rounded(fill, if (sel) pal.accent else t.edge, 14f).apply { if (sel) setStroke(px(2f), pal.accent) }

    /** The palettes as miniature keyboards in a 3-column grid: dark, light, then the wallpaper's (Android 12+). */
    private fun paletteGrid(s: KbSettings) {
        val look = store.activeTema.look
        fun group(title: String, list: List<com.resyst.vk.core.Theme>, enabled: Boolean = true, note: String? = null) {
            into.addView(label(title, 12f, t.muted, 600), lp(top = 8f, bottom = 4f))
            if (note != null) into.addView(label(note, 11f, t.muted), lp(bottom = 4f))
            list.chunked(3).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (th in chunk) {
                    val sel = th.id == s.theme
                    val pp = paletteOf(look.copy(theme = th.id))
                    val cardV = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = cardBg(sel)
                        setPadding(px(6f), px(6f), px(6f), px(4f))
                        isClickable = enabled
                        isEnabled = enabled
                        alpha = if (enabled) 1f else 0.45f
                        tag = "palette.${th.id}"
                        contentDescription = getString(R.string.palette_desc, th.label) + (if (sel) ", seleccionado" else "") + if (enabled) "" else ", no disponible"
                        if (enabled) setOnClickListener { if (!sel) commit { it.copy(theme = th.id) } }
                    }
                    cardV.addView(MiniKeyboardView(this, pp, look.shape, look.cap), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(54f)))
                    cardV.addView(label(th.label, 11f, t.text, if (sel) 700 else 500).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    }, lp(top = 4f))
                    row.addView(cardV, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(6f) })
                }
                repeat(3 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = px(6f) }) }
                into.addView(row, lp(bottom = 6f))
            }
        }
        val all = Themes.CATALOG.filter { it.id !in Themes.DYNAMIC }
        group(getString(R.string.colors_dark), all.filter { it.dark })
        group(getString(R.string.colors_light), all.filter { !it.dark })
        val sys = android.os.Build.VERSION.SDK_INT >= 31
        group(getString(R.string.colors_system), Themes.CATALOG.filter { it.id in Themes.DYNAMIC }, sys, if (sys) null else getString(R.string.colors_system_off))
        // following the system: say which palette is on screen when it is the partner of the chosen one (FS2)
        val shown = shownSettings().theme
        if (store.followSystem && shown != s.theme) {
            into.addView(label("${getString(R.string.follow_system_title)}: ahora se ve «${Themes.byId(shown).label}», su pareja ${if (Themes.byId(shown).dark) "oscura" else "clara"}.",
                12f, pal.accent, 550).apply { tag = "palette-follow" }, lp(top = 2f, bottom = 4f))
        }
    }

    /** Option tiles: each one a key drawn in that shape / cap / font, its name under it; the chosen one ringed. */
    private fun <T> keyTiles(title: String, options: List<Triple<T, String, KeyTile>>, current: T, onPick: (T) -> Unit) {
        into.addView(label(title, 14f, t.textMod, 550), lp(top = 10f, bottom = 6f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val s = shownSettings()
        for ((value, name, k) in options) {
            val sel = value == current
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = cardBg(sel)
                setPadding(px(4f), px(6f), px(4f), px(6f))
                isClickable = true
                contentDescription = "$title: $name" + if (sel) ", seleccionado" else ""
                setOnClickListener { if (!sel) onPick(value) }
            }
            tile.addView(SampleKeyView(this, pal, k.shape ?: s.shape, k.cap ?: s.cap, k.legend, Fonts.get(this, k.font ?: s.font, k.weight ?: s.legendWeight.wght)),
                LinearLayout.LayoutParams(px(64f), px(54f)))
            tile.addView(label(name, 12f, t.text, if (sel) 700 else 500).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(top = 2f))
            row.addView(tile, LinearLayout.LayoutParams(px(88f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) })
        }
        into.addView(bleedRow(row), bleedLp())
    }

    /** What one option tile draws (null = the tema's current value). */
    private class KeyTile(val shape: KeyShape? = null, val cap: KeyCap? = null, val font: KeyFont? = null, val weight: Int? = null, val legend: String = "a")

    /** «Empieza con un estilo»: curated looks as miniature keyboards; a tap applies one to the tema in use, «Deshacer» puts it back. */
    private fun stylesGallery() {
        into.addView(label(getString(R.string.styles_title), 14f, t.textMod, 550), lp(top = 4f))
        into.addView(label(getString(R.string.styles_summary), 12f, t.muted), lp(bottom = 6f))
        undoStyle?.let { (id, previous) ->
            val name = styleName(id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(pal.chipFill, pal.accentGlow, 12f)
                setPadding(px(12f), px(4f), px(4f), px(4f))
                tag = "style-applied"
            }
            row.addView(label(getString(R.string.styles_applied, name), 13f, pal.accent, 600), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(getString(R.string.styles_undo), 14f, pal.accent, 700).apply {
                minHeight = px(44f); minWidth = px(64f); gravity = Gravity.CENTER
                isClickable = true
                tag = "style-undo"
                setOnClickListener { undoStyle = null; commitStore(com.resyst.vk.core.Styles.undo(store, previous)) }
            })
            into.addView(row, lp(bottom = 8f))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val now = store.activeTema.look
        for (st in com.resyst.vk.core.Styles.ALL) {
            val look = st.on(now)
            val sel = look == now
            val name = styleName(st.id)
            val cardV = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg(sel)
                setPadding(px(8f), px(8f), px(8f), px(8f))
                isClickable = true
                tag = "style.${st.id}"
                contentDescription = "$name. ${styleLine(st.id)}" + if (sel) ", en uso" else ""
                setOnClickListener {
                    val (next, previous) = com.resyst.vk.core.Styles.apply(store, st)
                    undoStyle = st.id to previous
                    commitStore(next)
                }
            }
            cardV.addView(MiniKeyboardView(this, paletteOf(look), look.shape, look.cap), LinearLayout.LayoutParams(px(132f), px(70f)))
            cardV.addView(label("${st.icon}  $name", 14f, t.text, 650).apply { maxLines = 1 }, lp(top = 6f))
            cardV.addView(label(styleLine(st.id), 12f, t.textMod).apply { maxLines = 2 }, LinearLayout.LayoutParams(px(132f), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(cardV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) })
        }
        into.addView(bleedRow(row), bleedLp(bottom = 6f))
    }

    private fun styleName(id: String) = resources.getIdentifier("style_$id", "string", packageName).takeIf { it != 0 }?.let(::getString) ?: id
    private fun styleLine(id: String) = resources.getIdentifier("style_${id}_line", "string", packageName).takeIf { it != 0 }?.let(::getString) ?: ""

    /** «Tus temas»: each tema with Renombrar / Borrar, and «Nuevo tema» (a copy of the one in use). */
    private fun temaManage() {
        into.addView(label(getString(R.string.tema_manage_title), 14f, t.textMod, 550), lp(top = 10f, bottom = 4f))
        for (tm in store.temas) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(52f)
                tag = "manage.${tm.id}"
            }
            val sel = tm.id == store.active
            row.addView(MiniKeyboardView(this, paletteOf(tm.look), tm.look.shape, tm.look.cap), LinearLayout.LayoutParams(px(58f), px(36f)).apply { marginEnd = px(10f) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label("${tm.icon}  ${tm.name}", 15f, t.text, if (sel) 700 else 550).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(label(Themes.byId(tm.look.theme).label + if (sel) " · ${getString(R.string.tema_in_use)}" else "", 12f, t.muted).apply { maxLines = 1 })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(getString(R.string.tema_rename), 13f, pal.accent, 600).apply {
                minHeight = px(48f); gravity = Gravity.CENTER; setPadding(px(10f), 0, px(10f), 0)
                isClickable = true
                contentDescription = getString(R.string.tema_rename_title, tm.name)
                setOnClickListener { renameTema(tm.id) }
            })
            row.addView(label(getString(R.string.tema_delete), 13f, if (store.temas.size > 1) t.bad else t.muted, 600).apply {
                minHeight = px(48f); gravity = Gravity.CENTER; setPadding(px(10f), 0, 0, 0)
                isClickable = true
                contentDescription = getString(R.string.tema_delete_title, tm.name)
                setOnClickListener { deleteTema(tm.id) }
            })
            into.addView(row, lp())
        }
        into.addView(pill(getString(R.string.tema_new)) { createTema() }.apply { tag = "tema-new" }, lp(top = 8f, bottom = 4f))
    }

    private fun nameField(initial: String): EditText = EditText(this).apply {
        setText(initial)
        hint = getString(R.string.tema_name_hint)
        setSingleLine()
        filters = arrayOf(android.text.InputFilter.LengthFilter(com.resyst.vk.core.TemaOps.NAME_MAX))
        setSelection(text.length)
        setPadding(px(20f), px(14f), px(20f), px(14f))
    }

    private fun createTema() {
        if (store.temas.size >= com.resyst.vk.core.TemaOps.MAX_TEMAS) {
            AlertDialog.Builder(this).setMessage(getString(R.string.tema_full, com.resyst.vk.core.TemaOps.MAX_TEMAS)).setPositiveButton("OK", null).show()
            return
        }
        val field = nameField("")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tema_new_title))
            .setMessage(getString(R.string.tema_new_message, store.activeTema.name))
            .setView(field)
            .setPositiveButton(getString(R.string.tema_create)) { _, _ ->
                val next = com.resyst.vk.core.TemaOps.create(store, field.text.toString(), "✦")
                if (next != store) commitStore(next)
            }
            .setNegativeButton(getString(R.string.tema_cancel), null)
            .show()
    }

    private fun renameTema(id: String) {
        val tm = store.byId(id) ?: return
        val field = nameField(tm.name)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tema_rename_title, tm.name))
            .setView(field)
            .setPositiveButton(getString(R.string.tema_save)) { _, _ ->
                val next = com.resyst.vk.core.TemaOps.rename(store, id, field.text.toString(), tm.icon)
                if (next != store) commitStore(next)
            }
            .setNegativeButton(getString(R.string.tema_cancel), null)
            .show()
    }

    private fun deleteTema(id: String) {
        val tm = store.byId(id) ?: return
        if (store.temas.size <= 1) {
            AlertDialog.Builder(this).setMessage(getString(R.string.tema_last)).setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tema_delete_title, tm.name))
            .setMessage(getString(R.string.tema_delete_message))
            .setPositiveButton(getString(R.string.tema_delete)) { _, _ -> commitStore(com.resyst.vk.core.TemaOps.delete(store, id)) }
            .setNegativeButton(getString(R.string.tema_cancel), null)
            .show()
    }

    /**
     * «Lo que sé de ti», in numbers: one row of counts (words, pairs, undone corrections, emails, recent emoji,
     * clipboard), each one tap from its section. Read from the same stores the sections list.
     */
    private fun knowDashboard() {
        val words = PersonalStore.words?.let { w -> Lang.values().sumOf { w.vocabCount(it) } }
        val pairs = PersonalStore.pairs?.pairCount()
        val fixes = PersonalStore.rejections?.let { it.items().size + it.strikes().size }
        val emails = PersonalStore.values?.values(FieldKind.EMAIL)?.size
        val emoji = emojiRecents().items().size
        val clip = ClipStore.history?.size
        val stats = listOf(
            Triple(words, getString(R.string.know_dash_words), Ctl.KNOW_WORDS), Triple(pairs, getString(R.string.know_dash_pairs), Ctl.KNOW_PAIRS),
            Triple(fixes, getString(R.string.know_dash_fixes), Ctl.KNOW_REJECTIONS), Triple(emails, getString(R.string.know_dash_emails), Ctl.KNOW_EMAILS),
            Triple(emoji, getString(R.string.know_dash_emoji), Ctl.KNOW_EMOJI), Triple(clip, getString(R.string.know_dash_clip), Ctl.KNOW_CLIP),
        )
        root.addView(label(getString(R.string.know_dash_title).uppercase(), 11f, t.muted, 700).apply { letterSpacing = 0.12f }, lp(bottom = 6f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((n, what, ctl) in stats) {
            val count = n?.toString() ?: "…"
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(t.bg2, t.edge, 14f)
                setPadding(px(12f), px(10f), px(12f), px(10f))
                minimumWidth = px(96f)
                isClickable = true
                tag = "dash.${ctl.name}"
                contentDescription = getString(R.string.know_dash_desc, count, what)
                setOnClickListener { revealKnow(ctl) }
            }
            tile.addView(label(count, 22f, if ((n ?: 0) > 0) pal.accent else t.muted, 700))
            tile.addView(label(what, 11f, t.textMod, 500).apply { maxLines = 2 })
            row.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = px(8f) })
        }
        root.addView(bleedRow(row), bleedLp(bottom = 10f))
    }

    /** Scrolls «Lo que sé de ti» to the section of [ctl]. */
    private fun revealKnow(ctl: Ctl) {
        scroll.post {
            val box = root.findViewWithTag<View>(ctl.name) ?: return@post
            scroll.smoothScrollTo(0, (box.top - px(8f)).coerceAtLeast(0))
        }
    }

    // ── chrome ──────────────────────────────────────────────────────────
    private fun typeface(w: Int) = Fonts.get(this, KeyFont.BRAND, w)

    private fun label(text: String, sizeSp: Float, color: Int, weight: Int = 400): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = typeface(weight)
    }

    private fun rounded(fill: Int, stroke: Int? = null, radiusDp: Float = 10f) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * dp
        if (stroke != null) setStroke(px(1f), stroke)
    }

    private fun lp(top: Float = 0f, bottom: Float = 0f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px(top); bottomMargin = px(bottom) }

    private fun header() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("✦", 30f, pal.accent, 500).apply { setPadding(0, 0, px(12f), 0) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("Resyst VK", 26f, t.text, 700))
        col.addView(label("Teclado de sistema · lo que escribes en las apps no sale del teléfono", 13f, t.muted, 450))
        row.addView(col)
        root.addView(row, lp(bottom = 18f))
    }

    /** A page: back to home, its title, and where its settings are stored. */
    private fun pageHeader(pg: SettingsPage) {
        // "‹ Ajustes" sits on the content edge (48 dp target), the title under it on the same edge.
        root.addView(label("‹  Ajustes", 15f, pal.accent, 600).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(48f)
            isClickable = true
            tag = "nav-back"
            contentDescription = "Volver a Ajustes"
            setOnClickListener { go(null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(label(pg.title, 24f, t.text, 700).apply { tag = "page-title" }, lp(top = 2f))
        val where = if (pg.scope == Scope.TEMA) {
            "Solo para el tema «${store.activeTema.name}». Los otros temas tienen su propia apariencia."
        } else {
            "Vale para todo el teclado, con cualquier tema."
        }
        root.addView(label(where, 13f, t.muted).apply { tag = "page-scope" }, lp(top = 2f, bottom = 14f))
    }

    /** One home row per page: title, what it holds, chevron. */
    private fun navRow(pg: SettingsPage) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(56f)
            isClickable = true
            tag = "page.${pg.id}"
            contentDescription = "${pg.title}. ${pg.summary}"
            setOnClickListener { go(pg.id) }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(pg.title, 15f, t.text, 550))
        if (pg.summary.isNotEmpty()) col.addView(label(pg.summary, 12f, t.muted))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label("›", 22f, pal.accent, 500).apply { setPadding(px(12f), 0, px(4f), 0) })
        root.addView(row, lp())
        root.addView(View(this).apply { setBackgroundColor(t.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)))
    }

    private fun imeState(): Pair<Boolean, Boolean> {
        val imm = getSystemService(InputMethodManager::class.java)
        val ours = imm?.enabledInputMethodList?.firstOrNull { it.packageName == packageName && it.serviceName == ResystImeService::class.java.name }
        val enabled = ours != null
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = ours != null && current == ours.id
        return enabled to selected
    }

    /** Setup: the two steps while pending; once active, one quiet line (UI-6: done ≠ a button). */
    private fun setupCard() {
        val (enabled, selected) = imeState()
        if (selected) {
            root.addView(label("✓ Resyst VK está activo", 14f, t.ok, 600).apply { tag = "setup-done" }, lp(bottom = 14f))
            return
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.bg2, pal.accent, 14f)
            setPadding(px(16f), px(14f), px(16f), px(14f))
        }
        card.addView(label("Activa el teclado en dos pasos", 16f, t.text, 650))
        card.addView(step(1, "Habilitar Resyst VK en Ajustes → Teclados", enabled) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, lp(top = 10f))
        card.addView(step(2, "Elegirlo como teclado actual", selected) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }, lp(top = 8f))
        card.addView(label("3  ·  " + getString(R.string.setup_step3), 14f, t.textMod, 500).apply { setPadding(px(12f), 0, 0, 0) }, lp(top = 8f))
        card.addView(label("Android mostrará un aviso estándar: todo teclado puede leer lo que escribes. Lo que escribes en las apps nunca sale del teléfono. ${UpdateNotice.promise(store.autoUpdateCheck)}", 12f, t.muted, 400), lp(top = 10f))
        root.addView(card, lp(bottom = 20f))
    }

    private fun step(n: Int, text: String, done: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48f)
            background = if (done) null else rounded(t.key, t.edgeHi, 10f)
            setPadding(px(12f), px(8f), px(12f), px(8f))
            isClickable = !done
            if (!done) setOnClickListener { onClick() }
            contentDescription = "Paso $n: $text. ${if (done) "Completado" else "Pendiente"}"
        }
        row.addView(label(if (done) "✓" else "$n", 15f, if (done) t.ok else pal.accent, 700).apply {
            gravity = Gravity.CENTER
            minWidth = px(28f)
        })
        row.addView(label(text, 14f, if (done) t.muted else t.text, 500).apply { setPadding(px(10f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!done) row.addView(label("›", 20f, pal.accent, 500))
        return row
    }

    /** The live keyboard with exactly what the keyboard runs: tema look + phone + mode. */
    private fun livePreview() = livePreviewInto(root)

    private fun livePreviewInto(parent: LinearLayout) {
        val s = shownSettings()
        val kv = KeyboardView(this)
        kv.setStyle(s, pal)
        kv.setSuggestions(if (s.suggest) listOf("está", "estaba", "estar") else emptyList())
        val spec = LayoutSpec(s.lang, s.effectiveTopRow, emojiKey = s.emojiKey)
        kv.setKeyboard(KeyboardLayouts.rows(Layer.LETTERS, spec), s.baseRowCount, Layer.LETTERS)
        kv.setShift(ShiftState.OFF)
        kv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        kv.contentDescription = "Vista previa del teclado"
        val frame = FrameLayout(this).apply {
            background = rounded(t.bg, t.edge, 14f)
            setPadding(px(1f), px(1f), px(1f), px(1f))
            clipToOutline = true
        }
        frame.addView(kv)
        preview = kv
        parent.addView(frame, lp(top = 6f, bottom = 6f))
        val mode = if (store.mode == Mode.NONE) "" else " · modo ${store.mode.label}"
        parent.addView(label("Vista previa · ${store.activeTema.name} (${Themes.byId(s.theme).label})$mode", 11f, t.muted, 500).apply {
            gravity = Gravity.END
            tag = "preview-caption"
        }, lp(bottom = 6f))
    }

    private fun section(title: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("✦ ", 13f, pal.accent, 600))
        row.addView(label(title.uppercase(), 12f, pal.accent, 700).apply { letterSpacing = 0.14f })
        root.addView(row, lp(top = 22f, bottom = 6f))
        root.addView(View(this).apply { setBackgroundColor(t.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)).apply { bottomMargin = px(8f) })
    }

    /**
     * A horizontal row that bleeds to the right screen edge (the root's 18 dp padding), so a
     * partly visible last item reads as "scroll for more", not as a clipped layout.
     */
    private fun bleedRow(row: LinearLayout): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(0, 0, px(18f), 0)
        addView(row)
    }

    private fun bleedLp(bottom: Float = 4f) = lp(bottom = bottom).apply { marginEnd = -px(18f) }

    private fun <T> choice(title: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
        into.addView(label(title, 14f, t.textMod, 550), lp(top = 8f, bottom = 6f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, name) in options) {
            val sel = value == current
            row.addView(label(name, 14f, if (sel) pal.accentInk else t.text, if (sel) 650 else 500).apply {
                gravity = Gravity.CENTER
                minHeight = px(40f)
                setPadding(px(14f), px(8f), px(14f), px(8f))
                background = rounded(if (sel) pal.accent else t.key, if (sel) pal.accent else t.edgeHi, 20f)
                isClickable = true
                setOnClickListener { if (!sel) onPick(value) }
                contentDescription = "$title: $name" + if (sel) ", seleccionado" else ""
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) })
        }
        into.addView(bleedRow(row), bleedLp())
    }

    private fun accentRow(s: KbSettings) {
        into.addView(label(Ctl.ACCENT.label, 14f, t.textMod, 550), lp(top = 8f, bottom = 6f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val options = listOf<Pair<Int?, String>>(null to getString(R.string.accent_theme)) + Themes.ACCENTS.map { it.first to it.second } +
            (if (moreAccents || (s.accent != null && Themes.ACCENTS.none { it.first == s.accent })) Themes.MORE_ACCENTS.map { it to ColorMath.toHex(it) } else emptyList())
        for ((c, name) in options) {
            val sel = s.accent == c
            val shown = Palette.of(s.theme, c).accent
            val sw = TextView(this).apply {
                text = if (c == null) "T" else ""
                gravity = Gravity.CENTER
                setTextColor(Palette.of(s.theme, c).accentInk)
                typeface = typeface(700)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(shown)
                    setStroke(px(if (sel) 3f else 1f), if (sel) t.text else t.edge)
                }
                isClickable = true
                setOnClickListener { if (!sel) commit { it.copy(accent = c) } }
                contentDescription = "Acento $name" + if (sel) ", seleccionado" else ""
            }
            row.addView(sw, LinearLayout.LayoutParams(px(44f), px(44f)).apply { marginEnd = px(8f) })
        }
        into.addView(bleedRow(row), bleedLp())
        if (!moreAccents) link("${getString(R.string.accent_more)} ›") { moreAccents = true; rerender() }
        into.addView(label("«T» = " + getString(R.string.accent_theme) + ". " + getString(R.string.accent_hint), 11f, t.muted), lp(top = 2f, bottom = 4f))
    }

    private fun toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(52f)
            isClickable = true
            setOnClickListener { onChange(!value) }
            contentDescription = "$title, ${if (value) "activado" else "desactivado"}"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 15f, t.text, 500))
        if (sub != null) col.addView(label(sub, 12f, t.muted))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(12f) })
        // custom switch: track + knob, theme colored
        val track = FrameLayout(this).apply {
            background = rounded(if (value) pal.accent else t.keyMod, if (value) pal.accent else t.edgeHi, 14f)
        }
        val knob = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (value) pal.accentInk else t.muted) }
        }
        track.addView(knob, FrameLayout.LayoutParams(px(20f), px(20f)).apply {
            gravity = Gravity.CENTER_VERTICAL or if (value) Gravity.END else Gravity.START
            marginStart = px(4f); marginEnd = px(4f)
        })
        row.addView(track, LinearLayout.LayoutParams(px(48f), px(28f)))
        into.addView(row, lp())
    }

    private fun slider(title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, step: Int = 5, onChange: (Int) -> Unit) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label(title, 14f, t.textMod, 550), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueLabel = label(fmt(value), 14f, pal.accent, 650)
        head.addView(valueLabel)
        into.addView(head, lp(top = 10f))
        val bar = SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value - min) / step
            progressTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(pal.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(t.edgeHi)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { valueLabel.text = fmt(min + p * step) }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) { onChange(min + (sb?.progress ?: 0) * step) }
            })
        }
        into.addView(bar, lp().apply { height = px(44f) })
        // UI-6: the range is visible, not guessed
        val ends = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ends.addView(label(fmt(min), 11f, t.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ends.addView(label(fmt(max), 11f, t.muted))
        into.addView(ends, lp(bottom = 4f))
    }

    private fun link(text: String, onClick: () -> Unit) {
        into.addView(label(text, 13f, pal.accent, 600).apply {
            minHeight = px(44f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { onClick() }
        }, lp())
    }

    private fun pill(text: String, onClick: () -> Unit) = label(text, 14f, pal.accentInk, 650).apply {
        gravity = Gravity.CENTER
        minHeight = px(46f)
        setPadding(px(14f), px(10f), px(14f), px(10f))
        background = rounded(pal.accent, null, 23f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tryField() {
        val et = EditText(this).apply {
            hint = "Escribe aquí para probar: ¿ñandú? ¡acción!"
            setHintTextColor(t.muted)
            setTextColor(t.text)
            typeface = typeface(450)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            background = rounded(t.bg2, t.edgeHi, 12f)
            setPadding(px(14f), px(12f), px(14f), px(12f))
            tag = "try-field"
        }
        root.addView(et, lp(bottom = 8f))
    }

    private fun footer() {
        root.addView(label("✦ Resyst · DM Sans (OFL) · léxico FrequencyWords (CC BY-SA 4.0) · diccionario español RLA-ES de Hunspell (se usa bajo la GPL; lo ofrece como GPL, LGPL o MPL) y SCOWL (en_US)", 11f, t.muted).apply { gravity = Gravity.CENTER }, lp(top = 18f))
        // r14 (PS5): the two bundled key fonts (assets/fonts/, their OFL texts beside them)
        root.addView(label(getString(R.string.credits_fonts), 11f, t.muted).apply { gravity = Gravity.CENTER; tag = "credits-fonts" }, lp(top = 6f))
        // r13f (offers, LC1): the context-offer model's sources, next to the dictionaries (assets/context/NOTICE.txt)
        root.addView(label("Sugerencias por contexto: modelo construido con textos de Wikipedia contributors, Wikipedia, la enciclopedia libre (CC BY-SA 4.0) y de Tatoeba contributors (CC BY 2.0 FR); el modelo se distribuye bajo la GPL 3 (solo versión 3)", 11f, t.muted).apply {
            gravity = Gravity.CENTER
            tag = "credits-context"
        }, lp(top = 6f))
        // r13c (LI1, LI5): the app's own license + source. A tap hands the page to the browser: the
        // browser makes that request, not the keyboard, so the "Libro de conexiones" stays as it is.
        root.addView(label("Resyst VK es software libre bajo la GPL 3 · código fuente en GitHub (se abre en el navegador)", 11f, pal.accent, 550).apply {
            gravity = Gravity.CENTER
            minHeight = px(48f)
            isClickable = true
            tag = "source-link"
            contentDescription = "Resyst VK es software libre bajo la GPL 3. Código fuente en GitHub. Abre el navegador"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) { /* no browser: nothing to open */ }
            }
        }, lp(top = 2f))
        root.addView(label("", 1f, t.bg).apply { typeface = Typeface.DEFAULT }, lp(bottom = 40f))
    }

    companion object {
        /** r9: open on a section (the keyboard's update chip). */
        /** r13c (LI1): where the source of this GPL-3.0-or-later app lives. */
        const val SOURCE_URL = "https://github.com/ProDrifterDK/resyst-vk-android"
        const val EXTRA_SECTION = "com.resyst.vk.section"
        const val SECTION_UPDATE = "update"
        /** r10: open on a [SettingsPage.id] (quick panel → "Ajustes", privacy surfaces…). */
        const val EXTRA_PAGE = "com.resyst.vk.page"
        private const val STATE_PAGE = "page"
        /** r10 (F-4): the "Lo que sé de ti" page. */
        const val PAGE_KNOW = "datos"
        /** r14 (PS4): the «Apariencia» page (the quick panel's «Personalizar»). */
        const val PAGE_LOOK = "apariencia"
        private const val KNOW_WORDS_STEP = 25
        private const val KNOW_PAIRS_STEP = 15
        /** Pages whose controls a mode can override (they get the "modo encendido" banner). */
        private val MODE_AFFECTED = setOf("apariencia", "teclas", "escritura")
    }
}
