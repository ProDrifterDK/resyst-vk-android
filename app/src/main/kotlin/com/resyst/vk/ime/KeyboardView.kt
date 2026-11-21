package com.resyst.vk.ime

import com.resyst.vk.R
import com.resyst.vk.core.Txt
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.resyst.vk.core.BarStyle
import com.resyst.vk.core.Bubble
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.Legend
import com.resyst.vk.core.Motion
import com.resyst.vk.core.DeleteSwipe
import com.resyst.vk.core.EditOp
import com.resyst.vk.core.EditPad
import com.resyst.vk.core.HeldKey
import com.resyst.vk.core.ImeSwitcher
import com.resyst.vk.core.Key
import com.resyst.vk.core.KeyCap
import com.resyst.vk.core.KeyStyle
import com.resyst.vk.core.KeyType
import com.resyst.vk.core.KbSettings
import com.resyst.vk.core.Layer
import com.resyst.vk.core.Mode
import com.resyst.vk.core.NavInsets
import com.resyst.vk.core.OneHand
import com.resyst.vk.core.Palette
import com.resyst.vk.core.Drawn
import com.resyst.vk.core.Ink
import com.resyst.vk.core.KeyKind
import com.resyst.vk.core.Surface
import com.resyst.vk.core.PointerGuard
import com.resyst.vk.core.PopupGeometry
import com.resyst.vk.core.Quick
import com.resyst.vk.core.QuickAction
import com.resyst.vk.core.ShiftState
import com.resyst.vk.core.StripPlan
import com.resyst.vk.core.UpdateNotice
import com.resyst.vk.core.UpdateSurface
import com.resyst.vk.core.WhatsNew
import com.resyst.vk.core.VariantPopup
import com.resyst.vk.core.Variants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-drawn Resyst keyboard: suggestion strip + key grid + key previews + long-press variants
 * popup. All popups are drawn inside this view (the strip doubles as headroom), so there are no
 * PopupWindows to leak or mis-position inside the IME window.
 *
 * r10 (UX-3): the strip is for typing — suggestions, the contextual «Pegar» chip, the r9 update
 * chip, a «sin memoria» mark and ⚙. ⚙ opens the quick panel ([QuickPanel]); long-press ⚙ opens
 * Settings. Every glyph is a [KeyIcons] vector (UI-1). The keys may be narrowed to one side
 * ([OneHand]) with a rail to move them back.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    /** r14b: every word the view says or draws comes from the resources, asked for when drawn (TR7). */
    private val tx: Txt = ResTxt(context)

    interface Listener {
        fun onKeyDown(key: Key)
        fun onKeyUp(key: Key)
        fun onKeyCommit(key: Key)
        /**
         * r13d (TB1): called right before [onKeyCommit] of a letter key with the other letter keys whose hit
         * cell lies within [com.resyst.vk.core.TouchKeys.BORDER] key widths of where the finger lifted
         * ("" = none): the corrector may read the touch as meaning one of them.
         */
        fun onKeyNear(near: String) = Unit
        fun onVariant(text: String)
        fun onLongPressOpened()
        fun onSuggestion(word: String)
        fun onCursorDrag(steps: Int)
        fun onSpaceLongPress()
        fun onOpenSettings()
        /** The "Pegar" chip: paste the current clipboard offer. */
        fun onPasteOffer()
        /** The clipboard button (or a long-press on the paste chip): open the history. */
        fun onClipboardButton()
        /** A panel action the service must perform (paste, pin, delete, clear, close). */
        fun onClipboardPanel(act: ClipboardPanel.Act, id: Long)
        /** The emoji key: the service opens the panel (it owns the recents). */
        fun onEmojiKey()
        /** An emoji panel action: EMOJI commits [text], SPACE / DELETE edit, ABC closes. */
        fun onEmojiPanel(act: EmojiPanel.Act, text: String)
        /** r11: a skin tone picked from [cell]'s long-press: commit [pick] and make it the default. */
        fun onEmojiTone(cell: com.resyst.vk.core.EmojiCatalog.Cell, pick: String) = Unit
        /** The day/night chip in the strip (r8). */
        fun onDayNight()
        /** r9: the update chip — open the settings' update section. */
        fun onUpdateChip() = Unit
        /** r9: the update chip's ✕ — quiet until a newer version. */
        fun onUpdateDismiss() = Unit
        /** r10: ⚙ tapped — the service fills the quick panel ([showQuick]). */
        fun onQuickPanel() = Unit
        /** r10: a quick-panel tile. */
        fun onQuickAction(action: QuickAction) = Unit
        /** r10: the one-handed rail (move to [side], or [OneHand.OFF] = full width). */
        fun onOneHand(side: OneHand) = Unit
        /** r10: the «sin memoria» mark was tapped (privacy-visible explains why). */
        fun onNoMemory() = Unit
        /** r10 (bet 4): a quick vertical flick on space — switch ES ⇄ EN ([SpaceGesture]). */
        fun onSpaceFlick() = Unit
        /** r10 (bet 5): an edit-panel button. */
        fun onEditOp(op: EditOp) = Unit
        /** r10 (bet 5): a leftward swipe on ⌫ — delete the word before the cursor. */
        fun onDeleteWord() = Unit
        /** r11b: the emoji panel moved onto ([shown] true) or off the GIF tab (also when the panel hides). */
        fun onGifTab(shown: Boolean) = Unit
        /** r11b: a target of the GIF tab (enable, accept, cancel, search box, retry, a GIF). */
        fun onGifPanel(target: GifPanel.Target) = Unit
        /** r11b: the GIF grid scrolled (thumbnail window, next page). */
        fun onGifScrolled() = Unit
        /** r11b: the GIF search strip's ← (back to the grid, no request) and «Buscar» (one request). */
        fun onGifSearchBack() = Unit
        fun onGifSearchGo() = Unit
        /** r15 (ES): the emoji search: a result tapped, ✕, ← (back to the panel). */
        fun onEmojiSearchPick(emoji: String) = Unit
        fun onEmojiSearchClear() = Unit
        fun onEmojiSearchBack() = Unit
        /** r13f (offers): the context offer chip — replace the earlier word with the offered one. */
        fun onContextOffer() = Unit
        /** r14 (PS4): a tema chip in the quick panel's row. */
        fun onQuickTema(id: String) = Unit
        /** r14 (PS4): «Personalizar» in the quick panel — Settings › Apariencia. */
        fun onPersonalize() = Unit
    }

    var listener: Listener? = null

    // ── state pushed by the service ──────────────────────────────────────
    private var rows: List<List<Key>> = emptyList()
    private var baseRowCount = 5
    private var layer = Layer.LETTERS
    private var settings = KbSettings()
    private var palette = Palette.of("lab", null)
    private var shift = ShiftState.OFF
    private var enterDesc = tx.get(R.string.enter_default)
    private var enterIcon = Icon.ENTER
    private var suggestions: List<String> = emptyList()
    private var pasteLabel: String? = null
    private var pasteImage = false
    /** r13f (offers, CH1/CH2): the context offer chip — its label (the replacement) and TalkBack text; null = none. */
    private var offerLabel: String? = null
    private var offerDesc: String = ""
    /** r10 (F-6): why personal memory is off in this field, null = it is on (no mark). */
    private var noMemory: String? = null

    /** The system globe is hidden in this window (IME nav bar caption hidden, r7). */
    private var systemGlobeHidden = false
    /** There is another keyboard / subtype to switch to (set by the service). */
    private var switchAvailable = false
    private val spaceHint get() = ImeSwitcher.spaceHint(systemGlobeHidden, switchAvailable)

    fun setSwitchAvailable(v: Boolean) {
        if (v != switchAvailable) { switchAvailable = v; invalidate(); a11y.invalidateRoot() }
    }

    /** The clipboard history panel, drawn over the strip + keys while open. */
    val clipPanel = ClipboardPanel(resources.displayMetrics.density, tx)
    var clipboardOpen = false
        private set

    /** The emoji panel (r8), same overlay pattern as the clipboard. */
    val emojiPanel = EmojiPanel(resources.displayMetrics.density, tx).apply {
        // r12 (EG3): a tab measured off the UI thread arrived while it is on screen
        onCellsReady = { if (emojiOpen) { invalidate(); a11y.invalidateRoot() } }
    }
    var emojiOpen = false
        private set

    /** r10: the quick panel under ⚙ (UX-3), same overlay pattern. */
    val quickPanel = QuickPanel(resources.displayMetrics.density, tx).apply { face = { w -> typeface(w) } }
    var quickOpen = false
        private set
    private var mode = Mode.NONE
    /** r10 (bet 5): the edit panel, opened from the quick panel's «Edición». */
    val editPanel = EditPanel(resources.displayMetrics.density, tx)
    var editOpen = false
        private set
    private val panelOpen get() = clipboardOpen || emojiOpen || quickOpen || editOpen

    /** The edit panel; [selecting] = the arrows extend the selection; [secret] closes Copiar/Cortar. */
    fun showEdit(selecting: Boolean) {
        cancelPointers()
        hideClipboard(); hideEmoji(); hideQuick()
        if (!editOpen) panelAt = SystemClock.uptimeMillis()
        editOpen = true
        layoutPanel()
        editPanel.set(selecting, secretField)
        invalidate(); a11y.invalidateRoot()
    }

    fun setEditSelecting(selecting: Boolean) {
        if (!editOpen || selecting == editPanel.selecting) return
        editPanel.set(selecting, secretField)
        invalidate(); a11y.invalidateRoot()
    }

    fun hideEdit() {
        if (!editOpen) return
        cancelPointers()
        editOpen = false
        invalidate(); a11y.invalidateRoot()
    }

    /** The quick panel with [tiles]; [update] = the r9 notice line (null = none); r14: [temas] = the tema row. */
    fun showQuick(tiles: List<Quick.Tile>, update: String?, mode: Mode, temas: List<QuickPanel.TemaChip> = emptyList()) {
        cancelPointers()
        hideClipboard(); hideEmoji(); hideEdit()
        this.mode = mode
        if (!quickOpen) { panelAt = SystemClock.uptimeMillis(); quickPanel.opened() }
        quickOpen = true
        layoutPanel()
        quickPanel.set(tiles, update, temas, tx.get(R.string.quick_personalize))
        // r15 (QP6–QP10): what the layout chose and what the real font would cut (debuggable builds; the E2E reads it)
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) quickPanel.logReport(TAG)
        invalidate(); a11y.invalidateRoot()
    }

    fun hideQuick() {
        if (!quickOpen) return
        cancelPointers()
        quickOpen = false
        invalidate(); a11y.invalidateRoot()
    }

    /** r10 (bet 3): a secret field (password, PIN) — no key-preview bubble shows what is typed. */
    private var secretField = false

    fun setSecret(secret: Boolean) {
        if (secret != secretField) { secretField = secret; invalidate() }
    }

    /** r10 (F-6): the «sin memoria» mark; [reason] is what TalkBack (and a tap) explains. */
    fun setNoMemory(reason: String?) {
        if (reason == noMemory) return
        noMemory = reason
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    /** Show the day/night chip in the strip (only when the profile has a light/dark twin). */
    private var dayNight: Boolean? = null

    /** [dark] = the current theme is dark (the chip shows the sun to go light), null = no chip. */
    fun setDayNight(dark: Boolean?) {
        if (dark == dayNight) return
        dayNight = dark
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    // ── r9: update notice (chip on a fresh field, amber dot on ⚙ otherwise) ──
    /** The announced version (null = none) and where the service wants it. */
    private var updateVersion: String? = null
    private var updateSurface = UpdateSurface.NONE
    /** The chip is laid out, showing [chipVersion] (kept while it fades out after a dismiss). */
    private var chipShown = false
    private var chipVersion: String? = null
    /** r13c: the chip on the strip is the "Novedades" notice, not an update (label, glyph, a11y). */
    private var chipNews = false
    private val starPath = android.graphics.Path()
    private var chipExiting = false
    /** 0 = hidden … 1 = fully shown; drives alpha + an 8 dp slide. */
    private var chipAnim = 0f
    private var chipAnimator: ValueAnimator? = null
    private var updateLabel: UpdateNotice.Label? = null
    /** ⚙ carries the amber dot (update announced but the chip is not in the strip). */
    private var gearDot = false

    /**
     * [version] = the announced release (null = nothing to announce). The strip height never
     * changes, so the keys never move when the chip comes or goes (N5). Appearing fades + slides
     * in; a dismiss fades out; typing swaps it for the ⚙ dot at once (the strip is needed now).
     */
    fun setUpdate(version: String?, surface: UpdateSurface, news: Boolean = false) {
        val sf = if (version == null) UpdateSurface.NONE else surface
        if (version == updateVersion && sf == updateSurface && news == chipNews) return
        updateVersion = version
        updateSurface = sf
        // r13c (WN5): "Novedades" borrows the chip; while it fades out it keeps its own look
        if (sf == UpdateSurface.CHIP) chipNews = news
        when {
            sf == UpdateSurface.CHIP -> {
                chipVersion = version
                if (!chipShown || chipExiting) animateChip(show = true)
            }
            chipShown && sf == UpdateSurface.NONE && !chipExiting -> animateChip(show = false)
            chipShown && sf == UpdateSurface.BADGE -> {
                chipAnimator?.cancel(); chipShown = false; chipExiting = false; chipAnim = 0f
            }
        }
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    private fun animateChip(show: Boolean) {
        chipAnimator?.cancel()
        chipExiting = !show
        chipShown = true
        val a = ValueAnimator.ofFloat(chipAnim, if (show) 1f else 0f)
        a.duration = if (show) 260L else 160L
        a.interpolator = DecelerateInterpolator(if (show) 1.6f else 1f)
        a.addUpdateListener { chipAnim = it.animatedValue as Float; invalidate() }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled || show) return
                chipShown = false; chipExiting = false
                layoutStrip(); invalidate(); a11y.invalidateRoot()
            }
        })
        chipAnimator = a
        a.start()
    }

    fun showEmoji(recents: List<String>) {
        cancelPointers()
        hideClipboard()
        quickOpen = false
        if (!emojiOpen) panelAt = SystemClock.uptimeMillis()
        emojiOpen = true
        layoutPanel()
        emojiPanel.open(recents)
        invalidate(); a11y.invalidateRoot()
    }

    /**
     * r15 (ER4): back from the emoji search: the panel as it was (tab, scroll, the recents' order it opened with); not a
     * new open.
     */
    fun resumeEmoji() {
        cancelPointers()
        hideClipboard()
        quickOpen = false
        if (!emojiOpen) panelAt = SystemClock.uptimeMillis()
        emojiOpen = true
        layoutPanel()
        emojiPanel.relayout()
        invalidate(); a11y.invalidateRoot()
    }

    /** r15 (ER3): the recents were wiped: the open row empties at once (a pick never reorders it while open, ER1). */
    fun wipeEmojiRecents() {
        emojiPanel.wipeRecents()
        if (emojiOpen) { invalidate(); a11y.invalidateRoot() }
    }

    fun hideEmoji() {
        if (!emojiOpen) return
        cancelPointers()
        val wasGif = emojiPanel.onGif
        emojiOpen = false
        invalidate(); a11y.invalidateRoot()
        if (wasGif) listener?.onGifTab(false)
    }

    /** r11b: the emoji panel straight on its GIF tab (back from the GIF search strip). */
    fun showGif(recents: List<String>) {
        showEmoji(recents)
        emojiPanel.selectTab(emojiPanel.gifTab)
        invalidate(); a11y.invalidateRoot()
        listener?.onGifTab(true)
    }

    /** r11b: re-lay out and redraw the GIF tab after its content changed (page, thumbnail, status). */
    fun gifChanged() {
        if (!emojiOpen || !emojiPanel.onGif) return
        emojiPanel.relayout()
        invalidate(); a11y.invalidateRoot()
    }

    // ── r11b: the GIF search strip (the keys type into the service's private buffer) ──
    /** The GIF query being typed, null = normal strip. */
    private var gifSearch: String? = null

    fun setGifSearch(query: String?) {
        if (query == gifSearch) return
        gifSearch = query
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    // ── r15: the emoji search (ES): the bar is its box, a band above the bar holds the results ──
    /**
     * One result: what it draws and inserts (the user's tone) and its CLDR name in the interface language; the TalkBack
     * name («🦅, águila») is formatted only when TalkBack asks (ES9: nothing per result per keystroke).
     */
    class SearchResult(val emoji: String, val name: String)

    private fun resultDesc(i: Int): String {
        val r = esResults.getOrNull(i) ?: return ""
        return tx.get(if (i == 0) R.string.emoji_search_first else R.string.emoji_search_result, r.emoji, r.name)
    }
    /** The query being typed, null = not searching (no band, nothing moves). */
    private var esQuery: String? = null
    private var esResults: List<SearchResult> = emptyList()
    /** The band's line when it has no result: the hint, «preparing», or «no emoji for …». */
    private var esStatus: String? = null
    private var esScroll = 0f
    /** The visible results: index in [esResults] → its cell (view coordinates, inside the band). */
    private val esHits = ArrayList<Pair<Int, RectF>>()
    /** ES11: the band's height; 0 when not searching, so every offset below is exactly the 0.14 arithmetic. */
    private val bandH get() = if (esQuery != null) BAND_DP * dp else 0f

    /** [query] null ends the search (the band goes, the keyboard is its usual height again). */
    fun setEmojiSearch(query: String?, results: List<SearchResult>, status: String?) {
        val was = esQuery != null
        if (query == null) { esQuery = null; esResults = emptyList(); esStatus = null }
        else {
            if (query != esQuery || results.map { it.emoji } != esResults.map { it.emoji }) esScroll = 0f
            esQuery = query; esResults = results; esStatus = status
        }
        if (was != (query != null)) { cancelPointers(); requestLayout() }
        layoutStrip(); layoutBand(); invalidate(); a11y.invalidateRoot()
    }

    /** r15 (ES10): while searching, Enter shows (and inserts) the top match; null = its icon. */
    private var enterGlyph: String? = null

    fun setEnterGlyph(g: String?) {
        if (g != enterGlyph) { enterGlyph = g; invalidate() }
    }

    private fun bandCell() = BAND_CELL_DP * dp
    private fun bandMaxScroll() = max(0f, 2 * BAND_PAD_DP * dp + esResults.size * bandCell() - width)

    private fun layoutBand() {
        esHits.clear()
        if (esQuery == null || width == 0) return
        esScroll = esScroll.coerceIn(0f, bandMaxScroll())
        val cell = bandCell()
        for (i in esResults.indices) {
            val l = BAND_PAD_DP * dp + i * cell - esScroll
            if (l + cell <= 0f) continue
            if (l >= width) break
            esHits += i to RectF(l, 3 * dp, l + cell, bandH - 3 * dp)
        }
    }

    // ── geometry ─────────────────────────────────────────────────────────
    private val dp = resources.displayMetrics.density
    private class Box(val key: Key, val rect: RectF, val cellLeft: Float, val cellRight: Float, val row: Int) {
        /** r14 (MO1): when a finger left it (the press fades out from here); 0 = at rest. */
        var releasedAt = 0L
    }
    private val boxes = ArrayList<Box>()
    private val rowBoxes = ArrayList<List<Box>>()
    private var rowH = 0f
    private val stripH get() = 42 * dp

    private enum class StripKind { PASTE, SUGGESTION, OFFER, SETTINGS, DAYNIGHT, UPDATE, UPDATE_X, NO_MEMORY, GIF_BACK, GIF_FIELD, GIF_GO, ES_BACK, ES_FIELD, ES_CLEAR }
    private class StripItem(val kind: StripKind, val text: String, val rect: RectF)
    private val stripItems = ArrayList<StripItem>()

    // ── touch ────────────────────────────────────────────────────────────
    private class Ptr(val id: Int, var box: Box?, val downX: Float) {
        var popup: VariantPopup? = null
        var popupItems: List<String> = emptyList()
        var popupTop = 0f
        var sel = 0
        var longFired = false
        var strip: StripItem? = null
        /** r10: a one-handed rail button (move to that side / OFF = full width). */
        var rail: OneHand? = null
        /**
         * r13h: a key's moving finger — the space flick / cursor drag, ⌫'s word swipe, a letter's roll-over ([HeldKey],
         * pure) — and the merged-touch guard ([PointerGuard], G2); null off the keys (bar, rail).
         */
        var held: HeldKey? = null
        var guard: PointerGuard? = null
        /** r13h (JG2): a FAST sample's effects are waiting for the next calm sample or the lift. */
        var pending = false
        val cursorMode: Boolean get() = held?.cursorMode == true
        /** r13d (TB1): the last position seen (a roll-over commits a held key from here). */
        var lastX = downX
        var lastY = 0f
        /** r14 (MO1): the preview's key and when it showed (it grows out of the key), when the variants opened, the selection's drawn x. */
        var previewBox: Box? = null
        var previewAt = 0L
        var popupAt = 0L
        var hlX = -1f
    }
    private val ptrs = LinkedHashMap<Int, Ptr>()
    /** r13h (JG3): pointers whose touch ended at a merged-touch jump, until they settle (a new touch starts there). */
    private val landing = HashMap<Int, PointerGuard>()
    /** r13h: the width of a one-unit key (the guard measures jumps in key widths). */
    private var keyPx = 1f
    private val handler = Handler(Looper.getMainLooper())

    // ── paints ───────────────────────────────────────────────────────────
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()

    // ── r14: painter, motion (no allocation while drawing: PE1) ─────────
    private val painter = KeyPainter(dp)
    /** The face of the key being drawn (its legend centres here); also scratch for necks and highlights. */
    private val face = RectF()
    private val popupBody = RectF()
    private val chipBody = RectF()
    private val chipX = RectF()
    private val bubblePath = Path()
    private val neckPath = Path()
    private val headPath = Path()
    private val pressedNow = HashSet<Box>()
    private val pressedStrip = HashSet<StripItem>()
    /** The effective animation level (Android's "Quitar animaciones" forces OFF) and Android's animator scale. */
    private var motion = Motion.FULL
    private var animScale = 1f
    /** The system font scale (legends follow it from 1 to 2, LG4). */
    private var fontScale = 1f
    /** Set while drawing when an effect is still moving: the frame asks for the next one. */
    private var animating = false
    /** A palette change cross-fades (tema switch, day/night). */
    private var fadeFrom: Palette? = null
    private var fadeTo: Palette? = null
    private var fadeAt = 0L
    /** When the open panel opened (it rises into place) and when the context offer chip arrived. */
    private var panelAt = 0L
    private var offerAt = 0L

    private val a11y = KeyA11y()
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)

    init {
        ViewCompat.setAccessibilityDelegate(this, a11y)
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
    }

    // ── public API ───────────────────────────────────────────────────────
    fun setKeyboard(rows: List<List<Key>>, baseRowCount: Int, layer: Layer) {
        val heightChanged = baseRowCount != this.baseRowCount
        this.rows = rows
        this.baseRowCount = max(1, baseRowCount)
        this.layer = layer
        cancelPointers()
        if (heightChanged) requestLayout()
        layoutKeys()
        invalidate()
    }

    fun setStyle(s: KbSettings, p: Palette) {
        val heightChanged = s.heightScale != settings.heightScale
        if (s.oneHanded != settings.oneHanded) cancelPointers()
        animScale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        motion = Motion.effective(s.motion, animScale)
        fontScale = resources.configuration.fontScale
        quickPanel.fontScale = fontScale
        // r14: colours cross-fade when the keyboard is on screen and only they changed (a tema switch, day/night)
        val target = fadeTo ?: palette
        if (isShown && width > 0 && p != target && motion == Motion.FULL && !heightChanged) {
            fadeFrom = palette; fadeTo = p; fadeAt = SystemClock.uptimeMillis()
        } else if (p != target) {
            fadeFrom = null; fadeTo = null
        }
        settings = s
        palette = if (fadeTo != null) palette else p
        painter.shape = s.shape
        painter.cap = s.cap
        painter.reset()
        if (heightChanged) requestLayout()
        layoutKeys()
        invalidate()
    }

    fun setShift(state: ShiftState) {
        if (state != shift) { shift = state; invalidate(); a11y.invalidateRoot() }
    }

    fun setEnter(icon: Icon, desc: String) {
        if (icon != enterIcon || desc != enterDesc) { enterIcon = icon; enterDesc = desc; invalidate(); a11y.invalidateRoot() }
    }

    fun setSuggestions(list: List<String>) {
        if (list != suggestions) { suggestions = list; layoutStrip(); invalidate(); a11y.invalidateRoot() }
    }

    fun reset() { cancelPointers(); invalidate() }

    /**
     * r13f (offers): the context offer chip: [label] = the word it puts in (`casa`), [desc] = what TalkBack says
     * ("Cambiar caza por casa"); null = no chip. It takes the last suggestion slot (CH1).
     */
    fun setOffer(label: String?, desc: String?) {
        if (label == offerLabel && (desc ?: "") == offerDesc) return
        if (offerLabel == null && label != null) offerAt = SystemClock.uptimeMillis()
        offerLabel = label; offerDesc = desc ?: ""
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    /** The leading "Pegar" chip: [label] = what it pastes (already masked), null = no chip. */
    fun setPasteOffer(label: String?, image: Boolean) {
        if (label == pasteLabel && image == pasteImage) return
        pasteLabel = label; pasteImage = image
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    fun showClipboard(items: List<com.resyst.vk.core.ClipboardHistory.Entry>, now: Long) {
        cancelPointers()
        hideEmoji()
        quickOpen = false
        if (!clipboardOpen) { clipPanel.reset(); panelAt = SystemClock.uptimeMillis() }
        clipboardOpen = true
        layoutPanel()
        clipPanel.setItems(items, now)
        invalidate(); a11y.invalidateRoot()
    }

    fun updateClipboard(items: List<com.resyst.vk.core.ClipboardHistory.Entry>, now: Long) {
        if (!clipboardOpen) return
        clipPanel.setItems(items, now)
        invalidate(); a11y.invalidateRoot()
    }

    fun hideClipboard() {
        if (!clipboardOpen) return
        cancelPointers()
        clipboardOpen = false
        clipPanel.reset()
        invalidate(); a11y.invalidateRoot()
    }

    private fun layoutPanel() {
        val h = if (height > 0) (height - paddingBottom - bandH) else stripH + keysHeight() + 4 * dp
        clipPanel.setBounds(RectF(0f, 0f, width.toFloat(), h))
        emojiPanel.setBounds(RectF(0f, 0f, width.toFloat(), h))
        quickPanel.setBounds(RectF(0f, 0f, width.toFloat(), h))
        editPanel.setBounds(RectF(0f, 0f, width.toFloat(), h))
    }

    // ── measure / layout ─────────────────────────────────────────────────
    private fun keysHeight(): Float {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val base = if (landscape) 40f else 54f
        return baseRowCount * base * dp * settings.heightScale
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val content = (stripH + keysHeight() + 4 * dp).toInt()
        // r15 (ES11): the emoji search's results band adds its height on top (0 when not searching: 0.13.0's height)
        setMeasuredDimension(w, NavInsets.totalHeight(content + bandH.toInt(), paddingBottom))
    }

    /**
     * Only the IME's own input view reserves the navigation bar; the settings preview lives
     * inside an activity and must not.
     */
    var reserveNavBar = false
        set(v) { field = v; if (v) requestApplyInsets() else applyNavPadding(0) }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // The dispatched insets may already be consumed by the IME's frame on the way down;
        // the root insets are the window's truth (the r1 bug: this always read 0).
        if (reserveNavBar) applyNavPadding(navPaddingFrom(rootWindowInsets ?: insets, insets))
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (reserveNavBar) rootWindowInsets?.let { applyNavPadding(navPaddingFrom(it, it)) }
    }

    private fun navPaddingFrom(root: WindowInsets, dispatched: WindowInsets): Int {
        val (current, stable) = if (Build.VERSION.SDK_INT >= 30) {
            root.getInsets(WindowInsets.Type.navigationBars()).bottom to
                root.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            root.systemWindowInsetBottom to root.stableInsetBottom
        }
        // Gesture navigation draws the system keyboard-switch button (globe) inside the nav
        // strip, taller than the pill itself — reserve a floor so keys stay clear of it.
        val navMode = if (Build.VERSION.SDK_INT >= 29) {
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0)
        } else 0
        // r7: the service asks the system to hide the IME navigation bar (captionBar); when that
        // worked, the globe is gone and the floor above can go too.
        val (capH, capVisible) = if (Build.VERSION.SDK_INT >= 30) {
            root.getInsetsIgnoringVisibility(WindowInsets.Type.captionBar()).bottom to
                root.isVisible(WindowInsets.Type.captionBar())
        } else 0 to false
        val hidden = ImeSwitcher.systemGlobeHidden(Build.VERSION.SDK_INT, capH, capVisible)
        if (hidden != systemGlobeHidden) { systemGlobeHidden = hidden; invalidate(); a11y.invalidateRoot() }
        // r8: caption-aware rule; no floor when the system keeps its own strip below us (inset 0)
        val cap = if (Build.VERSION.SDK_INT >= ImeSwitcher.MIN_HIDE_SDK) capH else 0
        // double-reserve guard: where does our window really end vs the nav bar?
        val (bottom, screenH) = screenBottom()
        val ov = NavInsets.overlap(bottom, screenH, stable)
        val pad = NavInsets.reserve(current, stable, navMode, cap, capVisible, bottom, screenH)
        val d = if (Build.VERSION.SDK_INT >= 30) dispatched.getInsets(WindowInsets.Type.navigationBars()).bottom
        else @Suppress("DEPRECATION") dispatched.systemWindowInsetBottom
        Log.i(TAG, "nav inset: root current=$current stable=$stable dispatched=$d mode=$navMode caption=$capH visible=$capVisible globeHidden=$hidden overlap=$ov → padding=$pad")
        return pad
    }

    /**
     * Where our view ends on screen and the display height, for the double-reserve guard
     * ([NavInsets.overlap]); (0, 0) before the first layout = unknown, trust the insets. A view
     * the system already placed above its own nav strip covers 0 of it even if the insets claim
     * otherwise.
     */
    private fun screenBottom(): Pair<Int, Int> {
        if (!isLaidOut || height == 0) return 0 to 0
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val screenH = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(android.view.WindowManager::class.java)?.maximumWindowMetrics?.bounds?.height() ?: 0
        } else resources.displayMetrics.heightPixels
        return (loc[1] + height) to screenH
    }

    /**
     * A new reserve arrives inside the window's insets pass, after the traversal decided whether
     * the window may resize: a requestLayout() there is measured against the OLD window height and
     * the window never grows (API 35, 3-button: window 829 px, view 955 px, nav buttons drawn over
     * the space row). Ask again on the next frame so the window re-measures from the full display.
     */
    private fun applyNavPadding(px: Int) {
        if (px != paddingBottom) {
            setPadding(0, 0, 0, px)
            requestLayout()
            if (isAttachedToWindow) post { requestLayout() }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // the double-reserve guard needs the laid-out position: re-check once we have one
        if (reserveNavBar && paddingBottom > 0) post { rootWindowInsets?.let { applyNavPadding(navPaddingFrom(it, it)) } }
        layoutKeys()
        layoutBand()
        if (panelOpen) layoutPanel()
    }

    /** r10 (UX-13): where the keys sit and where the one-handed rail goes. */
    private var split = OneHand.Split(0f, 0f, 0f, 0f)
    private val rail = ArrayList<Pair<OneHand, RectF>>()

    private fun layoutKeys() {
        boxes.clear(); rowBoxes.clear(); rail.clear()
        if (width == 0 || rows.isEmpty()) return
        split = OneHand.split(width.toFloat(), settings.oneHanded, dp)
        val padX = 3 * dp
        val gap = settings.density.gapDp * dp
        val unit = (split.keysRight - split.keysLeft - 2 * padX) / com.resyst.vk.core.KeyboardLayouts.ROW_UNITS
        keyPx = max(1f, unit)
        rowH = keysHeight() / rows.size
        if (split.railWidth > 0f) {
            val top = stripH + 2 * dp
            val mid = stripH + keysHeight() / 2
            val bottom = stripH + keysHeight()
            val other = if (settings.oneHanded == OneHand.LEFT) OneHand.RIGHT else OneHand.LEFT
            rail += other to RectF(split.railLeft, top, split.railLeft + split.railWidth, mid)
            rail += OneHand.OFF to RectF(split.railLeft, mid, split.railLeft + split.railWidth, bottom)
        }
        rows.forEachIndexed { r, row ->
            val y0 = stripH + r * rowH
            var x = split.keysLeft + padX
            val list = ArrayList<Box>()
            for (k in row) {
                val w = k.width * unit
                if (k.type != KeyType.SPACER) {
                    val b = Box(k, RectF(x + gap / 2, y0 + gap / 2 + dp, x + w - gap / 2, y0 + rowH - gap / 2), x, x + w, r)
                    boxes += b; list += b
                }
                x += w
            }
            rowBoxes += list
        }
        layoutStrip()
        a11y.invalidateRoot()
    }

    private fun layoutStrip() {
        stripItems.clear()
        if (width == 0) return
        val h = stripH
        esQuery?.let { q ->
            // r15 (ES): ← · the search box, its ✕ inside its right end (when there is something to clear); nothing else
            val back = 48 * dp
            val right = width - 8 * dp
            stripItems += StripItem(StripKind.ES_BACK, "", RectF(0f, 0f, back, h))
            if (q.isNotEmpty()) stripItems += StripItem(StripKind.ES_CLEAR, "", RectF(right - 44 * dp, 0f, right, h))
            stripItems += StripItem(StripKind.ES_FIELD, q, RectF(back, 5 * dp, right, h - 5 * dp))
            gearDot = false
            return
        }
        gifSearch?.let { q ->
            // r11b: ← · the query box · «Buscar»; nothing else (no suggestions, no chips) while typing a GIF search
            val back = 48 * dp
            val go = 84 * dp
            stripItems += StripItem(StripKind.GIF_BACK, "", RectF(0f, 0f, back, h))
            stripItems += StripItem(StripKind.GIF_GO, tx.get(R.string.strip_gif_go), RectF(width - go, 0f, width.toFloat(), h))
            stripItems += StripItem(StripKind.GIF_FIELD, q, RectF(back, 5 * dp, width - go - 4 * dp, h - 5 * dp))
            gearDot = false
            return
        }
        // ⚙ owns the right edge (48 dp target); the opt-in sun/moon and the «sin memoria» mark sit beside it
        val gearW = 48 * dp
        stripItems += StripItem(StripKind.SETTINGS, "", RectF(width - gearW, 0f, width.toFloat(), h))
        var r = width - gearW
        if (dayNight != null) {
            val dw = 44 * dp
            stripItems += StripItem(StripKind.DAYNIGHT, "", RectF(r - dw, 0f, r, h))
            r -= dw
        }
        if (noMemory != null) {
            val nw = 36 * dp
            stripItems += StripItem(StripKind.NO_MEMORY, noMemory ?: "", RectF(r - nw, 0f, r, h))
            r -= nw
        }
        var l = 6 * dp
        // r9: the update chip leads (a fresh field only — the service decides, UpdateNotice.surface)
        val upd = chipVersion
        updateLabel = null
        var chipRoomLeft = Float.MAX_VALUE
        if (chipShown && upd != null) {
            val room = r - l - 2 * dp
            val textRoom = room - CHIP_ICON_W * dp - CHIP_X_W * dp - CHIP_TEXT_END * dp
            val label = UpdateNotice.fitOf(if (chipNews) WhatsNew.labels(tx, upd) else UpdateNotice.labels(tx, upd), textRoom,
                { t, two -> chipTitlePaint(two).measureText(t) }, { t, two -> chipActionPaint(two).measureText(t) })
            if (label != null) {
                val textW = if (label.twoLine) max(chipTitlePaint(true).measureText(label.title), chipActionPaint(true).measureText(label.action))
                else chipTitlePaint(false).measureText(label.title) + chipActionPaint(false).measureText(label.action)
                val w = min(room, CHIP_ICON_W * dp + textW + CHIP_TEXT_END * dp + CHIP_X_W * dp)
                // ✕ first: stripAt() returns the first match, so the dismiss target wins its 48 dp
                stripItems += StripItem(StripKind.UPDATE_X, upd, RectF(l + w - CHIP_X_W * dp, 0f, l + w, h))
                stripItems += StripItem(StripKind.UPDATE, upd, RectF(l, 0f, l + w - CHIP_X_W * dp, h))
                updateLabel = label
                l += w + 4 * dp
                chipRoomLeft = r - l
            }
        }
        gearDot = updateVersion != null && updateSurface != UpdateSurface.NONE && updateLabel == null
        val paste = pasteLabel
        // QS2: «Pegar» takes one suggestion slot — two suggestions stay beside it
        var shown = StripPlan.suggestions(suggestions, paste != null)
        if (updateLabel != null) shown = shown.take((chipRoomLeft / (96 * dp)).toInt())
        if (paste != null) {
            text.textSize = 14 * dp
            text.typeface = typeface(600)
            val label = if (pasteImage) tx.get(R.string.strip_paste_image) else paste
            val room = r - l
            val want = text.measureText(label) + 44 * dp
            val slot = if (shown.isEmpty()) room else room / (shown.size + 1)
            val w = min(want, max(slot, min(room, 96 * dp)))
            stripItems += StripItem(StripKind.PASTE, label, RectF(l, 6 * dp, l + w, h - 6 * dp))
            l += w + 4 * dp
        }
        // r13f (offers, CH1): the offer chip takes the LAST slot, at the right end of the suggestions: the first
        // suggestion (what space applies), ↶ and ¿…? keep their places
        val offer = offerLabel
        if (offer != null && r - l > 120 * dp) {
            if (shown.isNotEmpty()) shown = shown.dropLast(1)
            text.textSize = 15 * dp
            text.typeface = typeface(650)
            val room = r - l
            val want = text.measureText(offer) + 42 * dp
            val w = min(max(want, 72 * dp), if (shown.isEmpty()) room else room / 2)
            stripItems += StripItem(StripKind.OFFER, offer, RectF(r - w - 4 * dp, 6 * dp, r - 4 * dp, h - 6 * dp))
            r -= w + 8 * dp
        }
        if (shown.isNotEmpty() && r - l > 40 * dp) {
            val cw = (r - l) / shown.size
            shown.forEachIndexed { i, s ->
                stripItems += StripItem(StripKind.SUGGESTION, s, RectF(l + i * cw, 0f, l + (i + 1) * cw, h))
            }
        }
    }

    private fun typeface(weight: Int): Typeface = Fonts.get(context, settings.font, weight)

    // r9 chip text: brand face (DM Sans) whatever the key font, so the notice reads as Resyst's own voice
    private val chipTitle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipAction = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun chipTitlePaint(twoLine: Boolean) = chipTitle.apply {
        textSize = (if (twoLine) 12f else 13.5f) * dp
        typeface = Fonts.get(context, com.resyst.vk.core.KeyFont.BRAND, 650)
        textAlign = Paint.Align.LEFT
    }
    private fun chipActionPaint(twoLine: Boolean) = chipAction.apply {
        textSize = (if (twoLine) 10f else 13f) * dp
        typeface = Fonts.get(context, com.resyst.vk.core.KeyFont.BRAND, 500)
        textAlign = Paint.Align.LEFT
    }

    // ── hit testing ──────────────────────────────────────────────────────
    private fun boxAt(x: Float, y: Float): Box? {
        if (rowBoxes.isEmpty() || y < stripH) return null
        val r = ((y - stripH) / rowH).toInt().coerceIn(0, rowBoxes.size - 1)
        val row = rowBoxes[r]
        if (row.isEmpty()) return null
        row.firstOrNull { x >= it.cellLeft && x < it.cellRight }?.let { return it }
        // spacer cells and the outer padding go to the nearest real key
        return row.minByOrNull { min(abs(x - it.cellLeft), abs(x - it.cellRight)) }
    }

    /**
     * r13d (TB1): the other letter keys whose HIT cell (the cells [boxAt] uses: a row is `stripH + r·rowH`,
     * a key runs to the gap midpoints) lies within [com.resyst.vk.core.TouchKeys.BORDER] key widths of
     * ([x], [y]), for the letter key [b] the touch committed; "" for any other key. Letters only, never
     * kept here, never logged.
     */
    private fun nearKeys(b: Box, x: Float, y: Float): String {
        val hit = b.key.text.singleOrNull()?.takeIf { it.isLetter() } ?: return ""
        val cells = ArrayList<com.resyst.vk.core.TouchKeys.Cell>(32)
        for (row in rowBoxes) for (o in row) {
            val l = o.key.text.singleOrNull() ?: continue
            if (o.key.type != KeyType.CHAR || !l.isLetter()) continue
            val top = stripH + o.row * rowH
            cells += com.resyst.vk.core.TouchKeys.Cell(l, o.cellLeft, top, o.cellRight, top + rowH)
        }
        return com.resyst.vk.core.TouchKeys.near(cells, x, y, hit, b.cellRight - b.cellLeft)
    }

    private fun stripAt(x: Float, y: Float): StripItem? =
        if (y >= stripH) null else stripItems.firstOrNull { x >= it.rect.left - 4 * dp && x <= it.rect.right + 4 * dp }

    // ── touch ────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // r15 (ES11): the results band sits above the bar; below it every y is the bar's (bandH = 0 when not searching)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val y = e.getY(i)
                if (esQuery != null && y < bandH) bandDown(e.getPointerId(i), e.getX(i))
                else down(e.getPointerId(i), e.getX(i), y - bandH, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                // r13h (JG1): every sample counts (a batched MOVE carries the reports since the last frame)
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) {
                        val id = e.getPointerId(i)
                        if (id == bandPtr) bandMove(e.getHistoricalX(i, h)) else move(id, e.getHistoricalX(i, h), e.getHistoricalY(i, h) - bandH, e.getHistoricalEventTime(h))
                    }
                }
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == bandPtr) bandMove(e.getX(i)) else move(id, e.getX(i), e.getY(i) - bandH, e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (id == bandPtr) bandUp(e.getX(i)) else up(id, e.getX(i), e.getY(i) - bandH, e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> cancelPointers()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float, t: Long) {
        if (clipboardOpen) { panelDown(id, x, y); return }
        if (emojiOpen) { emojiDown(id, x, y); return }
        if (quickOpen) { quickDown(id, x, y); return }
        if (editOpen) { editDown(id, x, y); return }
        // Fast-typing roll-over: a new finger commits any char key still held without a popup.
        for (p in ptrs.values.toList()) {
            val b = p.box ?: continue
            if (p.popup == null && !p.cursorMode && b.key.type == KeyType.CHAR) {
                handler.removeCallbacksAndMessages(p)
                ptrs.remove(p.id)
                b.releasedAt = SystemClock.uptimeMillis()
                listener?.onKeyNear(nearKeys(b, p.lastX, p.lastY))
                listener?.onKeyCommit(b.key)
            }
        }
        val strip = stripAt(x, y)
        if (strip != null) {
            val p = Ptr(id, null, x).also { it.strip = strip }
            ptrs[id] = p
            if (strip.kind == StripKind.SETTINGS) {
                // r10: tap ⚙ = quick panel, hold ⚙ = Settings (QS4)
                handler.postAtTime({ p.longFired = true; listener?.onLongPressOpened(); listener?.onOpenSettings() }, p, SystemClock.uptimeMillis() + 500)
            } else if (strip.kind == StripKind.PASTE) {
                handler.postAtTime({ p.longFired = true; listener?.onLongPressOpened(); listener?.onClipboardButton() }, p, SystemClock.uptimeMillis() + 500)
            }
            invalidate()
            return
        }
        railAt(x, y)?.let { side ->
            ptrs[id] = Ptr(id, null, x).also { it.rail = side }
            invalidate()
            return
        }
        val box = boxAt(x, y) ?: return
        val p = Ptr(id, box, x).also { it.lastY = y }
        p.held = HeldKey(box.key.type, x, y, t, dp, settings.spaceFlick)
        p.guard = PointerGuard(keyPx).also { it.down(x, y, t) }
        ptrs[id] = p
        listener?.onKeyDown(box.key)
        when (box.key.type) {
            KeyType.BACKSPACE -> {
                listener?.onKeyCommit(box.key)
                scheduleRepeat(p, REPEAT_START_MS)
            }
            KeyType.CHAR -> if (box.key.variants.isNotEmpty()) scheduleLongPress(p)
            KeyType.SPACE -> scheduleLongPress(p)
            else -> Unit
        }
        invalidate()
    }

    private fun scheduleLongPress(p: Ptr) {
        handler.postAtTime({ longPress(p) }, p, SystemClock.uptimeMillis() + settings.longPressMs)
    }

    private fun scheduleRepeat(p: Ptr, delay: Long) {
        handler.postAtTime({
            val b = p.box
            if (ptrs[p.id] === p && b != null) {
                listener?.onKeyCommit(b.key)
                scheduleRepeat(p, REPEAT_MS)
            }
        }, p, SystemClock.uptimeMillis() + delay)
    }

    private fun longPress(p: Ptr) {
        val b = p.box ?: return
        if (ptrs[p.id] !== p) return
        when (b.key.type) {
            KeyType.SPACE -> if (!p.cursorMode) { p.longFired = true; listener?.onSpaceLongPress() }
            KeyType.CHAR -> {
                val items = Variants.applyShift(b.key.variants, shift != ShiftState.OFF)
                if (items.isEmpty()) return
                val cell = max(b.rect.width(), 34 * dp)
                val popup = PopupGeometry.variants(b.rect.left, b.rect.width(), items.size, cell, width.toFloat(), 4 * dp)
                p.popup = popup
                p.popupAt = SystemClock.uptimeMillis()
                p.popupItems = items
                p.sel = 0
                p.popupTop = PopupGeometry.previewTop(b.rect.top - 4 * dp, rowH * 0.95f)
                listener?.onLongPressOpened()
                invalidate()
            }
            else -> Unit
        }
    }

    private fun move(id: Int, x: Float, y: Float, t: Long) {
        if (id == quickPtr) { quickMove(x, y); return }
        if (id == editPtr) return
        if (id == emojiPtr) { emojiMove(x, y); return }
        if (id == panelPtr) { panelMove(y); return }
        landing[id]?.let { g ->
            // r13h (JG3): the pointer that jumped starts its new touch where it settles
            if (g.feed(x, y, t) == PointerGuard.Verdict.SETTLED) { landing.remove(id); down(id, x, y, t) }
            return
        }
        val p = ptrs[id] ?: return
        keyMove(p, x, y, t, lift = false)
    }

    /**
     * One sample of a held key's finger. r13h (G2): the guard first — a JUMP ends this touch at its last calm sample
     * (its tap) and the pointer lands as a new touch; a FAST sample's effects wait for a calm one, or for the lift
     * ([lift]: the lift applies only what a FAST run left waiting). Returns true when a jump ended the touch.
     */
    private fun keyMove(p: Ptr, x: Float, y: Float, t: Long, lift: Boolean): Boolean {
        val b = p.box ?: return false
        val g = p.guard
        if (g != null) {
            when (g.feed(x, y, t)) {
                PointerGuard.Verdict.JUMP -> {
                    endAtJump(p, g)
                    return true
                }
                PointerGuard.Verdict.FAST, PointerGuard.Verdict.LANDING -> if (!lift) { p.pending = true; return false }
                else -> Unit
            }
        }
        if (lift && !p.pending) return false
        p.pending = false
        p.lastX = x; p.lastY = y
        val popup = p.popup
        if (popup != null) {
            val s = popup.indexAt(x)
            if (s != p.sel) { p.sel = s; invalidate() }
            return false
        }
        val held = p.held ?: return false
        for (a in held.move(x, y, t, p.longFired)) when (a) {
            // r10 (UX-6): a leftward swipe on ⌫ deletes the previous word (QE6), once per touch
            HeldKey.Act.DeleteWord -> { handler.removeCallbacksAndMessages(p); listener?.onDeleteWord(); invalidate() }
            // r10 (FL1/FL2), r13f (SG1/SG2), r13h (LF1/LF3): a deliberate vertical swipe; no picker after it (FL4)
            HeldKey.Act.Flick -> { handler.removeCallbacksAndMessages(p); listener?.onSpaceFlick(); invalidate() }
            HeldKey.Act.Swipe -> { handler.removeCallbacksAndMessages(p); invalidate() }
            // r13f (SG2): the drag counts from where the finger landed
            HeldKey.Act.CursorStart -> handler.removeCallbacksAndMessages(p)
            is HeldKey.Act.Cursor -> listener?.onCursorDrag(a.steps)
            is HeldKey.Act.Over -> {
                val nb = boxAt(a.x, a.y)
                if (nb != null && nb !== b && nb.key.type == KeyType.CHAR) {
                    handler.removeCallbacksAndMessages(p)
                    b.releasedAt = SystemClock.uptimeMillis()
                    p.box = nb
                    if (nb.key.variants.isNotEmpty()) scheduleLongPress(p)
                    invalidate()
                }
            }
        }
        return false
    }

    /** r13h (JG1): the touch ends at its last calm sample, as if lifted there; the pointer is landing (JG3). */
    private fun endAtJump(p: Ptr, g: PointerGuard) {
        release(p.id, g.calmX, g.calmY)
        landing[p.id] = g
    }

    private fun up(id: Int, x: Float, y: Float, t: Long) {
        if (id == quickPtr) { quickUp(x, y); return }
        if (id == editPtr) { editUp(x, y); return }
        if (id == emojiPtr) { emojiUp(x, y); return }
        if (id == panelPtr) { panelUp(x, y); return }
        // r13h (JG1/JG3): a lift while landing, or a jump on the lift itself, is a tap where it lifted
        if (landing.remove(id) != null) { down(id, x, y, t); release(id, x, y); return }
        val p = ptrs[id]
        if (p != null && keyMove(p, x, y, t, lift = true)) {
            landing.remove(id)
            down(id, x, y, t)
        }
        release(id, x, y)
    }

    private fun release(id: Int, x: Float, y: Float) {
        val p = ptrs.remove(id) ?: return
        handler.removeCallbacksAndMessages(p)
        val strip = p.strip
        if (strip != null) {
            if (!p.longFired && stripAt(x, y) === strip) stripTap(strip)
            invalidate()
            return
        }
        p.rail?.let { side ->
            if (railAt(x, y) == side) listener?.onOneHand(side)
            invalidate()
            return
        }
        val b = p.box ?: return
        b.releasedAt = SystemClock.uptimeMillis()
        val popup = p.popup
        val held = p.held
        when {
            popup != null -> listener?.onVariant(p.popupItems[p.sel])
            p.longFired || held?.cursorMode == true || held?.flicked == true -> Unit
            b.key.type == KeyType.BACKSPACE || b.key.type == KeyType.SHIFT -> Unit
            b.key.type == KeyType.EMOJI -> listener?.onEmojiKey()
            else -> {
                if (b.key.type == KeyType.CHAR) listener?.onKeyNear(nearKeys(b, x, y))
                listener?.onKeyCommit(b.key)
            }
        }
        listener?.onKeyUp(b.key)
        invalidate()
    }

    private fun stripTap(s: StripItem) {
        when (s.kind) {
            StripKind.SETTINGS -> listener?.onQuickPanel()
            StripKind.SUGGESTION -> listener?.onSuggestion(s.text)
            StripKind.OFFER -> listener?.onContextOffer()
            StripKind.PASTE -> listener?.onPasteOffer()
            StripKind.NO_MEMORY -> listener?.onNoMemory()
            StripKind.DAYNIGHT -> listener?.onDayNight()
            StripKind.UPDATE -> if (!chipExiting) listener?.onUpdateChip()
            StripKind.UPDATE_X -> if (!chipExiting) listener?.onUpdateDismiss()
            StripKind.GIF_BACK -> listener?.onGifSearchBack()
            StripKind.GIF_GO -> listener?.onGifSearchGo()
            StripKind.GIF_FIELD -> Unit
            StripKind.ES_BACK -> listener?.onEmojiSearchBack()
            StripKind.ES_CLEAR -> listener?.onEmojiSearchClear()
            StripKind.ES_FIELD -> Unit
        }
    }

    // ── r15: the results band: one finger, tap = insert, a sideways drag scrolls ──
    private var bandPtr = -1
    private var bandHit = -1
    private var bandDownX = 0f
    private var bandLastX = 0f
    private var bandScrolling = false

    private fun bandIndexAt(x: Float): Int = esHits.firstOrNull { x >= it.second.left && x < it.second.right }?.first ?: -1

    private fun bandDown(id: Int, x: Float) {
        if (bandPtr != -1) return
        bandPtr = id
        bandHit = bandIndexAt(x)
        bandDownX = x; bandLastX = x
        bandScrolling = false
        invalidate()
    }

    private fun bandMove(x: Float) {
        if (!bandScrolling && abs(x - bandDownX) > 10 * dp) { bandScrolling = true; bandHit = -1 }
        if (bandScrolling) {
            val s = (esScroll + bandLastX - x).coerceIn(0f, bandMaxScroll())
            if (s != esScroll) { esScroll = s; layoutBand(); invalidate(); a11y.invalidateRoot() }
        }
        bandLastX = x
    }

    private fun bandUp(x: Float) {
        val h = bandHit
        val scrolled = bandScrolling
        bandPtr = -1; bandHit = -1; bandScrolling = false
        if (!scrolled && h >= 0 && bandIndexAt(x) == h) bandAct(h)
        invalidate()
    }

    private fun bandAct(i: Int) {
        val r = esResults.getOrNull(i) ?: return
        listener?.onEmojiSearchPick(r.emoji)
    }

    // ── quick panel touch: one finger, tap = act ──────────────────────────
    private var quickPtr = -1
    private var quickHit: QuickPanel.Hit? = null
    /** r15 (QP11): a touch that started on a tema row that scrolls, where it started, and whether it became a scroll. */
    private var quickRow = false
    private var quickDownX = 0f
    private var quickLastX = 0f
    private var quickScrolling = false

    private fun quickDown(id: Int, x: Float, y: Float) {
        if (quickPtr != -1) return
        quickPtr = id
        quickHit = quickPanel.hitAt(x, y)?.takeIf { it.enabled }
        quickRow = quickPanel.scrollsAt(x, y)
        quickDownX = x; quickLastX = x
        quickScrolling = false
        invalidate()
    }

    private fun quickMove(x: Float, y: Float) {
        if (quickRow && !quickScrolling && abs(x - quickDownX) > quickPanel.slop) { quickScrolling = true; quickHit = null; invalidate() }
        if (quickScrolling && quickPanel.scrollBy(quickLastX - x)) { invalidate(); a11y.invalidateRoot() }
        quickLastX = x
    }

    private fun quickUp(x: Float, y: Float) {
        quickPtr = -1
        val h = quickHit
        quickHit = null
        val scrolled = quickScrolling
        quickScrolling = false
        if (!scrolled && h != null && quickPanel.hitAt(x, y) === h) quickAct(h)
        invalidate()
    }

    private fun quickAct(h: QuickPanel.Hit) {
        if (!h.enabled) return
        when (h.act) {
            QuickPanel.Act.CLOSE -> hideQuick()
            QuickPanel.Act.UPDATE -> { hideQuick(); listener?.onUpdateChip() }
            QuickPanel.Act.TILE -> h.action?.let { listener?.onQuickAction(it) }
            QuickPanel.Act.TEMA -> h.tema?.let { listener?.onQuickTema(it) }
            QuickPanel.Act.PERSONALIZE -> listener?.onPersonalize()
        }
        invalidate(); a11y.invalidateRoot()
    }

    // ── edit panel touch: tap = op; hold a move / Borrar palabra = repeat (QE3) ──
    private val editToken = Any()
    private var editPtr = -1
    private var editHit: EditPanel.Hit? = null

    private fun editDown(id: Int, x: Float, y: Float) {
        if (editPtr != -1) return
        editPtr = id
        val h = editPanel.hitAt(x, y)?.takeIf { it.enabled }
        editHit = h
        val op = h?.op
        if (h != null && op != null && EditPad.repeats(op)) {
            listener?.onEditOp(op)
            scheduleEditRepeat(op, REPEAT_START_MS)
        }
        invalidate()
    }

    private fun scheduleEditRepeat(op: EditOp, delay: Long) {
        handler.postAtTime({
            if (editPtr != -1 && editHit?.op == op) {
                listener?.onEditOp(op)
                scheduleEditRepeat(op, EDIT_REPEAT_MS)
            }
        }, editToken, SystemClock.uptimeMillis() + delay)
    }

    private fun editUp(x: Float, y: Float) {
        handler.removeCallbacksAndMessages(editToken)
        editPtr = -1
        val h = editHit
        editHit = null
        // repeating ops already fired on down; one-shots fire on a clean release
        if (h != null && editPanel.hitAt(x, y) === h && (h.op == null || !EditPad.repeats(h.op))) editAct(h)
        invalidate()
    }

    private fun editAct(h: EditPanel.Hit) {
        if (!h.enabled) return
        when (h.act) {
            EditPanel.Act.CLOSE -> hideEdit()
            EditPanel.Act.OP -> h.op?.let { listener?.onEditOp(it) }
        }
        invalidate(); a11y.invalidateRoot()
    }

    private fun railAt(x: Float, y: Float): OneHand? =
        if (y < stripH) null else rail.firstOrNull { it.second.contains(x, y) }?.first

    // ── emoji panel touch: tap = act, drag = scroll the grid, hold ⌫ = repeat ──
    private val emojiToken = Any()
    private var emojiPtr = -1
    private var emojiHit: EmojiPanel.Hit? = null
    private var emojiDownY = 0f
    private var emojiLastY = 0f
    private var emojiScrolling = false
    /** r11: this press opened the skin tones (a lift on a tone picks it; elsewhere keeps them open). */
    private var emojiTonesByPress = false

    private fun emojiDown(id: Int, x: Float, y: Float) {
        if (emojiPtr != -1) return
        emojiPtr = id
        val hit = emojiPanel.hitAt(x, y)
        emojiHit = hit
        emojiDownY = y; emojiLastY = y
        emojiScrolling = false
        emojiTonesByPress = false
        if (hit?.act == EmojiPanel.Act.DELETE) {
            listener?.onEmojiPanel(EmojiPanel.Act.DELETE, "")
            scheduleEmojiRepeat(REPEAT_START_MS)
        }
        if (hit?.act == EmojiPanel.Act.EMOJI && hit.cell?.tones?.isNotEmpty() == true) {
            // r11 (ET4): hold an emoji with skin tones = its tones; the lift on one picks it
            handler.postAtTime({
                if (emojiPtr == id && !emojiScrolling && emojiHit === hit && emojiPanel.showTones(hit)) {
                    emojiTonesByPress = true
                    emojiHit = null
                    listener?.onLongPressOpened()
                    invalidate(); a11y.invalidateRoot()
                }
            }, emojiToken, SystemClock.uptimeMillis() + settings.longPressMs)
        }
        invalidate()
    }

    private fun scheduleEmojiRepeat(delay: Long) {
        handler.postAtTime({
            if (emojiPtr != -1 && emojiHit?.act == EmojiPanel.Act.DELETE) {
                listener?.onEmojiPanel(EmojiPanel.Act.DELETE, "")
                scheduleEmojiRepeat(REPEAT_MS)
            }
        }, emojiToken, SystemClock.uptimeMillis() + delay)
    }

    private fun emojiMove(x: Float, y: Float) {
        if (emojiPanel.tonesOf != null) {
            // slide across the tones: the one under the finger is highlighted
            val t = emojiPanel.hitAt(x, y)?.takeIf { it.act == EmojiPanel.Act.TONE }
            if (t !== emojiHit) { emojiHit = t; invalidate() }
            return
        }
        val h = emojiHit
        val gifScrolls = h?.act == EmojiPanel.Act.GIF && h.gif?.act.let { it == GifPanel.Act.ITEM || it == GifPanel.Act.ATTRIBUTION }
        val inGrid = h == null || h.act == EmojiPanel.Act.EMOJI || gifScrolls
        if (!emojiScrolling && inGrid && abs(y - emojiDownY) > 10 * dp) {
            emojiScrolling = true
            emojiHit = null
        }
        if (emojiScrolling && emojiPanel.scrollBy(emojiLastY - y)) {
            invalidate(); a11y.invalidateRoot()
            if (emojiPanel.onGif) listener?.onGifScrolled()
        }
        emojiLastY = y
    }

    private fun emojiUp(x: Float, y: Float) {
        handler.removeCallbacksAndMessages(emojiToken)
        emojiPtr = -1
        val h = emojiHit
        emojiHit = null
        val byPress = emojiTonesByPress
        emojiTonesByPress = false
        if (emojiPanel.tonesOf != null) {
            val t = emojiPanel.hitAt(x, y)
            when {
                t?.act == EmojiPanel.Act.TONE -> emojiAct(t)
                !byPress -> { emojiPanel.hideTones(); a11y.invalidateRoot() } // a tap outside closes them
            }
            invalidate()
            return
        }
        if (!emojiScrolling && h != null && h.act != EmojiPanel.Act.DELETE && emojiPanel.hitAt(x, y)?.let { it.act == h.act && it.index == h.index } == true) emojiAct(h)
        invalidate()
    }

    private fun emojiAct(h: EmojiPanel.Hit) {
        when (h.act) {
            EmojiPanel.Act.TAB -> {
                val was = emojiPanel.onGif
                emojiPanel.selectTab(h.index)
                if (was != emojiPanel.onGif) listener?.onGifTab(emojiPanel.onGif)
            }
            EmojiPanel.Act.GIF -> h.gif?.let { listener?.onGifPanel(it) }
            EmojiPanel.Act.CLOSE_TONES -> emojiPanel.hideTones()
            EmojiPanel.Act.TONE -> {
                val cell = h.cell
                // the service stores the new default first: the grid laid out by hideTones shows it (ET1)
                if (cell != null) listener?.onEmojiTone(cell, h.text)
                emojiPanel.hideTones()
            }
            else -> listener?.onEmojiPanel(h.act, h.text)
        }
        invalidate(); a11y.invalidateRoot()
    }

    // ── clipboard panel touch: one finger; tap = act, hold a row = preview, drag = scroll ──
    private val panelToken = Any()
    private var panelPtr = -1
    private var panelHit: ClipboardPanel.Hit? = null
    private var panelDownY = 0f
    private var panelLastY = 0f
    private var panelScrolling = false
    private var panelLong = false

    private fun panelDown(id: Int, x: Float, y: Float) {
        if (panelPtr != -1) return
        panelPtr = id
        val hit = clipPanel.hitAt(x, y)
        panelHit = hit
        panelDownY = y; panelLastY = y
        panelScrolling = false; panelLong = false
        if (hit?.act == ClipboardPanel.Act.ROW) {
            handler.postAtTime({
                if (panelPtr == id && !panelScrolling) {
                    panelLong = true
                    clipPanel.showDetail(hit.id)
                    listener?.onLongPressOpened()
                    invalidate(); a11y.invalidateRoot()
                }
            }, panelToken, SystemClock.uptimeMillis() + 450)
        }
        invalidate()
    }

    private fun panelMove(y: Float) {
        if (!panelScrolling && !panelLong && abs(y - panelDownY) > 10 * dp) {
            panelScrolling = true
            panelHit = null
            handler.removeCallbacksAndMessages(panelToken)
        }
        if (panelScrolling && clipPanel.scrollBy(panelLastY - y)) { invalidate(); a11y.invalidateRoot() }
        panelLastY = y
    }

    private fun panelUp(x: Float, y: Float) {
        handler.removeCallbacksAndMessages(panelToken)
        panelPtr = -1
        val h = panelHit
        panelHit = null
        if (!panelScrolling && !panelLong && h != null && clipPanel.hitAt(x, y) === h) panelAct(h)
        invalidate()
    }

    private fun panelAct(h: ClipboardPanel.Hit) {
        when (h.act) {
            ClipboardPanel.Act.CLEAR_ASK -> clipPanel.askClear(true)
            ClipboardPanel.Act.CLEAR_NO -> clipPanel.askClear(false)
            ClipboardPanel.Act.BACK -> clipPanel.back()
            else -> listener?.onClipboardPanel(h.act, h.id)
        }
        invalidate(); a11y.invalidateRoot()
    }

    private fun cancelPointers() {
        bandPtr = -1; bandHit = -1; bandScrolling = false
        handler.removeCallbacksAndMessages(editToken)
        editPtr = -1
        editHit = null
        quickPtr = -1
        quickHit = null
        quickScrolling = false
        handler.removeCallbacksAndMessages(panelToken)
        panelPtr = -1
        panelHit = null
        handler.removeCallbacksAndMessages(emojiToken)
        emojiPtr = -1
        emojiHit = null
        landing.clear()
        for (p in ptrs.values) {
            handler.removeCallbacksAndMessages(p)
            val k = p.box?.key
            if (k != null) listener?.onKeyUp(k)
        }
        ptrs.clear()
    }

    override fun onDetachedFromWindow() {
        chipAnimator?.cancel()
        cancelPointers()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // ── drawing ──────────────────────────────────────────────────────────
    /** r14 (MO1): 0…1 progress of an effect of [ms] started at [at] (1 when motion is off); flags the frame to continue. */
    private fun grow(at: Long, now: Long, ms: Long): Float {
        val d = motion.ms(ms, animScale)
        if (d <= 0L || at <= 0L) return 1f
        val f = (now - at).toFloat() / d
        if (f < 1f) { animating = true; return f.coerceAtLeast(0f) }
        return 1f
    }

    /** Ease-out (cubic): fast start, soft landing. */
    private fun ease(f: Float): Float { val g = 1f - f; return 1f - g * g * g }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        animating = false
        val from = fadeFrom
        val to = fadeTo
        if (from != null && to != null) {
            // r14: a tema switch or a day/night flip cross-fades its colours (≤ 200 ms)
            val f = grow(fadeAt, now, 200)
            palette = if (f >= 1f) { fadeFrom = null; fadeTo = null; to } else Palette.mix(from, to, ease(f))
            painter.reset()
        }
        canvas.drawColor(Drawn.surface(palette, Surface.BACKGROUND))
        // r15 (ES11): the results band on top while searching; the bar and the keys below it, shifted by its height
        val band = bandH
        if (band > 0f) drawBand(canvas)
        val shifted = canvas.save()
        if (band > 0f) canvas.translate(0f, band)
        drawBody(canvas, now)
        canvas.restoreToCount(shifted)
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawBody(canvas: Canvas, now: Long) {
        if (panelOpen) {
            // r14: a panel rises 14 dp into place (≤ 160 ms); taps act at once
            val e = ease(grow(panelAt, now, 160))
            canvas.save()
            canvas.translate(0f, (1f - e) * 14 * dp)
            when {
                clipboardOpen -> clipPanel.draw(canvas, palette, panelRadius(), ::typeface, if (panelScrolling || panelLong) null else panelHit)
                emojiOpen -> emojiPanel.draw(canvas, palette, panelRadius(), ::typeface, if (emojiScrolling) null else emojiHit)
                quickOpen -> quickPanel.draw(canvas, palette, panelRadius(), ::typeface, quickHit, mode)
                editOpen -> editPanel.draw(canvas, palette, panelRadius(), ::typeface, editHit)
            }
            canvas.restore()
            return
        }
        drawStrip(canvas, now)
        drawRail(canvas)
        pressedNow.clear()
        for (p in ptrs.values) p.box?.let { if (!p.cursorMode) pressedNow += it }
        for (b in boxes) drawKey(canvas, b, pressOf(b, now))
        for (p in ptrs.values) {
            val b = p.box ?: continue
            val pop = p.popup
            if (pop != null) drawVariants(canvas, p, pop, now)
            else if (settings.popups && !secretField && b.key.type == KeyType.CHAR && !p.cursorMode) drawPreview(canvas, p, b, now)
        }
    }

    /**
     * r15 (ES): the results band: the matches in a row that scrolls sideways (the first ringed: Enter inserts it), or one
     * line (the hint, «preparing», «no emoji for …»). Emoji are colour glyphs; the line is muted text on the background.
     */
    private fun drawBand(c: Canvas) {
        val t = palette.theme
        fill.shader = null
        val bh = bandH
        if (esResults.isEmpty()) {
            val line = esStatus ?: ""
            text.textAlign = Paint.Align.LEFT
            text.color = Drawn.ink(palette, Surface.BACKGROUND, Ink.MUTED)
            text.textSize = 14 * dp * Legend.fontFactor(fontScale).coerceAtMost(1.3f)
            text.typeface = typeface(500)
            val x0 = 16 * dp
            KeyIcons.draw(c, Icon.SEARCH, x0 + 8 * dp, bh / 2, 15 * dp, Drawn.ink(palette, Surface.BACKGROUND, Ink.MUTED), stroke, fill)
            val shown = ellipsize(line, width - x0 - 40 * dp)
            val fm = text.fontMetrics
            c.drawText(shown, x0 + 26 * dp, bh / 2 - (fm.ascent + fm.descent) / 2, text)
            text.textAlign = Paint.Align.CENTER
        } else {
            c.save()
            c.clipRect(0f, 0f, width.toFloat(), bh)
            val glyph = min(bandCell() * 0.58f, 28 * dp)
            for ((i, r) in esHits) {
                val pressed = i == bandHit && !bandScrolling
                tmp.set(r.left + 3 * dp, r.top + 2 * dp, r.right - 3 * dp, r.bottom - 2 * dp)
                if (pressed) {
                    fill.color = Drawn.surface(palette, Surface.PRESSED)
                    c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill)
                }
                if (i == 0) { // the one Enter inserts
                    stroke.color = palette.accentGlow
                    stroke.strokeWidth = 1.5f * dp
                    c.drawRoundRect(tmp, panelRadius(), panelRadius(), stroke)
                }
                text.color = if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.TEXT) else Drawn.ink(palette, Surface.BACKGROUND, Ink.TEXT)
                text.textSize = glyph
                text.typeface = Typeface.DEFAULT
                drawCentered(c, esResults[i].emoji, r.centerX(), r.centerY())
            }
            c.restore()
            // more to the side: a scroll track under the row
            val ms = bandMaxScroll()
            if (ms > 0f) {
                val w = width.toFloat()
                val len = max(24 * dp, w * w / (w + ms))
                val x = (w - len) * (esScroll / ms)
                fill.color = t.edgeHi
                c.drawRoundRect(x, bh - 3 * dp, x + len, bh - 1.5f * dp, dp, dp, fill)
            }
        }
        fill.color = t.edge
        c.drawRect(0f, bh - 1, width.toFloat(), bh, fill)
        c.drawRect(0f, 0f, width.toFloat(), 1f, fill) // the band's top edge: it reads as the keyboard's, not the app's
    }

    /** 1 while held; after the release it fades out (≤ 110 ms) so the key reads as coming back up. */
    private fun pressOf(b: Box, now: Long): Float {
        if (b in pressedNow) return 1f
        if (b.releasedAt <= 0L || motion == Motion.OFF) return 0f
        val f = grow(b.releasedAt, now, if (motion == Motion.SUBTLE) 90 else 110)
        return if (f >= 1f) { b.releasedAt = 0L; 0f } else 1f - f
    }

    /** Panels draw rounded cards: the key shape's radius, never a capsule (a PILL key would make every row one). */
    private fun panelRadius() = min(settings.shape.radiusDp, 16f) * dp

    private fun drawStrip(c: Canvas, now: Long) {
        val t = palette.theme
        fill.shader = null
        fill.color = t.edge
        c.drawRect(0f, stripH - 1, width.toFloat(), stripH, fill)
        pressedStrip.clear()
        for (p in ptrs.values) p.strip?.let { pressedStrip += it }
        var firstSuggestion = true
        val chips = settings.barStyle == BarStyle.CHIPS
        for (s in stripItems) {
            val r = s.rect
            when (s.kind) {
                StripKind.PASTE -> drawChip(c, r, s in pressedStrip, Icon.CLIPBOARD, s.text, 14f, 600, 1f)
                StripKind.OFFER -> {
                    // r13f (offers, CH2): an accent pill with ⇄ and the word it puts in; r14: it arrives (fade + 10 dp slide)
                    val f = ease(grow(offerAt, now, 180))
                    if (f < 1f) {
                        val save = c.saveLayerAlpha(r.left - 12 * dp, 0f, r.right + 2 * dp, stripH, (f * 255).toInt())
                        c.translate((1f - f) * 10 * dp, 0f)
                        drawChip(c, r, s in pressedStrip, Icon.SWAP, s.text, 15f, 650, 0.93f)
                        c.restoreToCount(save)
                    } else drawChip(c, r, s in pressedStrip, Icon.SWAP, s.text, 15f, 650, 0.93f)
                }
                StripKind.DAYNIGHT -> {
                    val pressed = s in pressedStrip
                    if (pressed) {
                        fill.color = Drawn.surface(palette, Surface.PRESSED)
                        tmp.set(r.left + 3 * dp, 6 * dp, r.right - 3 * dp, stripH - 6 * dp)
                        c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill)
                    }
                    KeyIcons.draw(c, if (dayNight == true) Icon.SUN else Icon.MOON, r.centerX(), r.centerY(), 17 * dp,
                        if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(palette, Surface.BACKGROUND, Ink.MUTED), stroke, fill)
                }
                StripKind.SETTINGS -> {
                    val on = s in pressedStrip
                    if (on) {
                        fill.color = Drawn.surface(palette, Surface.PRESSED)
                        tmp.set(r.left + 4 * dp, 6 * dp, r.right - 4 * dp, stripH - 6 * dp)
                        c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill)
                    }
                    KeyIcons.draw(c, Icon.GEAR, r.centerX(), r.centerY(), 19 * dp, when {
                        on -> Drawn.ink(palette, Surface.PRESSED, Ink.ACCENT)
                        gearDot -> Drawn.ink(palette, Surface.BACKGROUND, Ink.ACCENT)
                        else -> Drawn.ink(palette, Surface.BACKGROUND, Ink.MUTED)
                    }, stroke, fill)
                    if (gearDot) { // r9: an update waits — amber dot, ringed in the strip color
                        fill.color = Drawn.surface(palette, Surface.BACKGROUND)
                        c.drawCircle(r.centerX() + 8 * dp, r.centerY() - 8 * dp, 5 * dp, fill)
                        fill.color = Drawn.ink(palette, Surface.BACKGROUND, Ink.ACCENT)
                        c.drawCircle(r.centerX() + 8 * dp, r.centerY() - 8 * dp, 3.5f * dp, fill)
                    }
                }
                StripKind.NO_MEMORY -> drawNoMemory(c, r, s in pressedStrip)
                StripKind.GIF_BACK -> {
                    val pressed = s in pressedStrip
                    if (pressed) { fill.color = Drawn.surface(palette, Surface.PRESSED); tmp.set(r.left + 4 * dp, 6 * dp, r.right - 4 * dp, stripH - 6 * dp); c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill) }
                    KeyIcons.draw(c, Icon.ARROW_LEFT, r.centerX(), r.centerY(), 18 * dp,
                        if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.TEXT_MOD) else Drawn.ink(palette, Surface.BACKGROUND, Ink.TEXT_MOD), stroke, fill)
                }
                StripKind.GIF_FIELD -> {
                    fill.color = Drawn.surface(palette, Surface.PLAIN)
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                    stroke.color = palette.accentGlow
                    stroke.strokeWidth = 1 * dp
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
                    text.textAlign = Paint.Align.LEFT
                    text.textSize = 15 * dp
                    val empty = s.text.isEmpty()
                    text.color = if (empty) Drawn.ink(palette, Surface.PLAIN, Ink.MUTED) else Drawn.ink(palette, Surface.PLAIN, Ink.TEXT)
                    text.typeface = typeface(if (empty) 450 else 550)
                    val label = ellipsize(if (empty) com.resyst.vk.core.GifCopy.placeholder(tx) else s.text, r.width() - 34 * dp)
                    val fm = text.fontMetrics
                    val baseline = r.centerY() - (fm.ascent + fm.descent) / 2
                    c.drawText(label, r.left + 14 * dp, baseline, text)
                    // the caret: this box, not the app's field, receives the keys
                    val cx = r.left + 14 * dp + (if (empty) 0f else text.measureText(label)) + 2 * dp
                    fill.color = Drawn.ink(palette, Surface.PLAIN, Ink.ACCENT)
                    c.drawRect(cx, r.centerY() - 9 * dp, cx + 1.5f * dp, r.centerY() + 9 * dp, fill)
                    text.textAlign = Paint.Align.CENTER
                }
                StripKind.GIF_GO -> {
                    val pressed = s in pressedStrip
                    tmp.set(r.left + 4 * dp, 6 * dp, r.right - 6 * dp, stripH - 6 * dp)
                    fill.color = Drawn.surface(palette, if (pressed) Surface.SOLID_PRESSED else Surface.SOLID)
                    c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
                    text.color = if (pressed) Drawn.ink(palette, Surface.SOLID_PRESSED, Ink.ACCENT_INK) else Drawn.ink(palette, Surface.SOLID, Ink.ACCENT_INK)
                    text.textSize = 14 * dp
                    text.typeface = typeface(650)
                    drawCentered(c, s.text, tmp.centerX(), tmp.centerY())
                }
                StripKind.ES_BACK -> {
                    val pressed = s in pressedStrip
                    if (pressed) { fill.color = Drawn.surface(palette, Surface.PRESSED); tmp.set(r.left + 4 * dp, 6 * dp, r.right - 4 * dp, stripH - 6 * dp); c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill) }
                    KeyIcons.draw(c, Icon.ARROW_LEFT, r.centerX(), r.centerY(), 18 * dp,
                        if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.TEXT_MOD) else Drawn.ink(palette, Surface.BACKGROUND, Ink.TEXT_MOD), stroke, fill)
                }
                StripKind.ES_CLEAR -> Unit // drawn after the loop: on top of its box
                StripKind.ES_FIELD -> {
                    // r15 (ES): the box the keys type into (not the app's field): a magnifier, the query and its caret
                    fill.color = Drawn.surface(palette, Surface.PLAIN)
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                    stroke.color = palette.accentGlow
                    stroke.strokeWidth = 1 * dp
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
                    KeyIcons.draw(c, Icon.SEARCH, r.left + 18 * dp, r.centerY(), 15 * dp, Drawn.ink(palette, Surface.PLAIN, Ink.MUTED), stroke, fill)
                    text.textAlign = Paint.Align.LEFT
                    text.textSize = 15 * dp * Legend.fontFactor(fontScale).coerceAtMost(1.25f)
                    val empty = s.text.isEmpty()
                    text.color = if (empty) Drawn.ink(palette, Surface.PLAIN, Ink.MUTED) else Drawn.ink(palette, Surface.PLAIN, Ink.TEXT)
                    text.typeface = typeface(if (empty) 450 else 550)
                    val room = r.width() - 48 * dp - (if (s.text.isNotEmpty()) 36 * dp else 0f)
                    // the end of a long query stays visible (the caret is there)
                    var label = if (empty) tx.get(R.string.emoji_search_placeholder) else s.text
                    if (!empty && text.measureText(label) > room) {
                        var from = 0
                        while (from < label.length - 1 && text.measureText("…" + label.substring(from)) > room) from++
                        label = "…" + label.substring(from)
                    } else if (empty) label = ellipsize(label, room)
                    val fm = text.fontMetrics
                    val x0 = r.left + 34 * dp
                    c.drawText(label, x0, r.centerY() - (fm.ascent + fm.descent) / 2, text)
                    val cx = (if (empty) x0 else x0 + text.measureText(label)) + 2 * dp
                    fill.color = Drawn.ink(palette, Surface.PLAIN, Ink.ACCENT)
                    c.drawRect(cx, r.centerY() - 9 * dp, cx + 1.5f * dp, r.centerY() + 9 * dp, fill)
                    text.textAlign = Paint.Align.CENTER
                }
                StripKind.UPDATE_X -> Unit // drawn with its chip
                StripKind.UPDATE -> drawUpdateChip(c, s, pressedStrip)
                StripKind.SUGGESTION -> {
                    val pressed = s in pressedStrip
                    if (chips) {
                        // r14: suggestions as chips (one surface each, the first in the accent's chip colours)
                        tmp.set(r.left + 3 * dp, 7 * dp, r.right - 3 * dp, stripH - 7 * dp)
                        val rad = min(tmp.height() / 2, panelRadius() + 4 * dp)
                        fill.color = Drawn.surface(palette, when {
                            pressed -> Surface.PRESSED
                            firstSuggestion -> Surface.CHIP
                            else -> Surface.PLAIN
                        })
                        c.drawRoundRect(tmp, rad, rad, fill)
                        stroke.color = if (firstSuggestion) palette.accentGlow else t.edge
                        stroke.strokeWidth = dp
                        c.drawRoundRect(tmp, rad, rad, stroke)
                    } else if (pressed) {
                        fill.color = Drawn.surface(palette, Surface.PRESSED)
                        tmp.set(r.left + 2 * dp, 5 * dp, r.right - 2 * dp, stripH - 5 * dp)
                        c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill)
                    }
                    text.color = when {
                        pressed && firstSuggestion -> Drawn.ink(palette, Surface.PRESSED, Ink.ACCENT)
                        pressed -> Drawn.ink(palette, Surface.PRESSED, Ink.TEXT)
                        chips && firstSuggestion -> Drawn.ink(palette, Surface.CHIP, Ink.ACCENT)
                        chips -> Drawn.ink(palette, Surface.PLAIN, Ink.TEXT)
                        firstSuggestion -> Drawn.ink(palette, Surface.BACKGROUND, Ink.ACCENT)
                        else -> Drawn.ink(palette, Surface.BACKGROUND, Ink.TEXT)
                    }
                    text.textSize = 16 * dp * Legend.fontFactor(fontScale).coerceAtMost(1.25f)
                    text.typeface = typeface(if (firstSuggestion) 650 else 450)
                    val label = ellipsize(s.text, r.width() - (if (chips) 18 else 12) * dp)
                    drawCentered(c, label, r.centerX(), r.centerY())
                    if (!firstSuggestion && !chips) {
                        fill.color = t.edge
                        c.drawRect(r.left, stripH * 0.3f, r.left + dp, stripH * 0.7f, fill)
                    }
                    firstSuggestion = false
                }
            }
        }
        stripItems.firstOrNull { it.kind == StripKind.ES_CLEAR }?.let { drawSearchClear(c, it, it in pressedStrip) }
    }

    /** r15 (ES): the search box's ✕, inside its right end (the box's key colour under it, the pressed colour while held). */
    private fun drawSearchClear(c: Canvas, s: StripItem, pressed: Boolean) {
        val r = s.rect
        if (pressed) { fill.color = Drawn.surface(palette, Surface.PRESSED); c.drawCircle(r.centerX(), r.centerY(), 14 * dp, fill) }
        stroke.color = if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.TEXT) else Drawn.ink(palette, Surface.PLAIN, Ink.TEXT)
        stroke.strokeWidth = 1.7f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        val k = 5f * dp
        c.drawLine(r.centerX() - k, r.centerY() - k, r.centerX() + k, r.centerY() + k, stroke)
        c.drawLine(r.centerX() + k, r.centerY() - k, r.centerX() - k, r.centerY() + k, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /**
     * r14 (LG1): the bar's accent chips (Pegar, the context offer). At rest: the accent's chip colour with the accent
     * text (≥ 4.5:1 by the gate); pressed: inverted, the accent fill with its ink — the press is unmistakable and
     * stays legible (0.13.0's 30 % accent wash fell to 2.83:1 on Papiro).
     */
    private fun drawChip(c: Canvas, r: RectF, pressed: Boolean, icon: Icon, label: String, sizeDp: Float, weight: Int, iconScale: Float) {
        val rad = r.height() / 2
        fill.shader = null
        fill.color = Drawn.surface(palette, if (pressed) Surface.SOLID else Surface.CHIP)
        c.drawRoundRect(r, rad, rad, fill)
        if (!pressed) {
            stroke.color = if (palette.highContrast) Drawn.ink(palette, Surface.BACKGROUND, Ink.ACCENT) else palette.accentGlow
            stroke.strokeWidth = dp
            stroke.strokeCap = Paint.Cap.BUTT
            c.drawRoundRect(r, rad, rad, stroke)
        }
        val ink = if (pressed) Drawn.ink(palette, Surface.SOLID, Ink.ACCENT_INK) else Drawn.ink(palette, Surface.CHIP, Ink.ACCENT)
        KeyIcons.draw(c, icon, r.left + 17 * dp, r.centerY(), 14 * dp * iconScale, ink, stroke, fill)
        text.color = ink
        text.textSize = sizeDp * dp
        text.typeface = typeface(weight)
        val shown = ellipsize(label, r.width() - 38 * dp)
        text.textAlign = Paint.Align.LEFT
        val fm = text.fontMetrics
        c.drawText(shown, r.left + 30 * dp, r.centerY() - (fm.ascent + fm.descent) / 2, text)
        text.textAlign = Paint.Align.CENTER
    }

    /**
     * r9 update chip: accent-soft pill with an amber hairline, a filled amber disc holding a
     * download arrow, the version line in the accent and the action quieter, then a ✕ cell behind
     * a hairline divider. Fades + slides 8 dp in; fades out.
     */
    private fun drawUpdateChip(c: Canvas, s: StripItem, pressedStrip: Set<StripItem>) {
        val label = updateLabel ?: return
        val x = stripItems.firstOrNull { it.kind == StripKind.UPDATE_X } ?: return
        val a = chipAnim.coerceIn(0f, 1f)
        if (a <= 0f) return
        val dx = (1f - a) * 8 * dp
        // same 6 dp inset as the profile + paste pills: one height across the strip
        val body = chipBody.apply { set(s.rect.left + dx, 6 * dp, x.rect.right + dx, stripH - 6 * dp) }
        val save = c.saveLayerAlpha(body.left - 2 * dp, 0f, body.right + 2 * dp, stripH, (a * 255).toInt())
        val rad = body.height() / 2
        fill.shader = null
        val pressed = s in pressedStrip
        fill.color = Drawn.surface(palette, if (pressed) Surface.PRESSED else Surface.CHIP)
        c.drawRoundRect(body, rad, rad, fill)
        // the paste chip's hairline: amber, but the disc alone carries full strength (Enter stays the hero)
        stroke.color = palette.accentGlow
        stroke.strokeWidth = 1 * dp
        stroke.strokeCap = Paint.Cap.BUTT
        c.drawRoundRect(body, rad, rad, stroke)
        // icon: amber disc + download arrow in the accent's ink (Novedades: the ✦ of the brand)
        val cx = body.left + 16 * dp
        val cy = body.centerY()
        fill.color = Drawn.surface(palette, Surface.SOLID)
        c.drawCircle(cx, cy, 10 * dp, fill)
        if (chipNews) {
            val R = 6.2f * dp
            val r = 1.7f * dp
            starPath.rewind()
            starPath.moveTo(cx, cy - R)
            starPath.lineTo(cx + r, cy - r); starPath.lineTo(cx + R, cy); starPath.lineTo(cx + r, cy + r)
            starPath.lineTo(cx, cy + R); starPath.lineTo(cx - r, cy + r); starPath.lineTo(cx - R, cy)
            starPath.lineTo(cx - r, cy - r); starPath.close()
            fill.color = Drawn.ink(palette, Surface.SOLID, Ink.ACCENT_INK)
            c.drawPath(starPath, fill)
        } else {
            stroke.color = Drawn.ink(palette, Surface.SOLID, Ink.ACCENT_INK)
            stroke.strokeWidth = 1.6f * dp
            stroke.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx, cy - 4.8f * dp, cx, cy + 2.2f * dp, stroke)
            c.drawLine(cx - 3.4f * dp, cy - 1f * dp, cx, cy + 2.5f * dp, stroke)
            c.drawLine(cx + 3.4f * dp, cy - 1f * dp, cx, cy + 2.5f * dp, stroke)
            c.drawLine(cx - 4.2f * dp, cy + 5.2f * dp, cx + 4.2f * dp, cy + 5.2f * dp, stroke)
        }
        // text
        val tx = body.left + CHIP_ICON_W * dp
        val tp = chipTitlePaint(label.twoLine).apply { color = if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(palette, Surface.CHIP, Ink.ACCENT) }
        val ap = chipActionPaint(label.twoLine).apply { color = if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.TEXT_MOD) else Drawn.ink(palette, Surface.CHIP, Ink.TEXT_MOD) }
        if (label.twoLine) {
            val tf = tp.fontMetrics
            val af = ap.fontMetrics
            val th = tf.descent - tf.ascent
            val ah = af.descent - af.ascent
            val gap = 0f // DM Sans' own ascent/descent already leave the pair a comfortable lead
            val top = cy - (th + ah + gap) / 2
            c.drawText(label.title, tx, top - tf.ascent, tp)
            c.drawText(label.action, tx, top + th + gap - af.ascent, ap)
        } else {
            val fm = tp.fontMetrics
            val base = cy - (fm.ascent + fm.descent) / 2
            c.drawText(label.title, tx, base, tp)
            c.drawText(label.action, tx + tp.measureText(label.title), base, ap)
        }
        // ✕ cell
        val xr = chipX.apply { set(x.rect.left + dx, body.top, x.rect.right + dx, body.bottom) }
        val xPressed = x in pressedStrip
        if (xPressed) {
            fill.color = Drawn.surface(palette, Surface.PRESSED)
            c.drawCircle(xr.centerX(), xr.centerY(), 13 * dp, fill)
        }
        fill.color = Drawn.chipDivider(palette)
        c.drawRect(xr.left, body.top + body.height() * 0.24f, xr.left + dp, body.bottom - body.height() * 0.24f, fill)
        stroke.color = if (xPressed) Drawn.ink(palette, Surface.PRESSED, Ink.ACCENT) else if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.TEXT_MOD) else Drawn.ink(palette, Surface.CHIP, Ink.TEXT_MOD)
        stroke.strokeWidth = 1.6f * dp
        val k = 4.2f * dp
        c.drawLine(xr.centerX() - k, xr.centerY() - k, xr.centerX() + k, xr.centerY() + k, stroke)
        c.drawLine(xr.centerX() + k, xr.centerY() - k, xr.centerX() - k, xr.centerY() + k, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        c.restoreToCount(save)
    }

    /**
     * r10 (F-6): «sin memoria» — a small hollow ring with a slash, muted: this field learns nothing
     * and offers nothing learned. Quiet on purpose (it explains itself on tap / TalkBack).
     */
    private fun drawNoMemory(c: Canvas, r: RectF, pressed: Boolean) {
        if (pressed) {
            fill.color = Drawn.surface(palette, Surface.PRESSED)
            tmp.set(r.left + 3 * dp, 8 * dp, r.right - 3 * dp, stripH - 8 * dp)
            c.drawRoundRect(tmp, panelRadius(), panelRadius(), fill)
        }
        val cx = r.centerX()
        val cy = r.centerY()
        stroke.color = if (pressed) Drawn.ink(palette, Surface.PRESSED, Ink.ACCENT) else Drawn.ink(palette, Surface.BACKGROUND, Ink.MUTED)
        stroke.strokeWidth = 1.5f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawCircle(cx, cy, 6.5f * dp, stroke)
        c.drawLine(cx - 4.6f * dp, cy + 4.6f * dp, cx + 4.6f * dp, cy - 4.6f * dp, stroke)
    }

    /**
     * r10 (UX-13): the one-handed rail — move to the other side (top) / back to full width (bottom). r14: one dock the
     * height of the keys holding two round buttons with a grip between them, instead of two loose key-like slabs.
     */
    private fun drawRail(c: Canvas) {
        if (rail.isEmpty()) return
        val t = palette.theme
        val first = rail.first().second
        val last = rail.last().second
        fill.shader = null
        tmp.set(first.left + 6 * dp, first.top + 2 * dp, first.right - 6 * dp, last.bottom - 2 * dp)
        val dockR = min(tmp.width() / 2, 22 * dp)
        fill.color = Drawn.surface(palette, Surface.CARD)
        c.drawRoundRect(tmp, dockR, dockR, fill)
        stroke.color = t.edge
        stroke.strokeWidth = dp
        c.drawRoundRect(tmp, dockR, dockR, stroke)
        // grip
        fill.color = t.edgeHi
        val gy = (first.bottom + last.top) / 2
        c.drawRoundRect(tmp.centerX() - 7 * dp, gy - 1.5f * dp, tmp.centerX() + 7 * dp, gy - 0.5f * dp, dp, dp, fill)
        c.drawRoundRect(tmp.centerX() - 7 * dp, gy + 0.5f * dp, tmp.centerX() + 7 * dp, gy + 1.5f * dp, dp, dp, fill)
        for ((side, r) in rail) {
            var on = false
            for (p in ptrs.values) if (p.rail == side) on = true
            val d = min(r.width() - 16 * dp, 44 * dp).coerceAtLeast(28 * dp)
            fill.color = Drawn.surface(palette, if (on) Surface.CHIP else Surface.MOD_PLAIN)
            c.drawCircle(r.centerX(), r.centerY(), d / 2, fill)
            stroke.color = if (on || palette.highContrast) Drawn.ink(palette, Surface.CARD, Ink.ACCENT) else t.edgeHi
            stroke.strokeWidth = dp
            c.drawCircle(r.centerX(), r.centerY(), d / 2, stroke)
            val icon = when (side) { OneHand.LEFT -> Icon.ARROW_LEFT; OneHand.RIGHT -> Icon.ARROW_RIGHT; OneHand.OFF -> Icon.EXPAND }
            KeyIcons.draw(c, icon, r.centerX(), r.centerY(), d * 0.48f,
                if (on) Drawn.ink(palette, Surface.CHIP, Ink.ACCENT) else Drawn.ink(palette, Surface.MOD_PLAIN, Ink.TEXT_MOD), stroke, fill)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, text)
    }

    /** Icons follow the legend size a little (× 0.9-1.2), never past the 0.13.0 cap of 24 dp × 1.2. */
    private fun iconSize(w: Float): Float = KeyIcons.size(rowH, w, dp) * (settings.legendScale * Legend.fontFactor(fontScale)).coerceIn(0.9f, 1.2f)

    private fun drawKey(c: Canvas, b: Box, press: Float) {
        val k = b.key
        // r14 fix (LG6): the kind and the legend's ink come from the table the gate corrects and checks
        val ink = Drawn.ink(palette, Surface.KEY_TOP, Drawn.legendInk(k.type, k.style, shift))
        painter.body(c, b.rect, b.row, Drawn.kindOf(k.type, k.style, shift), palette, press, face)
        // legend: centred on the face the cap leaves (a keycap's face sits above its skirt)
        val cx = face.centerX()
        val cy = face.centerY()
        text.color = ink
        when (k.type) {
            KeyType.CHAR -> {
                val letter = k.label.length == 1 && k.label[0].isLetter()
                val label = if (shift != ShiftState.OFF || (settings.upperLegends && letter)) Variants.shiftChar(k.label) else k.label
                val small = k.style == KeyStyle.ACCENT || k.style == KeyStyle.NUM
                text.textSize = Legend.letter(rowH, face.width(), face.height(), small, settings.legendScale, fontScale, dp)
                text.typeface = typeface(settings.legendWeight.wght + if (small) 50 else 0)
                drawCentered(c, label, cx, cy)
                if (settings.subLegends && k.hint != null) {
                    text.color = Drawn.ink(palette, Surface.KEY_TOP, Drawn.HINT)
                    text.textSize = Legend.hint(rowH, settings.legendScale, fontScale, dp)
                    text.typeface = typeface(500)
                    c.drawText(k.hint, face.right - 6 * dp, face.top + 3 * dp + text.textSize * 0.82f, text)
                } else if (settings.subLegends && k.variants.isNotEmpty() && k.style == KeyStyle.NORMAL && letter) {
                    fill.color = palette.ghost
                    c.drawCircle(face.right - 6 * dp, face.top + 6 * dp, 1.4f * dp, fill)
                }
            }
            KeyType.SPACE -> {
                // the language in quiet ink, the brand's ✦ in the accent before it
                text.color = Drawn.ink(palette, Surface.KEY_TOP, Drawn.SPACE_LABEL)
                text.textSize = 13 * dp * (settings.legendScale * Legend.fontFactor(fontScale)).coerceIn(0.9f, 1.35f)
                text.typeface = typeface(550)
                val lw = text.measureText(k.label)
                val star = text.textSize * 0.42f
                val x0 = cx - (lw + star * 2 + 6 * dp) / 2
                drawStar(c, x0 + star, cy, star, Drawn.ink(palette, Surface.KEY_TOP, Drawn.SPACE_STAR))
                text.textAlign = Paint.Align.LEFT
                val fm = text.fontMetrics
                c.drawText(k.label, x0 + star * 2 + 6 * dp, cy - (fm.ascent + fm.descent) / 2, text)
                text.textAlign = Paint.Align.CENTER
                if (spaceHint) {
                    // the system globe is gone: a globe says "hold me to switch keyboards"
                    val sz = min(rowH * 0.3f, 14 * dp)
                    KeyIcons.draw(c, Icon.GLOBE, face.right - 8 * dp - sz / 2, cy, sz, Drawn.ink(palette, Surface.KEY_TOP, Drawn.SPACE_GLOBE), stroke, fill)
                }
            }
            KeyType.SHIFT -> {
                // QI2: off = outline, once/auto = filled (accent), locked = filled + bar (on the accent key)
                val icon = when (shift) {
                    ShiftState.OFF -> Icon.SHIFT
                    ShiftState.LOCKED -> Icon.SHIFT_LOCK
                    ShiftState.ONCE, ShiftState.AUTO -> Icon.SHIFT_ON
                }
                val color = if (shift == ShiftState.ONCE || shift == ShiftState.AUTO) Drawn.ink(palette, Surface.KEY_TOP, Drawn.SHIFT_ON) else ink
                KeyIcons.draw(c, icon, cx, cy, iconSize(face.width()), color, stroke, fill)
            }
            KeyType.ENTER -> {
                val g = enterGlyph
                if (g != null) { // r15 (ES10): the top match, inserted by Enter, on a neutral plate (a colour glyph on the accent reads muddy)
                    val sz = iconSize(face.width()) * 1.15f
                    fill.color = Drawn.surface(palette, Surface.CARD)
                    c.drawCircle(cx, cy, min(sz * 0.78f, min(face.width(), face.height()) * 0.46f), fill)
                    text.textSize = sz
                    text.typeface = Typeface.DEFAULT
                    drawCentered(c, g, cx, cy)
                } else KeyIcons.draw(c, enterIcon, cx, cy, iconSize(face.width()), ink, stroke, fill)
            }
            KeyType.BACKSPACE -> KeyIcons.draw(c, Icon.BACKSPACE, cx, cy, iconSize(face.width()), ink, stroke, fill)
            KeyType.EMOJI -> KeyIcons.draw(c, Icon.EMOJI, cx, cy, iconSize(face.width()), ink, stroke, fill)
            else -> {
                text.typeface = typeface(600)
                text.textSize = Legend.label(rowH, face.width(), k.label, settings.legendScale, fontScale, dp) { s, px -> text.textSize = px; text.measureText(s) }
                drawCentered(c, k.label, cx, cy)
            }
        }
    }

    /** The brand's four-point star, drawn (no font glyph): the space bar and the bar's notices share it. */
    private fun drawStar(c: Canvas, cx: Float, cy: Float, R: Float, color: Int) {
        val r = R * 0.28f
        starPath.rewind()
        starPath.moveTo(cx, cy - R)
        starPath.lineTo(cx + r, cy - r); starPath.lineTo(cx + R, cy); starPath.lineTo(cx + r, cy + r)
        starPath.lineTo(cx, cy + R); starPath.lineTo(cx - r, cy + r); starPath.lineTo(cx - R, cy)
        starPath.lineTo(cx - r, cy - r); starPath.close()
        fill.shader = null
        fill.color = color
        c.drawPath(starPath, fill)
    }

    /**
     * The key preview. r14: CONNECTED (default) is one shape — the bubble and a neck down into the pressed key, in
     * the pressed key's colour — and grows out of the key (≤ 70 ms); FLOATING is 0.13.0's card above the key.
     * Never in secret fields (the caller checks).
     */
    private fun drawPreview(c: Canvas, p: Ptr, b: Box, now: Long) {
        val t = palette.theme
        if (p.previewBox !== b) { p.previewBox = b; p.previewAt = now }
        val w = max(b.rect.width() * 1.25f, 44 * dp)
        val h = rowH * 1.1f
        val top = PopupGeometry.previewTop(b.rect.top - 2 * dp, h)
        val left = (b.rect.centerX() - w / 2).coerceIn(2 * dp, width - w - 2 * dp)
        val g = if (motion == Motion.FULL) ease(grow(p.previewAt, now, 70)) else 1f
        tmp.set(left, top, left + w, top + h)
        if (g < 1f) {
            val s = 0.72f + 0.28f * g
            val px = b.rect.centerX()
            val py = b.rect.top
            tmp.set(px + (tmp.left - px) * s, py + (tmp.top - py) * s, px + (tmp.right - px) * s, py + (tmp.bottom - py) * s)
        }
        val rad = min(painter.radius(b.rect) + 4 * dp, tmp.width() / 2)
        val connected = settings.bubble == Bubble.CONNECTED && tmp.bottom <= b.rect.top + 2 * dp
        headPath.rewind()
        headPath.addRoundRect(tmp, rad, rad, Path.Direction.CW)
        bubblePath.set(headPath)
        if (connected) {
            // one outline: the head and the whole pressed key under it (the key rises into the bubble, no seam)
            joinKey(tmp, rad, b.rect)
        }
        fill.shader = null
        fill.color = t.shadowColor()
        c.save()
        c.translate(0f, 2.5f * dp)
        c.drawPath(headPath, fill)
        c.restore()
        fill.color = Drawn.surface(palette, if (connected) Surface.PREVIEW else Surface.CARD)
        c.drawPath(bubblePath, fill)
        stroke.strokeWidth = if (connected && !t.dark) 1.5f * dp else dp
        stroke.color = if (palette.highContrast) palette.outline else if (connected) t.edgeHi else palette.accentGlow
        c.drawPath(bubblePath, stroke)
        val letter = b.key.label.length == 1 && b.key.label[0].isLetter()
        val label = if (shift != ShiftState.OFF || (settings.upperLegends && letter)) Variants.shiftChar(b.key.label) else b.key.label
        text.color = if (connected) Drawn.ink(palette, Surface.PREVIEW, Ink.TEXT) else Drawn.ink(palette, Surface.CARD, Ink.TEXT)
        text.textSize = min(min(h * 0.5f, 30 * dp) * (settings.legendScale * Legend.fontFactor(fontScale)).coerceIn(0.9f, 1.3f), tmp.height() * 0.66f)
        text.typeface = typeface(settings.legendWeight.wght + 50)
        drawCentered(c, label, tmp.centerX(), tmp.centerY())
    }

    /**
     * Adds to [bubblePath] the pressed key under a popup [head] (corner radius [rad]) and two concave fillets where the
     * head meets it, so the outline flares from the key into the head instead of stepping.
     */
    private fun joinKey(head: RectF, rad: Float, key: RectF) {
        val kr = min(painter.radius(key), key.width() / 2)
        neckPath.rewind()
        neckPath.addRoundRect(key.left, head.bottom - rad, key.right, key.bottom, kr, kr, Path.Direction.CW)
        if (head.left < key.left - dp && head.right > key.right + dp) {
            val f = min(6 * dp, rad)
            val top = head.bottom - rad
            neckPath.moveTo(head.left + rad * 0.5f, top)
            neckPath.lineTo(head.right - rad * 0.5f, top)
            neckPath.quadTo(key.right, head.bottom - rad * 0.2f, key.right, head.bottom + f)
            neckPath.lineTo(key.left, head.bottom + f)
            neckPath.quadTo(key.left, head.bottom - rad * 0.2f, head.left + rad * 0.5f, top)
            neckPath.close()
        }
        bubblePath.op(neckPath, Path.Op.UNION)
    }

    private fun com.resyst.vk.core.Theme.shadowColor(): Int =
        if (dark) 0xB0000000.toInt() else 0x403A352A

    /**
     * The long-press variants, in 0.13.0's cells (PopupGeometry: the slide mapping is unchanged, MO3). r14: the popup
     * grows from the key (≤ 110 ms, motion FULL), joins the key with a neck, and the selection glides between cells.
     */
    private fun drawVariants(c: Canvas, p: Ptr, pop: VariantPopup, now: Long) {
        val t = palette.theme
        val b = p.box ?: return
        val h = rowH * 0.95f
        val body = popupBody.apply { set(pop.left, p.popupTop, pop.left + pop.width, p.popupTop + h) }
        val g = if (motion == Motion.FULL) ease(grow(p.popupAt, now, 110)) else 1f
        c.save()
        if (g < 1f) {
            val s = 0.86f + 0.14f * g
            c.scale(s, s, b.rect.centerX(), b.rect.top)
        }
        val rad = min(painter.radius(b.rect) + 4 * dp, h / 2)
        headPath.rewind()
        headPath.addRoundRect(body, rad, rad, Path.Direction.CW)
        bubblePath.set(headPath)
        val joined = body.bottom <= b.rect.top + 2 * dp
        if (joined) {
            joinKey(body, rad, b.rect)
        }
        fill.shader = null
        fill.color = t.shadowColor()
        c.save()
        c.translate(0f, 3 * dp)
        c.drawPath(headPath, fill)
        c.restore()
        fill.color = Drawn.surface(palette, Surface.CARD)
        c.drawPath(bubblePath, fill)
        stroke.strokeWidth = dp
        stroke.color = if (palette.highContrast) palette.outline else t.edgeHi
        c.drawPath(bubblePath, stroke)
        // the selection glides to its cell (exponential approach, a few frames)
        val target = pop.cellLeft(p.sel)
        p.hlX = if (p.hlX < 0f || motion == Motion.OFF) target else p.hlX + (target - p.hlX) * 0.5f
        if (abs(target - p.hlX) > 0.5f) animating = true else p.hlX = target
        face.set(p.hlX + 2 * dp, body.top + 3 * dp, p.hlX + pop.cell - 2 * dp, body.bottom - 3 * dp)
        fill.color = Drawn.surface(palette, Surface.SOLID)
        val cr = min(painter.radius(b.rect), face.height() / 2)
        c.drawRoundRect(face, cr, cr, fill)
        for (i in p.popupItems.indices) {
            val l = pop.cellLeft(i)
            text.color = if (i == p.sel) Drawn.ink(palette, Surface.SOLID, Ink.ACCENT_INK) else Drawn.ink(palette, Surface.CARD, Ink.TEXT)
            text.textSize = min(h * 0.45f, 24 * dp) * (settings.legendScale * Legend.fontFactor(fontScale)).coerceIn(0.9f, 1.25f)
            text.typeface = typeface(settings.legendWeight.wght + 50)
            drawCentered(c, p.popupItems[i], l + pop.cell / 2, body.centerY())
        }
        c.restore()
    }

    // ── accessibility (TalkBack: explore by touch + lift-to-type) ────────
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = a11y.dispatchHoverEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT && accessibility?.isTouchExplorationEnabled == true) {
            val x = event.x
            val y0 = event.y
            if (esQuery != null && y0 >= 0 && y0 < bandH) {
                val i = bandIndexAt(x)
                if (i >= 0) bandAct(i)
                return true
            }
            val y = y0 - bandH
            if (x >= 0 && x < width && y >= 0 && y < height) {
                if (clipboardOpen) { clipPanel.hitAt(x, y)?.let { panelAct(it) }; return true }
                if (emojiOpen) { emojiPanel.hitAt(x, y)?.let { emojiAct(it) }; return true }
                if (quickOpen) { quickPanel.hitAt(x, y)?.let { quickAct(it) }; return true }
                if (editOpen) { editPanel.hitAt(x, y)?.let { editAct(it) }; return true }
                stripAt(x, y)?.let { stripTap(it); return true }
                railAt(x, y)?.let { listener?.onOneHand(it); return true }
                boxAt(x, y)?.let { activate(it); return true }
            }
        }
        return handled || super.dispatchHoverEvent(event)
    }

    private fun activate(b: Box) {
        val l = listener ?: return
        l.onKeyDown(b.key)
        when (b.key.type) {
            KeyType.SHIFT -> Unit
            KeyType.EMOJI -> l.onEmojiKey()
            else -> l.onKeyCommit(b.key)
        }
        l.onKeyUp(b.key)
    }

    private fun describe(k: Key): String = when (k.type) {
        KeyType.CHAR -> when (k.text) {
            "," -> tx.get(R.string.key_comma); "." -> tx.get(R.string.key_period); "@" -> tx.get(R.string.key_at)
            "/" -> tx.get(R.string.key_slash); "-" -> tx.get(R.string.key_hyphen)
            else -> if (shift != ShiftState.OFF) Variants.shiftChar(k.text) else k.text
        }
        KeyType.SHIFT -> when (shift) {
            ShiftState.OFF -> tx.get(R.string.key_shift)
            ShiftState.LOCKED -> tx.get(R.string.key_shift_locked)
            else -> tx.get(R.string.key_shift_on)
        }
        KeyType.BACKSPACE -> tx.get(R.string.key_backspace)
        KeyType.ENTER -> enterDesc
        KeyType.SPACE -> tx.get(if (spaceHint) R.string.key_space_hint else R.string.key_space)
        KeyType.LAYER -> when (k.target) {
            Layer.SYMBOLS -> tx.get(R.string.key_symbols)
            Layer.SYMBOLS2 -> tx.get(R.string.key_symbols_more)
            else -> tx.get(R.string.key_letters)
        }
        KeyType.SPACER -> ""
        KeyType.EMOJI -> tx.get(R.string.key_emoji)
    }

    private inner class KeyA11y : ExploreByTouchHelper(this) {
        private val stripBase = 10_000
        private val panelBase = 20_000
        private val emojiBase = 30_000
        private val quickBase = 40_000
        private val railBase = 50_000
        private val editBase = 60_000
        /** r15 (ES12): the results band (its visible cells). */
        private val bandBase = 70_000

        override fun getVirtualViewAt(x: Float, y0: Float): Int {
            if (esQuery != null && y0 < bandH) {
                if (esResults.isEmpty()) return if (esStatus != null) bandBase else INVALID_ID
                val i = esHits.indexOfFirst { x >= it.second.left && x < it.second.right }
                return if (i >= 0) bandBase + i else INVALID_ID
            }
            val y = y0 - bandH
            if (clipboardOpen) {
                val h = clipPanel.hitAt(x, y) ?: return INVALID_ID
                return panelBase + clipPanel.hits.indexOf(h)
            }
            if (emojiOpen) {
                val h = emojiPanel.hitAt(x, y) ?: return INVALID_ID
                return emojiBase + emojiPanel.hits.indexOf(h)
            }
            if (quickOpen) {
                val h = quickPanel.hitAt(x, y) ?: return INVALID_ID
                return quickBase + quickPanel.hits.indexOf(h)
            }
            if (editOpen) {
                val h = editPanel.hitAt(x, y) ?: return INVALID_ID
                return editBase + editPanel.hits.indexOf(h)
            }
            stripAt(x, y)?.let { return stripBase + stripItems.indexOf(it) }
            railAt(x, y)?.let { side -> return railBase + rail.indexOfFirst { it.first == side } }
            val b = boxAt(x, y) ?: return INVALID_ID
            return boxes.indexOf(b)
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            // r15 (ES12): the results, or the band's one line (hint, preparing, no result) as one node
            if (esQuery != null) { if (esResults.isEmpty()) { if (esStatus != null) ids += bandBase } else for (i in esHits.indices) ids += bandBase + i }
            if (clipboardOpen) { for (i in clipPanel.hits.indices) ids += panelBase + i; return }
            if (emojiOpen) { for (i in emojiPanel.hits.indices) ids += emojiBase + i; return }
            if (quickOpen) { for (i in quickPanel.hits.indices) ids += quickBase + i; return }
            if (editOpen) { for (i in editPanel.hits.indices) ids += editBase + i; return }
            for (i in stripItems.indices) ids += stripBase + i
            for (i in rail.indices) ids += railBase + i
            for (i in boxes.indices) ids += i
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val r = RectF()
            val desc: String
            if (id >= bandBase) {
                if (esResults.isEmpty()) {
                    desc = esStatus ?: ""
                    r.set(0f, 0f, width.toFloat(), bandH)
                } else {
                    val h = esHits.getOrNull(id - bandBase)
                    desc = h?.let { resultDesc(it.first) } ?: ""
                    h?.let { r.set(it.second) }
                }
            } else if (id >= editBase) {
                val h = editPanel.hits.getOrNull(id - editBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                node.isEnabled = h?.enabled ?: false
            } else if (id >= railBase) {
                val e = rail.getOrNull(id - railBase)
                desc = when (e?.first) {
                    OneHand.LEFT -> tx.get(R.string.rail_left)
                    OneHand.RIGHT -> tx.get(R.string.rail_right)
                    OneHand.OFF -> tx.get(R.string.rail_full)
                    null -> ""
                }
                e?.let { r.set(it.second) }
            } else if (id >= quickBase) {
                val h = quickPanel.hits.getOrNull(id - quickBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                node.isEnabled = h?.enabled ?: false
            } else if (id >= emojiBase) {
                val h = emojiPanel.hits.getOrNull(id - emojiBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                // r11 (ET4): TalkBack reaches the skin tones with a long-click action
                if (h?.act == EmojiPanel.Act.EMOJI && h.cell?.tones?.isNotEmpty() == true) node.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfo.ACTION_LONG_CLICK, tx.get(R.string.key_skin_tones)))
            } else if (id >= panelBase) {
                val h = clipPanel.hits.getOrNull(id - panelBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                if (h?.act == ClipboardPanel.Act.ROW) node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            } else if (id >= stripBase) {
                val s = stripItems.getOrNull(id - stripBase)
                desc = when (s?.kind) {
                    StripKind.SETTINGS -> if (gearDot) tx.get(R.string.strip_settings_update, updateVersion ?: "") else tx.get(R.string.strip_settings)
                    StripKind.NO_MEMORY -> tx.get(R.string.nomem_desc, s.text)
                    StripKind.UPDATE -> if (chipNews) WhatsNew.describe(tx, s.text) else tx.get(R.string.strip_update_desc, s.text)
                    StripKind.UPDATE_X -> if (chipNews) WhatsNew.dismissDescription(tx, s.text) else tx.get(R.string.strip_update_dismiss, s.text)
                    StripKind.SUGGESTION -> tx.get(R.string.strip_suggestion, s.text)
                    StripKind.OFFER -> offerDesc.ifEmpty { s.text }
                    StripKind.PASTE -> if (pasteImage) tx.get(R.string.strip_paste_image_desc) else tx.get(R.string.strip_paste_desc, s.text)
                    StripKind.DAYNIGHT -> tx.get(if (dayNight == true) R.string.strip_to_light else R.string.strip_to_dark)
                    StripKind.GIF_BACK -> tx.get(R.string.strip_gif_back)
                    StripKind.GIF_GO -> tx.get(R.string.strip_gif_search)
                    StripKind.GIF_FIELD -> tx.get(R.string.strip_gif_field, s.text.ifEmpty { tx.get(R.string.strip_gif_empty) })
                    StripKind.ES_BACK -> tx.get(R.string.emoji_search_back)
                    StripKind.ES_CLEAR -> tx.get(R.string.emoji_search_clear)
                    StripKind.ES_FIELD -> tx.get(R.string.emoji_search_field, s.text.ifEmpty { tx.get(R.string.strip_gif_empty) })
                    null -> ""
                }
                s?.let { r.set(it.rect) }
            } else {
                val b = boxes.getOrNull(id)
                desc = b?.let { describe(it.key) } ?: ""
                b?.let { r.set(it.rect) }
                // G6: long-press space = keyboard picker, also for TalkBack users
                if (b?.key?.type == KeyType.SPACE) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK, tx.get(R.string.key_switch_keyboard)))
            }
            if (id < bandBase) r.offset(0f, bandH) // below the band: the bar's coordinates (bandH = 0 when not searching)
            node.contentDescription = desc
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(android.graphics.Rect(r.left.toInt(), r.top.toInt(), max(r.left.toInt() + 1, r.right.toInt()), max(r.top.toInt() + 1, r.bottom.toInt())))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            if (id >= bandBase) {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
                val h = esHits.getOrNull(id - bandBase) ?: return false
                bandAct(h.first); return true
            }
            if (id >= editBase) {
                val h = editPanel.hits.getOrNull(id - editBase) ?: return false
                if (action != AccessibilityNodeInfo.ACTION_CLICK || !h.enabled) return false
                editAct(h); return true
            }
            if (id >= railBase) {
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
                rail.getOrNull(id - railBase)?.let { listener?.onOneHand(it.first); return true }
                return false
            }
            if (id >= quickBase) {
                val h = quickPanel.hits.getOrNull(id - quickBase) ?: return false
                if (action != AccessibilityNodeInfo.ACTION_CLICK || !h.enabled) return false
                quickAct(h); return true
            }
            if (id >= emojiBase) {
                val h = emojiPanel.hits.getOrNull(id - emojiBase) ?: return false
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && h.act == EmojiPanel.Act.EMOJI) {
                    if (!emojiPanel.showTones(h)) return false
                    invalidate(); invalidateRoot(); return true
                }
                if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
                if (h.act == EmojiPanel.Act.DELETE) listener?.onEmojiPanel(EmojiPanel.Act.DELETE, "") else emojiAct(h)
                return true
            }
            if (id >= panelBase) {
                val h = clipPanel.hits.getOrNull(id - panelBase) ?: return false
                return when {
                    action == AccessibilityNodeInfo.ACTION_CLICK -> { panelAct(h); true }
                    action == AccessibilityNodeInfo.ACTION_LONG_CLICK && h.act == ClipboardPanel.Act.ROW -> {
                        clipPanel.showDetail(h.id); invalidate(); invalidateRoot(); true
                    }
                    else -> false
                }
            }
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && id < stripBase &&
                boxes.getOrNull(id)?.key?.type == KeyType.SPACE) {
                listener?.onSpaceLongPress(); return true
            }
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            if (id >= stripBase) {
                stripItems.getOrNull(id - stripBase)?.let { stripTap(it); return true }
                return false
            }
            val b = boxes.getOrNull(id) ?: return false
            activate(b)
            return true
        }
    }

    companion object {
        const val TAG = "ResystVK"
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        /** Edit-panel moves repeat slower than ⌫: a caret walk you can follow. */
        const val EDIT_REPEAT_MS = 70L
        /** r9 chip geometry (dp): icon lead-in, and the ✕ cell = a 48 dp wide hit target. */
        const val CHIP_ICON_W = 33f
        const val CHIP_X_W = 48f
        /** Text → ✕ divider breathing room (dp). */
        const val CHIP_TEXT_END = 10f
        /** r15 (ES11): the emoji search's results band: its height, a result's cell, the row's side padding (dp). */
        const val BAND_DP = 54f
        const val BAND_CELL_DP = 50f
        const val BAND_PAD_DP = 2.5f
    }
}
