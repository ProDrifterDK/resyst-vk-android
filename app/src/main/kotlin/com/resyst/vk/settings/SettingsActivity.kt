package com.resyst.vk.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.Density
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
import com.resyst.vk.ime.Fonts
import com.resyst.vk.ime.KeyboardView
import com.resyst.vk.ime.ResystImeService
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

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Float) = (v * dp).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepo(this)
        store = repo.load()
        editing = store.active
        scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18f), px(22f), px(18f), px(28f))
        }
        scroll.addView(root)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        store = repo.load()
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) render() // IME picker closed → refresh the setup status
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
        scroll.setBackgroundColor(p.theme.bg)
        root.removeAllViews()

        header()
        setupCard()
        profileTabs()
        val s = store.byId(editing)?.settings ?: KbSettings()
        livePreview(s)
        section("Apariencia")
        choice("Tema", Themes.ALL.map { it.id to it.label }, s.theme) { v -> commit { it.copy(theme = v) } }
        accentRow(s)
        choice("Forma", listOf(KeyShape.SQUARE to "Recta", KeyShape.SOFT to "Suave", KeyShape.ROUND to "Redonda"), s.shape) { v -> commit { it.copy(shape = v) } }
        choice("Tecla", listOf(KeyCap.RAISED to "Relieve", KeyCap.FLAT to "Plana", KeyCap.OUTLINE to "Contorno"), s.cap) { v -> commit { it.copy(cap = v) } }
        choice("Fuente", listOf(KeyFont.BRAND to "DM Sans", KeyFont.TECH to "Mono", KeyFont.HUMAN to "Serif"), s.font) { v -> commit { it.copy(font = v) } }
        choice("Densidad", listOf(Density.TIGHT to "Compacta", Density.NORMAL to "Normal", Density.AIRY to "Aireada"), s.density) { v -> commit { it.copy(density = v) } }
        slider("Altura", 80, 130, (s.heightScale * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(heightScale = v / 100f) } }
        toggle("Leyendas secundarias", "Dígitos sobre la fila superior y punto de variantes", s.subLegends) { v -> commit { it.copy(subLegends = v) } }

        section("Escritura")
        choice("Idioma", listOf(Lang.ES to "Español", Lang.EN to "English"), s.lang) { v -> commit { it.copy(lang = v) } }
        choice("Fila superior", listOf(TopRow.ACCENTS to "Acentos", TopRow.NUMBERS to "Números", TopRow.NONE to "Ninguna"), s.topRow) { v -> commit { it.copy(topRow = v) } }
        toggle("Sugerencias", "Léxico offline por frecuencia (sin red)", s.suggest) { v -> commit { it.copy(suggest = v) } }
        toggle("Mayúscula automática", "Al inicio de frase", s.autoCap) { v -> commit { it.copy(autoCap = v) } }
        toggle("Doble espacio = punto", null, s.doubleSpace) { v -> commit { it.copy(doubleSpace = v) } }
        toggle("Vista previa de tecla", "Burbuja sobre la tecla al pulsar", s.popups) { v -> commit { it.copy(popups = v) } }
        slider("Pulsación larga", 150, 900, s.longPressMs, { "$it ms" }, step = 25) { v -> commit { it.copy(longPressMs = v) } }

        section("Respuesta")
        toggle("Vibración", null, s.haptics) { v -> commit { it.copy(haptics = v) } }
        toggle("Sonido de tecla", "Sintetizado en el dispositivo, sin archivos", s.sound) { v -> commit { it.copy(sound = v) } }
        if (s.sound) {
            choice("Pack", listOf(SoundPack.CLICK to "Click", SoundPack.THOCK to "Thock", SoundPack.TYPE to "Máquina", SoundPack.BUBBLE to "Burbuja"), s.soundPack) { v -> commit { it.copy(soundPack = v) } }
            slider("Volumen", 0, 100, (s.volume * 100).roundToInt(), { "$it %" }) { v -> commit { it.copy(volume = v / 100f) } }
        }

        section("Probar")
        tryField()
        footer()
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
            card.addView(label("Android mostrará un aviso estándar: todo teclado puede leer lo que escribes. Resyst VK no tiene permiso de Internet; nada sale del teléfono.", 12f, t.muted, 400), lp(top = 10f))
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
        val spec = LayoutSpec(s.lang, s.topRow, false)
        kv.setKeyboard(KeyboardLayouts.rows(Layer.LETTERS, spec), if (s.topRow == TopRow.NONE) 4 else 5, Layer.LETTERS)
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
}
