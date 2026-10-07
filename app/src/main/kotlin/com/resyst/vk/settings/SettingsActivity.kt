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
import com.resyst.vk.core.ColorMath
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
import com.resyst.vk.core.Palette
import com.resyst.vk.core.ProfileStore
import com.resyst.vk.core.ShiftState
import com.resyst.vk.core.SoundPack
import com.resyst.vk.core.Themes
import com.resyst.vk.core.TopRow
import com.resyst.vk.core.UpdateDecision
import com.resyst.vk.core.ClipSettings
import com.resyst.vk.core.ClipboardHistory
import com.resyst.vk.ime.ClipStore
import com.resyst.vk.ime.Fonts
import com.resyst.vk.ime.HapticPlayer
import com.resyst.vk.ime.KeyboardView
import com.resyst.vk.ime.PersonalStore
import com.resyst.vk.ime.ResystImeService
import com.resyst.vk.ime.SubtypeSync
import kotlin.math.roundToInt

/**
 * Setup + profiles screen. Built in code (no XML layouts) so every color comes from the
 * same palette the keyboard uses: the screen re-skins itself with the profile being edited.
 */
class SettingsActivity : Activity() {

    private lateinit var repo: SettingsRepo
    private lateinit var store: ProfileStore
    private var editing = "noche"
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var preview: KeyboardView? = null
    private var haptics: HapticPlayer? = null

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepo(this)
        store = repo.load()
        editing = store.active
        PersonalStore.init(this)
        ClipStore.init(this)
        scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18f), px(22f), px(18f), px(28f))
        }
        scroll.addView(root)
        setContentView(scroll)
        fitSystemBars()
        render()
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
        Updater.onChange = {
            val y = scroll.scrollY
            render()
            scroll.post { scroll.scrollTo(0, y) }
            maybeAutoInstall()
        }
        Updater.resume(this)
        ClipStore.listeners += clipListener
        render()
        maybeAutoInstall()
    }

    private val clipListener: () -> Unit = {
        val y = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    override fun onPause() {
        ClipStore.listeners -= clipListener
        Updater.onChange = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            render() // IME picker closed → refresh the setup status
            maybeAutoInstall() // the download finished while a dialog/notification shade had focus
        }
    }

    private val pal get() = Palette.of(store.byId(editing)?.settings?.theme, store.byId(editing)?.settings?.accent)
    private val t get() = pal.theme

    private fun commit(f: (KbSettings) -> KbSettings) {
        store = store.update(editing, f)
        repo.save(store)
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

        header()
        setupCard()
        profileTabs()
        val s = store.byId(editing)?.settings ?: KbSettings()
        livePreview(s)
        section("Apariencia")
        choice("Tema", Themes.ALL.map { it.id to it.label }, s.theme) { v -> commit { it.copy(theme = v) } }
        val dark = Themes.byId(s.theme).dark
        val toLabel = Themes.byId(DayNight.toggle(s).theme).label
        root.addView(pill(if (dark) "☀  Modo día · $toLabel" else "☾  Modo noche · $toLabel") { commit { DayNight.toggle(it) } }
            .apply { tag = "daynight"; contentDescription = "Cambiar a modo ${if (dark) "día" else "noche"}: $toLabel" }, lp(top = 8f))
        toggle("Botón día / noche en el teclado", "Sol / luna en la barra superior: un toque cambia el tema", s.dayNightChip) { v -> commit { it.copy(dayNightChip = v) } }
        accentRow(s)
        choice("Forma", listOf(KeyShape.SQUARE to "Recta", KeyShape.SOFT to "Suave", KeyShape.ROUND to "Redonda"), s.shape) { v -> commit { it.copy(shape = v) } }
        choice("Tecla", listOf(KeyCap.RAISED to "Relieve", KeyCap.FLAT to "Plana", KeyCap.OUTLINE to "Contorno"), s.cap) { v -> commit { it.copy(cap = v) } }
        choice("Fuente", listOf(KeyFont.BRAND to "DM Sans", KeyFont.TECH to "Mono", KeyFont.HUMAN to "Serif"), s.font) { v -> commit { it.copy(font = v) } }
        choice("Densidad", listOf(Density.TIGHT to "Compacta", Density.NORMAL to "Normal", Density.AIRY to "Aireada"), s.density) { v -> commit { it.copy(density = v) } }
        slider("Altura", 80, 130, (s.heightScale * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(heightScale = v / 100f) } }
        toggle("Leyendas secundarias", "Dígitos sobre la fila superior y punto de variantes", s.subLegends) { v -> commit { it.copy(subLegends = v) } }

        section("Escritura")
        choice("Idioma", listOf(Lang.ES to "Español", Lang.EN to "English"), s.lang) { v -> commit { it.copy(lang = v) } }
        link("Idiomas en el selector de Android…") { SubtypeSync(this).openSubtypeSettings() }
        toggle("Ocultar fila de caracteres especiales", "Quita la fila de acentos / números sobre las letras", s.hideTopRow) { v -> commit { it.copy(hideTopRow = v) } }
        if (!s.hideTopRow) {
            choice("Fila superior", listOf(TopRow.ACCENTS to "Acentos", TopRow.NUMBERS to "Números"), s.topRow) { v -> commit { it.copy(topRow = v) } }
        }
        toggle("Tecla de emojis", "Junto a la coma · abre el panel de emojis con recientes", s.emojiKey) { v -> commit { it.copy(emojiKey = v) } }
        toggle("Sugerencias", "Léxico offline por frecuencia (sin red)", s.suggest) { v -> commit { it.copy(suggest = v) } }
        if (s.suggest) {
            toggle("El espacio aplica la corrección", "Solo si la corrección es segura · ⌫ la deshace", s.spaceCorrects) { v -> commit { it.copy(spaceCorrects = v) } }
            toggle("Sugerencias personales", "Aprende tus palabras y correos frecuentes · solo en este teléfono", s.personal) { v -> commit { it.copy(personal = v) } }
        }
        forgetRow()
        toggle("Mayúscula automática", "Al inicio de frase", s.autoCap) { v -> commit { it.copy(autoCap = v) } }
        toggle("Doble espacio = punto", null, s.doubleSpace) { v -> commit { it.copy(doubleSpace = v) } }
        toggle("Vista previa de tecla", "Burbuja sobre la tecla al pulsar", s.popups) { v -> commit { it.copy(popups = v) } }
        slider("Pulsación larga", 150, 900, s.longPressMs, { "$it ms" }, step = 25) { v -> commit { it.copy(longPressMs = v) } }

        section("Portapapeles")
        clipboardSection()

        section("Respuesta")
        toggle("Vibración", null, s.haptics) { v -> commit { it.copy(haptics = v) } }
        if (s.haptics) {
            choice("Intensidad", listOf(HapticStrength.LOW to "Suave", HapticStrength.MEDIUM to "Media", HapticStrength.HIGH to "Fuerte"), s.hapticStrength) { v ->
                commit { it.copy(hapticStrength = v) }
                previewHaptic(v)
            }
        }
        toggle("Sonido de tecla", "Sintetizado en el dispositivo, sin archivos", s.sound) { v -> commit { it.copy(sound = v) } }
        if (s.sound) {
            choice("Pack", listOf(SoundPack.CLICK to "Click", SoundPack.THOCK to "Thock", SoundPack.TYPE to "Máquina", SoundPack.BUBBLE to "Burbuja"), s.soundPack) { v -> commit { it.copy(soundPack = v) } }
            slider("Volumen", 0, 100, (s.volume * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(volume = v / 100f) } }
        }

        section("Probar")
        tryField()

        section("Actualización")
        updateSection()
        footer()
    }

    // ── updates (the app's only network use, and only from these buttons) ──

    private fun updateSection() {
        val inst = Updater.installed(this)
        root.addView(label("Versión instalada: ${inst.versionName}", 14f, t.textMod, 550).apply { tag = "update-installed" }, lp(top = 4f))
        root.addView(label(PROMISE, 12f, t.muted), lp(top = 2f, bottom = 10f))
        when (val st = Updater.state) {
            Updater.State.Idle -> checkButton("Buscar actualizaciones")
            Updater.State.Checking -> status("Buscando actualizaciones…")
            is Updater.State.Checked -> when (val d = st.decision) {
                is UpdateDecision.UpToDate -> {
                    status("✓ Ya tienes la última versión (${d.latest}).", t.ok)
                    checkButton("Buscar de nuevo", quiet = true)
                }
                is UpdateDecision.Available -> {
                    val r = d.release
                    status("Nueva versión disponible: ${r.version}", pal.accent)
                    val facts = listOfNotNull(r.size, r.date?.take(10), r.minAndroid?.let { "Android $it o superior" })
                    if (facts.isNotEmpty()) status(facts.joinToString(" · "), t.muted, 12f)
                    root.addView(pill("Descargar e instalar ${r.version}") { confirmDownload(r) }.apply { tag = "update-download" }, lp(top = 8f))
                    link("Ahora no") { Updater.dismiss() }
                }
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
                root.addView(pill("Instalar ${st.release.version}") { install(st) }.apply { tag = "update-install" }, lp(top = 8f))
            }
            is Updater.State.Failed -> {
                status(st.message, t.bad)
                checkButton("Reintentar", quiet = true)
            }
        }
    }

    private fun status(text: String, color: Int = t.text, size: Float = 14f) {
        root.addView(label(text, size, color, 500).apply { tag = "update-status" }, lp(top = 4f))
    }

    private fun checkButton(text: String, quiet: Boolean = false) {
        if (quiet) link(text) { Updater.check(this) }
        else root.addView(pill(text) { Updater.check(this) }.apply { tag = "update-check" }, lp(top = 4f))
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

    /** Wipes the learned words + remembered values (shared by all profiles), after a confirm. */
    private fun forgetRow() {
        val w = PersonalStore.words
        val v = PersonalStore.values
        val summary = if (w == null || v == null) "Cargando…" else {
            val words = com.resyst.vk.core.Lang.values().sumOf { w.vocabCount(it) }
            val emails = v.suggest(com.resyst.vk.core.FieldKind.EMAIL, "", Int.MAX_VALUE).size
            "$words palabras · $emails correos aprendidos · nada sale del teléfono"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = px(52f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "Borrar lo aprendido. $summary"
            setOnClickListener { confirmForget() }
        }
        row.addView(label("Borrar lo aprendido", 15f, t.bad, 600))
        row.addView(label(summary, 12f, t.muted))
        root.addView(row, lp())
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar lo aprendido?")
            .setMessage("Se olvidan las palabras y los correos que el teclado aprendió de ti, en todos los perfiles. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                PersonalStore.clear(this)
                val y = scroll.scrollY
                render()
                scroll.post { scroll.scrollTo(0, y) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── clipboard (device-wide, not per profile) ────────────────────────
    private fun commitClip(f: (ClipSettings) -> ClipSettings) {
        store = store.copy(clip = f(store.clip))
        repo.save(store)
        val y = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun clipboardSection() {
        val c = store.clip
        toggle("Historial del portapapeles", "Guarda lo que copias (hasta ${ClipboardHistory.MAX_ITEMS}) · nunca en campos de contraseña · solo en este teléfono", c.history) { v ->
            commitClip { it.copy(history = v) }
        }
        if (c.history) {
            toggle("Purgar tras 1 hora", "Borra solo lo no fijado que copiaste hace más de una hora", c.purgeHour) { v ->
                commitClip { it.copy(purgeHour = v) }
            }
        }
        val h = ClipStore.history
        val summary = when {
            h == null -> "Cargando…"
            h.isEmpty() -> "Vacío · nada sale del teléfono"
            else -> "${h.size} elementos" + (if (h.pinCount > 0) " · ${h.pinCount} fijados" else "") + " · nada sale del teléfono"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = px(52f)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "Borrar historial del portapapeles. $summary"
            setOnClickListener { confirmClearClipboard() }
        }
        row.addView(label("Borrar historial del portapapeles", 15f, t.bad, 600))
        row.addView(label(summary, 12f, t.muted).apply { tag = "clip-summary" })
        root.addView(row, lp())
        root.addView(label("En el teclado: toca el portapapeles junto a ⚙ para ver el historial; «Pegar» aparece al copiar algo. Android solo avisa al teclado de lo que copias mientras está activo.", 12f, t.muted), lp(top = 2f, bottom = 4f))
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar el historial del portapapeles?")
            .setMessage("Se borra todo lo guardado, también lo fijado. Lo que esté ahora en el portapapeles de Android no cambia. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ -> ClipStore.clear(this) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Picking a level plays one key click at that level, so the choice is felt, not guessed. */
    private fun previewHaptic(strength: HapticStrength) {
        val player = haptics ?: HapticPlayer(this).also { haptics = it }
        Haptics.pulseFor(HapticEvent.KEY, enabled = true)?.let { player.play(it, strength) }
    }

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
        col.addView(label("Teclado de sistema · Midnight Laboratory", 13f, t.muted, 450))
        row.addView(col)
        root.addView(row, lp(bottom = 18f))
    }

    private fun imeState(): Pair<Boolean, Boolean> {
        val imm = getSystemService(InputMethodManager::class.java)
        val ours = imm?.enabledInputMethodList?.firstOrNull { it.packageName == packageName && it.serviceName == ResystImeService::class.java.name }
        val enabled = ours != null
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = ours != null && current == ours.id
        return enabled to selected
    }

    private fun setupCard() {
        val (enabled, selected) = imeState()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.bg2, if (selected) t.edge else pal.accent, 14f)
            setPadding(px(16f), px(14f), px(16f), px(14f))
        }
        card.addView(label(if (selected) "✓ Resyst VK está activo" else "Activa el teclado en dos pasos", 16f, if (selected) t.ok else t.text, 650))
        card.addView(step(1, "Habilitar Resyst VK en Ajustes → Teclados", enabled) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, lp(top = 10f))
        card.addView(step(2, "Elegirlo como teclado actual", selected) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }, lp(top = 8f))
        if (!selected) {
            card.addView(label("Android mostrará un aviso estándar: todo teclado puede leer lo que escribes. Lo que escribes nunca sale del teléfono. $PROMISE", 12f, t.muted, 400), lp(top = 10f))
        }
        root.addView(card, lp(bottom = 20f))
    }

    private fun step(n: Int, text: String, done: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48f)
            background = rounded(if (done) t.bg2 else t.key, if (done) t.edge else t.edgeHi, 10f)
            setPadding(px(12f), px(8f), px(12f), px(8f))
            isClickable = true
            setOnClickListener { onClick() }
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

    private fun profileTabs() {
        root.addView(label("PERFILES", 11f, t.muted, 700).apply { letterSpacing = 0.12f }, lp(bottom = 8f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in store.profiles) {
            val sel = p.id == editing
            val pp = Palette.of(p.settings.theme, p.settings.accent)
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = px(64f)
                background = rounded(pp.theme.bg2, if (sel) pp.accent else pp.theme.edge, 12f)
                setPadding(px(4f), px(8f), px(4f), px(8f))
                isClickable = true
                setOnClickListener { editing = p.id; render() }
                contentDescription = "Perfil ${p.name}" + (if (p.id == store.active) ", activo" else "") + (if (sel) ", editando" else "")
            }
            chip.addView(label(p.icon, 18f, pp.accent, 500).apply { gravity = Gravity.CENTER })
            chip.addView(label(p.name, 13f, pp.theme.text, if (sel) 700 else 500).apply { gravity = Gravity.CENTER })
            chip.addView(label(if (p.id == store.active) "● activo" else " ", 10f, pp.accent, 600).apply { gravity = Gravity.CENTER })
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(3f); marginEnd = px(3f)
            })
        }
        root.addView(row, lp(bottom = 10f))
        if (editing != store.active) {
            root.addView(pill("Usar «${store.byId(editing)?.name}» como perfil activo") {
                store = store.withActive(editing); repo.save(store); render()
            }, lp(bottom = 10f))
        } else {
            root.addView(label("En el teclado: toca ✦ para pasar al siguiente perfil; mantén ✦ para abrir esta pantalla.", 12f, t.muted), lp(bottom = 10f))
        }
    }

    private fun livePreview(s: KbSettings) {
        val kv = KeyboardView(this)
        kv.setStyle(s, Palette.of(s.theme, s.accent))
        kv.setProfile(store.byId(editing)?.icon ?: "✦", store.byId(editing)?.name ?: "")
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
        root.addView(frame, lp(bottom = 6f))
        root.addView(label("Vista previa · ${Themes.byId(s.theme).label}", 11f, t.muted, 500).apply { gravity = Gravity.END }, lp(bottom = 6f))
    }

    private fun section(title: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label("✦ ", 13f, pal.accent, 600))
        row.addView(label(title.uppercase(), 12f, pal.accent, 700).apply { letterSpacing = 0.14f })
        root.addView(row, lp(top = 22f, bottom = 6f))
        root.addView(View(this).apply { setBackgroundColor(t.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)).apply { bottomMargin = px(8f) })
    }

    private fun <T> choice(title: String, options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) {
        root.addView(label(title, 14f, t.textMod, 550), lp(top = 8f, bottom = 6f))
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, name) in options) {
            val sel = value == current
            row.addView(label(name, 14f, if (sel) pal.accentInk else t.text, if (sel) 650 else 500).apply {
                gravity = Gravity.CENTER
                minHeight = px(40f)
                setPadding(px(14f), px(8f), px(14f), px(8f))
                background = rounded(if (sel) pal.accent else t.key, if (sel) pal.accent else t.edge, 20f)
                isClickable = true
                setOnClickListener { if (!sel) onPick(value) }
                contentDescription = "$title: $name" + if (sel) ", seleccionado" else ""
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(8f) })
        }
        hs.addView(row)
        root.addView(hs, lp(bottom = 4f))
    }

    private fun accentRow(s: KbSettings) {
        root.addView(label("Acento", 14f, t.textMod, 550), lp(top = 8f, bottom = 6f))
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
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
            row.addView(sw, LinearLayout.LayoutParams(px(40f), px(40f)).apply { marginEnd = px(10f) })
        }
        hs.addView(row)
        root.addView(hs, lp(bottom = 4f))
        root.addView(label("El acento se corrige automáticamente a contraste ≥ 4.5:1 (${ColorMath.toHex(pal.accent)}).", 11f, t.muted), lp(bottom = 4f))
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
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        root.addView(row, lp())
    }

    private fun slider(title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, step: Int = 5, onChange: (Int) -> Unit) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label(title, 14f, t.textMod, 550), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueLabel = label(fmt(value), 14f, pal.accent, 650)
        head.addView(valueLabel)
        root.addView(head, lp(top = 10f))
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
        root.addView(bar, lp(bottom = 4f).apply { height = px(44f) })
    }

    private fun link(text: String, onClick: () -> Unit) {
        root.addView(label(text, 13f, pal.accent, 600).apply {
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
        val reset = label("Restablecer los 4 perfiles", 13f, t.bad, 600).apply {
            gravity = Gravity.CENTER
            minHeight = px(44f)
            isClickable = true
            setOnClickListener { repo.reset(); store = repo.load(); editing = store.active; render() }
        }
        root.addView(reset, lp(top = 18f))
        root.addView(label("✦ Resyst · DM Sans (OFL) · léxico FrequencyWords (MIT)", 11f, t.muted).apply { gravity = Gravity.CENTER }, lp(top = 8f))
        root.addView(label("", 1f, t.bg).apply { typeface = Typeface.DEFAULT }, lp(bottom = 40f))
    }

    private companion object {
        const val PROMISE = "No se conecta a internet por sí solo. Descarga actualizaciones solo cuando tú se lo pides."
    }
}
