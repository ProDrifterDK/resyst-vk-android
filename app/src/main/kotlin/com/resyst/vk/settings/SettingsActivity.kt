package com.resyst.vk.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import com.resyst.vk.core.ClipSettings
import com.resyst.vk.core.ClipboardHistory
import com.resyst.vk.core.ConnectionLog
import com.resyst.vk.core.EmojiRecents
import com.resyst.vk.core.FieldKind
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
import com.resyst.vk.core.Lang
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
import com.resyst.vk.ime.Fonts
import com.resyst.vk.ime.HapticPlayer
import com.resyst.vk.ime.KeyboardView
import com.resyst.vk.ime.PersonalStore
import com.resyst.vk.ime.ResystImeService
import com.resyst.vk.ime.SubtypeSync
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
        render()
        maybeAutoInstall()
    }

    private val updaterListener: () -> Unit = {
        rerender()
        maybeAutoInstall()
    }

    private val clipListener: () -> Unit = { rerender() }

    override fun onPause() {
        ClipStore.listeners -= clipListener
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

    private val pal get() = store.settings.let { Palette.of(it.theme, it.accent) }
    private val t get() = pal.theme

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
        section("Tema y modo")
        ctl(Ctl.TEMA)
        ctl(Ctl.MODE)
        livePreview()
        section("Ajustes rápidos")
        for (c in SettingsIA.HOME) if (c != Ctl.TEMA && c != Ctl.MODE) ctl(c)
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
        if (pg.id == PAGE_KNOW) knowIntro()
        val hinted = HashSet<Ctl>()
        for (row in SettingsIA.rows(pg) { on(it) }) {
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
            Ctl.THEME -> choice(ctl.label, Themes.ALL.map { it.id to it.label }, s.theme) { v -> commit { it.copy(theme = v) } }
            Ctl.DAY_NIGHT -> dayNightPill(s)
            Ctl.ACCENT -> accentRow(s)
            Ctl.SHAPE -> choice(ctl.label, listOf(KeyShape.SQUARE to "Recta", KeyShape.SOFT to "Suave", KeyShape.ROUND to "Redonda"), s.shape) { v -> commit { it.copy(shape = v) } }
            Ctl.CAP -> choice(ctl.label, listOf(KeyCap.RAISED to "Relieve", KeyCap.FLAT to "Plana", KeyCap.OUTLINE to "Contorno"), s.cap) { v -> commit { it.copy(cap = v) } }
            Ctl.FONT -> choice(ctl.label, listOf(KeyFont.BRAND to "DM Sans", KeyFont.TECH to "Mono", KeyFont.HUMAN to "Serif"), s.font) { v -> commit { it.copy(font = v) } }
            Ctl.DENSITY -> choice(ctl.label, listOf(Density.TIGHT to "Compacta", Density.NORMAL to "Normal", Density.AIRY to "Aireada"), s.density) { v -> commit { it.copy(density = v) } }
            Ctl.HEIGHT -> slider(ctl.label, 80, 130, (s.heightScale * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(heightScale = v / 100f) } }
            Ctl.SUB_LEGENDS -> toggle(ctl.label, "Dígitos sobre la fila superior y punto de variantes", s.subLegends) { v -> commit { it.copy(subLegends = v) } }

            Ctl.LANG -> choice(ctl.label, listOf(Lang.ES to "Español", Lang.EN to "English"), s.lang) { v -> commit { it.copy(lang = v) } }
            Ctl.SYSTEM_LANGS -> link("${ctl.label} ›") { SubtypeSync(this).openSubtypeSettings() }
            Ctl.HIDE_TOP_ROW -> toggle(ctl.label, "Quita la fila de acentos / números sobre las letras", s.hideTopRow) { v -> commit { it.copy(hideTopRow = v) } }
            Ctl.TOP_ROW -> choice(ctl.label, listOf(TopRow.ACCENTS to "Acentos", TopRow.NUMBERS to "Números"), s.topRow) { v -> commit { it.copy(topRow = v) } }
            Ctl.EMOJI_KEY -> toggle(ctl.label, "Junto a la coma · abre el panel de emojis con recientes", s.emojiKey) { v -> commit { it.copy(emojiKey = v) } }
            Ctl.DAY_NIGHT_CHIP -> toggle(ctl.label, "Sol / luna en la barra, además del panel ⚙ · un toque cambia entre claro y oscuro", s.dayNightChip) { v -> commit { it.copy(dayNightChip = v) } }
            Ctl.ONE_HANDED -> choice(ctl.label, listOf(OneHand.OFF to "Apagado", OneHand.RIGHT to "Derecha", OneHand.LEFT to "Izquierda"), s.oneHanded) { v -> commit { it.copy(oneHanded = v) } }
            Ctl.POPUPS -> toggle(ctl.label, "Burbuja sobre la tecla al pulsar · nunca en contraseñas", s.popups) { v -> commit { it.copy(popups = v) } }
            Ctl.LONG_PRESS -> slider(ctl.label, 150, 900, s.longPressMs, { "$it ms" }, step = 25) { v -> commit { it.copy(longPressMs = v) } }

            Ctl.SUGGEST -> toggle(ctl.label, "Léxico offline por frecuencia (sin red)", s.suggest) { v -> commit { it.copy(suggest = v) } }
            Ctl.SPACE_CORRECTS -> toggle(ctl.label, "Solo si la corrección es segura · ⌫ la deshace", s.spaceCorrects) { v -> commit { it.copy(spaceCorrects = v) } }
            Ctl.PERSONAL -> toggle(ctl.label, "Aprende tus palabras y correos frecuentes · solo en este teléfono", s.personal) { v -> commit { it.copy(personal = v) } }
            Ctl.AUTO_CAP -> toggle(ctl.label, "Al inicio de frase", s.autoCap) { v -> commit { it.copy(autoCap = v) } }
            Ctl.DOUBLE_SPACE -> toggle(ctl.label, "Dos espacios seguidos escriben «. »", s.doubleSpace) { v -> commit { it.copy(doubleSpace = v) } }
            Ctl.PROFANITY_FILTER -> toggle(ctl.label, "El teclado no propone groserías · lo que tú escribes no se toca, y si una la usas seguido vuelve a aparecer", s.profanityFilter) { v -> commit { it.copy(profanityFilter = v) } }

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
            Ctl.CLEAR_CLIP -> clearClipRow()
            Ctl.FORGET_LEARNED -> forgetRow()

            Ctl.KNOW_WORDS -> knowWords()
            Ctl.KNOW_EMAILS -> knowEmails()
            Ctl.KNOW_EMOJI -> knowEmoji()
            Ctl.KNOW_CLIP -> knowClip()

            Ctl.UPDATES -> {
                into.addView(label(ctl.label, 14f, t.textMod, 550), lp(top = 4f))
                updateSection()
            }
            Ctl.UPDATE_AUTO -> toggle(ctl.label, "Una consulta cuando el teclado arranca · si hay versión nueva, aparece aquí y en el teclado", store.autoUpdateCheck) { v ->
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
            val pp = Palette.of(tm.look.theme, tm.look.accent)
            // Each card wears its own tema; the one in use gets a 2 dp accent ring + a dot (one signal).
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = px(76f)
                background = rounded(pp.theme.bg2, pp.theme.edgeHi, 12f).apply { if (sel) setStroke(px(2f), pp.accent) }
                setPadding(px(8f), px(8f), px(8f), px(8f))
                isClickable = true
                tag = "tema.${tm.id}"
                setOnClickListener { if (!sel) commitStore(store.withTema(tm.id)) }
                contentDescription = "Tema ${tm.name}, ${Themes.byId(tm.look.theme).label}" + if (sel) ", en uso" else ""
            }
            card.addView(label(tm.name + if (sel) "  ●" else "", 14f, pp.theme.text, if (sel) 700 else 550).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(label(Themes.byId(tm.look.theme).label, 11f, pp.theme.textMod, 500).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, lp(top = 2f))
            row.addView(card, LinearLayout.LayoutParams(px(118f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) })
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

    private fun dayNightPill(s: KbSettings) {
        val dark = Themes.byId(s.theme).dark
        val toLabel = Themes.byId(DayNight.toggle(s).theme).label
        into.addView(label(Ctl.DAY_NIGHT.label, 14f, t.textMod, 550), lp(top = 8f))
        into.addView(pill(if (dark) "☀  Modo día · $toLabel" else "☾  Modo noche · $toLabel") {
            commitStore(store.updateLook(store.activeTema.id) { DayNight.toggle(it) })
        }.apply { tag = "daynight"; contentDescription = "Cambiar a modo ${if (dark) "día" else "noche"}: $toLabel" }, lp(top = 8f, bottom = 4f))
    }

    // ── updates (the app's only network use: these buttons + one check at keyboard start) ──

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

    /** "Comprobado al iniciar el teclado · hace 3 min" — where the shown state came from. */
    private fun checkedWhen(): String {
        val mins = ((System.currentTimeMillis() - Updater.checkedAt) / 60_000).coerceAtLeast(0)
        val ago = when {
            mins < 1 -> "ahora mismo"
            mins < 60 -> "hace $mins min"
            mins < 48 * 60 -> "hace ${mins / 60} h"
            else -> "hace ${mins / (24 * 60)} días"
        }
        return (if (Updater.checkedAuto) "Comprobado al iniciar el teclado" else "Comprobado") + " · $ago"
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

    /** Wipes the learned words + remembered values (shared by every tema), after a confirm. */
    private fun forgetRow() {
        val w = PersonalStore.words
        val v = PersonalStore.values
        if (w == null || v == null) {
            dangerRow(Ctl.FORGET_LEARNED.label, "Cargando…", enabled = false, onClick = {})
            return
        }
        val words = Lang.values().sumOf { w.vocabCount(it) }
        val emails = v.suggest(com.resyst.vk.core.FieldKind.EMAIL, "", Int.MAX_VALUE).size
        val empty = words == 0 && emails == 0
        val summary = if (empty) "Nada aprendido todavía · nada sale del teléfono" else "$words palabras · $emails correos aprendidos · nada sale del teléfono"
        dangerRow(Ctl.FORGET_LEARNED.label, summary, enabled = !empty, onClick = { confirmForget() })
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar lo aprendido?")
            .setMessage("Se olvidan las palabras y los correos que el teclado aprendió de ti, en todos los temas. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                PersonalStore.clear(this)
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
        card.addView(label("Vive solo en este teléfono, fuera de las copias de seguridad, y nunca se envía a ningún lado. Toca × para borrar una cosa; nada más cambia.", 12f, t.textMod), lp(top = 4f))
        root.addView(card, lp(bottom = 10f))
    }

    /** A listed item with its own delete target (48 dp), named for TalkBack. */
    private fun knowRow(text: String, meta: String?, desc: String, tagId: String, onDelete: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48f)
            tag = "know.$tagId"
        }
        row.addView(label(text, 15f, t.text, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (meta != null) row.addView(label(meta, 12f, t.muted, 500).apply { setPadding(px(8f), 0, px(4f), 0) })
        row.addView(label("×", 22f, t.bad, 500).apply {
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = "Borrar $desc"
            tag = "forget.$tagId"
            setOnClickListener { onDelete() }
        }, LinearLayout.LayoutParams(px(48f), px(48f)))
        into.addView(row, lp())
        into.addView(View(this).apply { setBackgroundColor(t.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)))
    }

    private fun knowHead(title: String, summary: String) {
        into.addView(label(title, 14f, t.textMod, 600), lp(top = 10f))
        into.addView(label(summary, 12f, t.muted).apply { tag = "know-summary" }, lp(bottom = 4f))
    }

    private fun knowWords() {
        val w = PersonalStore.words
        if (w == null) { knowHead(Ctl.KNOW_WORDS.label, "Cargando…"); return }
        val total = Lang.values().sumOf { w.vocabCount(it) }
        knowHead(Ctl.KNOW_WORDS.label, if (total == 0) "Ninguna todavía. Las aprende al escribir, solo en campos normales (nunca en contraseñas ni en modo incógnito)." else "$total palabras · con cuántas veces las usaste")
        for (lang in Lang.values()) {
            val n = w.vocabCount(lang)
            if (n == 0) continue
            into.addView(label(if (lang == Lang.ES) "Español · $n" else "English · $n", 12f, pal.accent, 650).apply { letterSpacing = 0.04f }, lp(top = 6f))
            for (e in w.words(lang, wordsShown)) {
                knowRow(e.form, "×${e.count}", "la palabra ${e.form}", "${lang.code}.${e.key}") {
                    if (w.forget(lang, e.key)) { PersonalStore.changed(); rerender() }
                }
            }
            if (n > wordsShown) link("Mostrar más (${n - wordsShown}) ›") { wordsShown += KNOW_WORDS_STEP * 4; rerender() }
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

    private fun knowEmoji() {
        val r = runCatching { EmojiRecents.decode(emojiFile.takeIf { it.exists() }?.readText()) }.getOrDefault(EmojiRecents())
        val list = r.items()
        knowHead(Ctl.KNOW_EMOJI.label, if (list.isEmpty()) "Ninguno. Nunca se guardan los de campos en modo incógnito." else "${list.size} · los últimos que usaste, para el panel de emojis")
        if (list.isEmpty()) return
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
        into.addView(label("Solo para comprobar o descargar actualizaciones de Resyst VK en kv.resyst.cl. Nunca se envía lo que escribes, lo que copias ni lo aprendido; ninguna otra parte de la app usa la red.", 12f, t.muted), lp(top = 2f, bottom = 6f))
        val fmt = java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale("es", "CL"))
        for ((i, e) in log.recent().withIndex()) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = px(48f)
                gravity = Gravity.CENTER_VERTICAL
                tag = "connection.$i"
                isFocusable = true
                contentDescription = "${e.what.label}, ${fmt.format(java.util.Date(e.at))}, ${e.why.label}. ${e.outcome}"
            }
            col.addView(label("${e.what.label} · ${e.why.label}", 14f, t.text, 550))
            col.addView(label("${fmt.format(java.util.Date(e.at))} · ${e.outcome}", 12f, t.muted))
            into.addView(col, lp())
        }
        if (log.total > log.recent().size) {
            into.addView(label("Se muestran las ${log.recent().size} más recientes.", 11f, t.muted), lp(top = 4f))
        }
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
        col.addView(label("Teclado de sistema · lo que escribes no sale del teléfono", 13f, t.muted, 450))
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
        card.addView(label("Android mostrará un aviso estándar: todo teclado puede leer lo que escribes. Lo que escribes nunca sale del teléfono. ${UpdateNotice.promise(store.autoUpdateCheck)}", 12f, t.muted, 400), lp(top = 10f))
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
    private fun livePreview() {
        val s = store.settings
        val kv = KeyboardView(this)
        kv.setStyle(s, Palette.of(s.theme, s.accent))
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
        root.addView(frame, lp(top = 6f, bottom = 6f))
        val mode = if (store.mode == Mode.NONE) "" else " · modo ${store.mode.label}"
        root.addView(label("Vista previa · ${store.activeTema.name} (${Themes.byId(s.theme).label})$mode", 11f, t.muted, 500).apply {
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
        val options = listOf<Pair<Int?, String>>(null to "Del tema") + Themes.ACCENTS.map { it.first to it.second }
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
        into.addView(label("«T» = el acento propio del tema. Cualquier acento se ajusta solo para leerse bien sobre las teclas.", 11f, t.muted), lp(top = 2f, bottom = 4f))
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
        root.addView(label("✦ Resyst · DM Sans (OFL) · léxico FrequencyWords (MIT)", 11f, t.muted).apply { gravity = Gravity.CENTER }, lp(top = 18f))
        root.addView(label("", 1f, t.bg).apply { typeface = Typeface.DEFAULT }, lp(bottom = 40f))
    }

    companion object {
        /** r9: open on a section (the keyboard's update chip). */
        const val EXTRA_SECTION = "com.resyst.vk.section"
        const val SECTION_UPDATE = "update"
        /** r10: open on a [SettingsPage.id] (quick panel → "Ajustes", privacy surfaces…). */
        const val EXTRA_PAGE = "com.resyst.vk.page"
        private const val STATE_PAGE = "page"
        /** r10 (F-4): the "Lo que sé de ti" page. */
        const val PAGE_KNOW = "datos"
        private const val KNOW_WORDS_STEP = 25
        /** Pages whose controls a mode can override (they get the "modo encendido" banner). */
        private val MODE_AFFECTED = setOf("apariencia", "teclas", "escritura")
    }
}
