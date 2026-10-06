package com.resyst.vk.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.ImeSwitcher
import com.resyst.vk.core.Key
import com.resyst.vk.core.KeyCap
import com.resyst.vk.core.KeyStyle
import com.resyst.vk.core.KeyType
import com.resyst.vk.core.KbSettings
import com.resyst.vk.core.Layer
import com.resyst.vk.core.NavInsets
import com.resyst.vk.core.Palette
import com.resyst.vk.core.PopupGeometry
import com.resyst.vk.core.ShiftState
import com.resyst.vk.core.VariantPopup
import com.resyst.vk.core.Variants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-drawn Resyst keyboard: suggestion/profile strip + key grid + key previews +
 * long-press variants popup. All popups are drawn inside this view (the strip doubles as
 * headroom), so there are no PopupWindows to leak or mis-position inside the IME window.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyDown(key: Key)
        fun onKeyUp(key: Key)
        fun onKeyCommit(key: Key)
        fun onVariant(text: String)
        fun onLongPressOpened()
        fun onSuggestion(word: String)
        fun onCursorDrag(steps: Int)
        fun onSpaceLongPress()
        fun onProfileTap()
        fun onOpenSettings()
        /** The "Pegar" chip: paste the current clipboard offer. */
        fun onPasteOffer()
        /** The clipboard button (or a long-press on the paste chip): open the history. */
        fun onClipboardButton()
        /** A panel action the service must perform (paste, pin, delete, clear, close). */
        fun onClipboardPanel(act: ClipboardPanel.Act, id: Long)
    }

    var listener: Listener? = null

    // ── state pushed by the service ──────────────────────────────────────
    private var rows: List<List<Key>> = emptyList()
    private var baseRowCount = 5
    private var layer = Layer.LETTERS
    private var settings = KbSettings()
    private var palette = Palette.of("lab", null)
    private var shift = ShiftState.OFF
    private var enterLabel = "⏎"
    private var enterDesc = "Intro"
    private var suggestions: List<String> = emptyList()
    private var profileIcon = "☾"
    private var profileName = "Noche"
    private var pasteLabel: String? = null
    private var pasteImage = false
    private var clipButton = false

    /** The system globe is hidden in this window (IME nav bar caption hidden, r7). */
    private var systemGlobeHidden = false
    /** There is another keyboard / subtype to switch to (set by the service). */
    private var switchAvailable = false
    private val spaceHint get() = ImeSwitcher.spaceHint(systemGlobeHidden, switchAvailable)

    fun setSwitchAvailable(v: Boolean) {
        if (v != switchAvailable) { switchAvailable = v; invalidate(); a11y.invalidateRoot() }
    }

    /** The clipboard history panel, drawn over the strip + keys while open. */
    val clipPanel = ClipboardPanel(resources.displayMetrics.density)
    var clipboardOpen = false
        private set

    // ── geometry ─────────────────────────────────────────────────────────
    private val dp = resources.displayMetrics.density
    private class Box(val key: Key, val rect: RectF, val cellLeft: Float, val cellRight: Float, val row: Int)
    private val boxes = ArrayList<Box>()
    private val rowBoxes = ArrayList<List<Box>>()
    private var rowH = 0f
    private val stripH get() = 42 * dp

    private enum class StripKind { PROFILE, PASTE, SUGGESTION, CLIP, SETTINGS }
    private class StripItem(val kind: StripKind, val text: String, val rect: RectF)
    private val stripItems = ArrayList<StripItem>()

    // ── touch ────────────────────────────────────────────────────────────
    private class Ptr(val id: Int, var box: Box?, val downX: Float) {
        var popup: VariantPopup? = null
        var popupItems: List<String> = emptyList()
        var popupTop = 0f
        var sel = 0
        var cursorMode = false
        var cursorX = 0f
        var longFired = false
        var strip: StripItem? = null
    }
    private val ptrs = LinkedHashMap<Int, Ptr>()
    private val handler = Handler(Looper.getMainLooper())

    // ── paints ───────────────────────────────────────────────────────────
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()

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
        settings = s
        palette = p
        if (heightChanged) requestLayout()
        layoutKeys()
        invalidate()
    }

    fun setShift(state: ShiftState) {
        if (state != shift) { shift = state; invalidate(); a11y.invalidateRoot() }
    }

    fun setEnter(label: String, desc: String) {
        if (label != enterLabel) { enterLabel = label; enterDesc = desc; invalidate() }
    }

    fun setSuggestions(list: List<String>) {
        if (list != suggestions) { suggestions = list; layoutStrip(); invalidate(); a11y.invalidateRoot() }
    }

    fun setProfile(icon: String, name: String) {
        profileIcon = icon; profileName = name; layoutStrip(); invalidate()
    }

    fun reset() { cancelPointers(); invalidate() }

    /** The leading "Pegar" chip: [label] = what it pastes (already masked), null = no chip. */
    fun setPasteOffer(label: String?, image: Boolean) {
        if (label == pasteLabel && image == pasteImage) return
        pasteLabel = label; pasteImage = image
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    /** The clipboard (history) button in the strip; hidden in secret fields / history off. */
    fun setClipButton(show: Boolean) {
        if (show == clipButton) return
        clipButton = show
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    fun showClipboard(items: List<com.resyst.vk.core.ClipboardHistory.Entry>, now: Long) {
        cancelPointers()
        if (!clipboardOpen) clipPanel.reset()
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
        val h = if (height > 0) (height - paddingBottom).toFloat() else stripH + keysHeight() + 4 * dp
        clipPanel.setBounds(RectF(0f, 0f, width.toFloat(), h))
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
        setMeasuredDimension(w, NavInsets.totalHeight(content, paddingBottom))
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
        val pad = NavInsets.bottomPadding(current, stable, navMode, hidden)
        val d = if (Build.VERSION.SDK_INT >= 30) dispatched.getInsets(WindowInsets.Type.navigationBars()).bottom
        else @Suppress("DEPRECATION") dispatched.systemWindowInsetBottom
        Log.i(TAG, "nav inset: root current=$current stable=$stable dispatched=$d mode=$navMode caption=$capH visible=$capVisible globeHidden=$hidden → padding=$pad")
        return pad
    }

    private fun applyNavPadding(px: Int) {
        if (px != paddingBottom) {
            setPadding(0, 0, 0, px)
            requestLayout()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
        if (clipboardOpen) layoutPanel()
    }

    private fun layoutKeys() {
        boxes.clear(); rowBoxes.clear()
        if (width == 0 || rows.isEmpty()) return
        val padX = 3 * dp
        val gap = settings.density.gapDp * dp
        val unit = (width - 2 * padX) / com.resyst.vk.core.KeyboardLayouts.ROW_UNITS
        rowH = keysHeight() / rows.size
        rows.forEachIndexed { r, row ->
            val y0 = stripH + r * rowH
            var x = padX
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
        text.textSize = 15 * dp
        text.typeface = typeface(600)
        val compact = suggestions.isNotEmpty() || pasteLabel != null
        val chipLabel = if (!compact) "✦  $profileIcon $profileName" else "✦ $profileIcon"
        val chipW = text.measureText(chipLabel) + 24 * dp
        stripItems += StripItem(StripKind.PROFILE, chipLabel, RectF(6 * dp, 6 * dp, 6 * dp + chipW, h - 6 * dp))
        val gearW = 44 * dp
        stripItems += StripItem(StripKind.SETTINGS, "⚙", RectF(width - gearW, 0f, width.toFloat(), h))
        var r = width - gearW
        if (clipButton) {
            val cw = 42 * dp
            stripItems += StripItem(StripKind.CLIP, "", RectF(r - cw, 0f, r, h))
            r -= cw
        }
        var l = 6 * dp + chipW + 4 * dp
        val paste = pasteLabel
        if (paste != null) {
            text.textSize = 14 * dp
            text.typeface = typeface(600)
            val label = if (pasteImage) "Pegar imagen" else paste
            val room = r - l
            val want = text.measureText(label) + 44 * dp
            val w = if (suggestions.isEmpty()) min(want, room) else min(want, room * 0.55f)
            stripItems += StripItem(StripKind.PASTE, label, RectF(l, 6 * dp, l + w, h - 6 * dp))
            l += w + 4 * dp
        }
        if (suggestions.isNotEmpty() && r - l > 40 * dp) {
            val shown = if (paste != null) suggestions.take(2) else suggestions
            val cw = (r - l) / shown.size
            shown.forEachIndexed { i, s ->
                stripItems += StripItem(StripKind.SUGGESTION, s, RectF(l + i * cw, 0f, l + (i + 1) * cw, h))
            }
        }
    }

    private fun typeface(weight: Int): Typeface = Fonts.get(context, settings.font, weight)

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

    private fun stripAt(x: Float, y: Float): StripItem? =
        if (y >= stripH) null else stripItems.firstOrNull { x >= it.rect.left - 4 * dp && x <= it.rect.right + 4 * dp }

    // ── touch ────────────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelPointers()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (clipboardOpen) { panelDown(id, x, y); return }
        // Fast-typing roll-over: a new finger commits any char key still held without a popup.
        for (p in ptrs.values.toList()) {
            val b = p.box ?: continue
            if (p.popup == null && !p.cursorMode && b.key.type == KeyType.CHAR) {
                handler.removeCallbacksAndMessages(p)
                ptrs.remove(p.id)
                listener?.onKeyCommit(b.key)
            }
        }
        val strip = stripAt(x, y)
        if (strip != null) {
            val p = Ptr(id, null, x).also { it.strip = strip }
            ptrs[id] = p
            if (strip.kind == StripKind.PROFILE) {
                handler.postAtTime({ p.longFired = true; listener?.onOpenSettings() }, p, SystemClock.uptimeMillis() + 500)
            } else if (strip.kind == StripKind.PASTE) {
                handler.postAtTime({ p.longFired = true; listener?.onLongPressOpened(); listener?.onClipboardButton() }, p, SystemClock.uptimeMillis() + 500)
            }
            invalidate()
            return
        }
        val box = boxAt(x, y) ?: return
        val p = Ptr(id, box, x)
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
                p.popupItems = items
                p.sel = 0
                p.popupTop = PopupGeometry.previewTop(b.rect.top - 4 * dp, rowH * 0.95f)
                listener?.onLongPressOpened()
                invalidate()
            }
            else -> Unit
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == panelPtr) { panelMove(y); return }
        val p = ptrs[id] ?: return
        val b = p.box ?: return
        val popup = p.popup
        if (popup != null) {
            val s = popup.indexAt(x)
            if (s != p.sel) { p.sel = s; invalidate() }
            return
        }
        if (b.key.type == KeyType.SPACE) {
            if (!p.cursorMode && !p.longFired && abs(x - p.downX) > 14 * dp) {
                p.cursorMode = true
                p.cursorX = x
                handler.removeCallbacksAndMessages(p)
            }
            if (p.cursorMode) {
                val stepPx = 9 * dp
                val steps = ((x - p.cursorX) / stepPx).toInt()
                if (steps != 0) { p.cursorX += steps * stepPx; listener?.onCursorDrag(steps) }
            }
            return
        }
        if (b.key.type == KeyType.CHAR) {
            val nb = boxAt(x, y)
            if (nb != null && nb !== b && nb.key.type == KeyType.CHAR) {
                handler.removeCallbacksAndMessages(p)
                p.box = nb
                if (nb.key.variants.isNotEmpty()) scheduleLongPress(p)
                invalidate()
            }
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        if (id == panelPtr) { panelUp(x, y); return }
        val p = ptrs.remove(id) ?: return
        handler.removeCallbacksAndMessages(p)
        val strip = p.strip
        if (strip != null) {
            if (!p.longFired && stripAt(x, y) === strip) stripTap(strip)
            invalidate()
            return
        }
        val b = p.box ?: return
        val popup = p.popup
        when {
            popup != null -> listener?.onVariant(p.popupItems[p.sel])
            p.cursorMode || p.longFired -> Unit
            b.key.type == KeyType.BACKSPACE || b.key.type == KeyType.SHIFT -> Unit
            else -> listener?.onKeyCommit(b.key)
        }
        listener?.onKeyUp(b.key)
        invalidate()
    }

    private fun stripTap(s: StripItem) {
        when (s.kind) {
            StripKind.PROFILE -> listener?.onProfileTap()
            StripKind.SETTINGS -> listener?.onOpenSettings()
            StripKind.SUGGESTION -> listener?.onSuggestion(s.text)
            StripKind.PASTE -> listener?.onPasteOffer()
            StripKind.CLIP -> listener?.onClipboardButton()
        }
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
        handler.removeCallbacksAndMessages(panelToken)
        panelPtr = -1
        panelHit = null
        for (p in ptrs.values) {
            handler.removeCallbacksAndMessages(p)
            val k = p.box?.key
            if (k != null) listener?.onKeyUp(k)
        }
        ptrs.clear()
    }

    override fun onDetachedFromWindow() {
        cancelPointers()
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // ── drawing ──────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        val t = palette.theme
        canvas.drawColor(t.bg)
        if (clipboardOpen) {
            clipPanel.draw(canvas, palette, radius(), ::typeface, if (panelScrolling || panelLong) null else panelHit)
            return
        }
        drawStrip(canvas)
        val pressed = HashSet<Box>()
        for (p in ptrs.values) p.box?.let { if (!p.cursorMode) pressed += it }
        for (b in boxes) drawKey(canvas, b, b in pressed)
        for (p in ptrs.values) {
            val b = p.box ?: continue
            val pop = p.popup
            if (pop != null) drawVariants(canvas, p, pop)
            else if (settings.popups && b.key.type == KeyType.CHAR && !p.cursorMode) drawPreview(canvas, b)
        }
    }

    private fun radius() = settings.shape.radiusDp * dp

    private fun drawStrip(c: Canvas) {
        val t = palette.theme
        fill.shader = null
        fill.color = t.edge
        c.drawRect(0f, stripH - 1, width.toFloat(), stripH, fill)
        val pressedStrip = ptrs.values.mapNotNull { it.strip }.toSet()
        var firstSuggestion = true
        for (s in stripItems) {
            val r = s.rect
            when (s.kind) {
                StripKind.PROFILE -> {
                    fill.color = if (s in pressedStrip) ColorMath.withAlpha(palette.accent, 0.3f) else palette.accentSoft
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                    text.color = palette.accent
                    text.textSize = 14 * dp
                    text.typeface = typeface(600)
                    drawCentered(c, s.text, r.centerX(), r.centerY())
                }
                StripKind.PASTE -> {
                    val pressed = s in pressedStrip
                    fill.color = if (pressed) ColorMath.withAlpha(palette.accent, 0.3f) else palette.accentSoft
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                    stroke.color = palette.accentGlow
                    stroke.strokeWidth = 1 * dp
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
                    clipPanel.drawClipIcon(c, r.left + 17 * dp, r.centerY(), 13 * dp, palette.accent)
                    text.color = palette.accent
                    text.textSize = 14 * dp
                    text.typeface = typeface(600)
                    val avail = r.width() - 38 * dp
                    val label = ellipsize(s.text, avail)
                    text.textAlign = Paint.Align.LEFT
                    val fm = text.fontMetrics
                    c.drawText(label, r.left + 30 * dp, r.centerY() - (fm.ascent + fm.descent) / 2, text)
                    text.textAlign = Paint.Align.CENTER
                }
                StripKind.CLIP -> {
                    val pressed = s in pressedStrip
                    if (pressed) {
                        fill.color = t.keyHi
                        tmp.set(r.left + 3 * dp, 6 * dp, r.right - 3 * dp, stripH - 6 * dp)
                        c.drawRoundRect(tmp, radius(), radius(), fill)
                    }
                    clipPanel.drawClipIcon(c, r.centerX(), r.centerY(), 17 * dp, if (pressed) palette.accent else t.muted)
                }
                StripKind.SETTINGS -> {
                    text.color = if (s in pressedStrip) palette.accent else t.muted
                    text.textSize = 18 * dp
                    text.typeface = Typeface.DEFAULT
                    drawCentered(c, s.text, r.centerX(), r.centerY())
                }
                StripKind.SUGGESTION -> {
                    if (s in pressedStrip) {
                        fill.color = t.keyHi
                        tmp.set(r.left + 2 * dp, 5 * dp, r.right - 2 * dp, stripH - 5 * dp)
                        c.drawRoundRect(tmp, radius(), radius(), fill)
                    }
                    text.color = if (firstSuggestion) palette.accent else t.text
                    text.textSize = 16 * dp
                    text.typeface = typeface(if (firstSuggestion) 650 else 450)
                    val label = ellipsize(s.text, r.width() - 12 * dp)
                    drawCentered(c, label, r.centerX(), r.centerY())
                    if (!firstSuggestion) {
                        fill.color = t.edge
                        c.drawRect(r.left, stripH * 0.3f, r.left + dp, stripH * 0.7f, fill)
                    }
                    firstSuggestion = false
                }
            }
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

    private fun keyColors(k: Key): Pair<Int, Int> {
        val t = palette.theme
        return when {
            k.type == KeyType.ENTER -> palette.accent to palette.accentInk
            k.type == KeyType.SHIFT && shift == ShiftState.LOCKED -> palette.accent to palette.accentInk
            k.style == KeyStyle.MOD -> t.keyMod to t.textMod
            k.style == KeyStyle.NUM -> t.keyNum to t.text
            k.style == KeyStyle.ACCENT -> t.keyNum to palette.accent
            else -> t.key to t.text
        }
    }

    private fun drawKey(c: Canvas, b: Box, pressed: Boolean) {
        val t = palette.theme
        val k = b.key
        val r = b.rect
        val rad = radius()
        val (base, ink) = keyColors(k)
        val solidAccent = base == palette.accent
        fill.shader = null
        when (settings.cap) {
            KeyCap.RAISED -> {
                fill.color = if (solidAccent) ColorMath.mix(palette.accent, t.bg, 0.35f) else t.edge
                c.drawRoundRect(r.left, r.top, r.right, r.bottom, rad, rad, fill)
                tmp.set(r.left, r.top, r.right, r.bottom - 2 * dp)
                if (pressed) {
                    tmp.offset(0f, 1.5f * dp)
                    fill.color = if (solidAccent) ColorMath.mix(palette.accent, 0xFFFFFFFF.toInt(), 0.15f) else t.keyHi
                } else if (solidAccent) {
                    fill.color = palette.accent
                } else {
                    val top = if (k.style == KeyStyle.MOD) ColorMath.mix(base, t.keyTop, 0.5f) else t.keyTop
                    fill.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, top, base, Shader.TileMode.CLAMP)
                }
                c.drawRoundRect(tmp, rad, rad, fill)
                fill.shader = null
            }
            KeyCap.FLAT -> {
                fill.color = when {
                    pressed && solidAccent -> ColorMath.mix(palette.accent, 0xFFFFFFFF.toInt(), 0.15f)
                    pressed -> t.keyHi
                    else -> base
                }
                tmp.set(r)
                c.drawRoundRect(tmp, rad, rad, fill)
            }
            KeyCap.OUTLINE -> {
                tmp.set(r)
                if (solidAccent || pressed) {
                    fill.color = if (solidAccent) palette.accent else t.keyHi
                    c.drawRoundRect(tmp, rad, rad, fill)
                }
                stroke.color = if (pressed) palette.accent else t.edgeHi
                stroke.strokeWidth = 1 * dp
                tmp.inset(0.5f * dp, 0.5f * dp)
                c.drawRoundRect(tmp, rad, rad, stroke)
            }
        }
        if (pressed && !solidAccent) {
            stroke.color = palette.accentGlow
            stroke.strokeWidth = 1.2f * dp
            c.drawRoundRect(tmp, rad, rad, stroke)
        }

        // legend
        val cx = tmp.centerX()
        val cy = tmp.centerY()
        text.color = ink
        when (k.type) {
            KeyType.CHAR -> {
                val label = if (shift != ShiftState.OFF) Variants.shiftChar(k.label) else k.label
                val small = k.style == KeyStyle.ACCENT || k.style == KeyStyle.NUM
                text.textSize = min(rowH * (if (small) 0.36f else 0.42f), (if (small) 19 else 23) * dp)
                text.typeface = typeface(if (small) 500 else 450)
                drawCentered(c, label, cx, cy)
                if (settings.subLegends && k.hint != null) {
                    text.color = t.muted
                    text.textSize = min(rowH * 0.2f, 10 * dp)
                    text.typeface = typeface(500)
                    c.drawText(k.hint, tmp.right - 6 * dp, tmp.top + 12 * dp, text)
                } else if (settings.subLegends && k.variants.isNotEmpty() && k.style == KeyStyle.NORMAL && k.label.length == 1 && k.label[0].isLetter()) {
                    fill.color = palette.ghost
                    c.drawCircle(tmp.right - 6 * dp, tmp.top + 6 * dp, 1.4f * dp, fill)
                }
            }
            KeyType.SPACE -> {
                text.color = t.muted
                text.textSize = 12 * dp
                text.typeface = typeface(500)
                drawCentered(c, "✦  " + k.label, cx, cy)
                if (spaceHint) {
                    // the system globe is gone: a small globe says "hold me to switch keyboards"
                    val sz = min(rowH * 0.26f, 12 * dp)
                    KeyIcons.draw(c, Icon.GLOBE, tmp.right - 5 * dp - sz / 2, tmp.top + 5 * dp + sz / 2, sz, t.muted, stroke, fill)
                }
            }
            KeyType.SHIFT -> {
                text.color = if (shift == ShiftState.ONCE || shift == ShiftState.AUTO) palette.accent else ink
                text.textSize = 20 * dp
                text.typeface = Typeface.DEFAULT
                drawCentered(c, if (shift == ShiftState.OFF) "⇧" else "⬆", cx, cy)
                if (shift == ShiftState.LOCKED) {
                    fill.color = ink
                    c.drawRoundRect(cx - 7 * dp, tmp.bottom - 7 * dp, cx + 7 * dp, tmp.bottom - 5 * dp, dp, dp, fill)
                }
            }
            KeyType.ENTER -> {
                text.textSize = 20 * dp
                text.typeface = typeface(600)
                drawCentered(c, enterLabel, cx, cy)
            }
            KeyType.BACKSPACE -> {
                text.textSize = 19 * dp
                text.typeface = Typeface.DEFAULT
                drawCentered(c, k.label, cx, cy)
            }
            else -> {
                text.textSize = 14 * dp
                text.typeface = typeface(600)
                drawCentered(c, k.label, cx, cy)
            }
        }
    }

    private fun drawPreview(c: Canvas, b: Box) {
        val t = palette.theme
        val w = max(b.rect.width() * 1.25f, 44 * dp)
        val h = rowH * 1.1f
        val top = PopupGeometry.previewTop(b.rect.top - 2 * dp, h)
        val left = (b.rect.centerX() - w / 2).coerceIn(2 * dp, width - w - 2 * dp)
        tmp.set(left, top, left + w, top + h)
        drawPopupBody(c, tmp)
        val label = if (shift != ShiftState.OFF) Variants.shiftChar(b.key.label) else b.key.label
        text.color = t.text
        text.textSize = min(h * 0.5f, 30 * dp)
        text.typeface = typeface(500)
        drawCentered(c, label, tmp.centerX(), tmp.centerY())
    }

    private fun drawPopupBody(c: Canvas, r: RectF) {
        val t = palette.theme
        val rad = radius() + 2 * dp
        fill.shader = null
        fill.color = t.shadowColor()
        c.drawRoundRect(r.left, r.top + 2 * dp, r.right, r.bottom + 3 * dp, rad, rad, fill)
        fill.color = t.bg2
        c.drawRoundRect(r, rad, rad, fill)
        stroke.color = palette.accent
        stroke.strokeWidth = 1 * dp
        c.drawRoundRect(r, rad, rad, stroke)
    }

    private fun com.resyst.vk.core.Theme.shadowColor(): Int =
        if (dark) 0xB0000000.toInt() else 0x403A352A

    private fun drawVariants(c: Canvas, p: Ptr, pop: VariantPopup) {
        val t = palette.theme
        val h = rowH * 0.95f
        tmp.set(pop.left, p.popupTop, pop.left + pop.width, p.popupTop + h)
        drawPopupBody(c, tmp)
        val body = RectF(tmp)
        for (i in p.popupItems.indices) {
            val l = pop.cellLeft(i)
            val cell = RectF(l + 2 * dp, body.top + 3 * dp, l + pop.cell - 2 * dp, body.bottom - 3 * dp)
            if (i == p.sel) {
                fill.color = palette.accent
                c.drawRoundRect(cell, radius(), radius(), fill)
            }
            text.color = if (i == p.sel) palette.accentInk else t.text
            text.textSize = min(h * 0.45f, 24 * dp)
            text.typeface = typeface(500)
            drawCentered(c, p.popupItems[i], cell.centerX(), cell.centerY())
        }
    }

    // ── accessibility (TalkBack: explore by touch + lift-to-type) ────────
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val handled = a11y.dispatchHoverEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT && accessibility?.isTouchExplorationEnabled == true) {
            val x = event.x
            val y = event.y
            if (x >= 0 && x < width && y >= 0 && y < height) {
                if (clipboardOpen) { clipPanel.hitAt(x, y)?.let { panelAct(it) }; return true }
                stripAt(x, y)?.let { stripTap(it); return true }
                boxAt(x, y)?.let { activate(it); return true }
            }
        }
        return handled || super.dispatchHoverEvent(event)
    }

    private fun activate(b: Box) {
        val l = listener ?: return
        l.onKeyDown(b.key)
        if (b.key.type != KeyType.SHIFT) l.onKeyCommit(b.key)
        l.onKeyUp(b.key)
    }

    private fun describe(k: Key): String = when (k.type) {
        KeyType.CHAR -> when (k.text) {
            "," -> "coma"; "." -> "punto"; "@" -> "arroba"; "/" -> "barra"; "-" -> "guion"
            else -> if (shift != ShiftState.OFF) Variants.shiftChar(k.text) else k.text
        }
        KeyType.SHIFT -> when (shift) {
            ShiftState.OFF -> "Mayúsculas"
            ShiftState.LOCKED -> "Mayúsculas, bloqueadas"
            else -> "Mayúsculas, activadas"
        }
        KeyType.BACKSPACE -> "Borrar"
        KeyType.ENTER -> enterDesc
        KeyType.SPACE -> if (spaceHint) "Espacio. Mantén pulsado para cambiar de teclado" else "Espacio"
        KeyType.LAYER -> when (k.target) {
            Layer.SYMBOLS -> "Símbolos"
            Layer.SYMBOLS2 -> "Más símbolos"
            else -> "Letras"
        }
        KeyType.SPACER -> ""
    }

    private inner class KeyA11y : ExploreByTouchHelper(this) {
        private val stripBase = 10_000
        private val panelBase = 20_000

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (clipboardOpen) {
                val h = clipPanel.hitAt(x, y) ?: return INVALID_ID
                return panelBase + clipPanel.hits.indexOf(h)
            }
            stripAt(x, y)?.let { return stripBase + stripItems.indexOf(it) }
            val b = boxAt(x, y) ?: return INVALID_ID
            return boxes.indexOf(b)
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            if (clipboardOpen) { for (i in clipPanel.hits.indices) ids += panelBase + i; return }
            for (i in stripItems.indices) ids += stripBase + i
            for (i in boxes.indices) ids += i
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val r = RectF()
            val desc: String
            if (id >= panelBase) {
                val h = clipPanel.hits.getOrNull(id - panelBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                if (h?.act == ClipboardPanel.Act.ROW) node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            } else if (id >= stripBase) {
                val s = stripItems.getOrNull(id - stripBase)
                desc = when (s?.kind) {
                    StripKind.PROFILE -> "Perfil $profileName. Toca para cambiar de perfil"
                    StripKind.SETTINGS -> "Ajustes de Resyst VK"
                    StripKind.SUGGESTION -> "Sugerencia: ${s.text}"
                    StripKind.PASTE -> if (pasteImage) "Pegar imagen del portapapeles" else "Pegar del portapapeles: ${s.text}"
                    StripKind.CLIP -> "Historial del portapapeles"
                    null -> ""
                }
                s?.let { r.set(it.rect) }
            } else {
                val b = boxes.getOrNull(id)
                desc = b?.let { describe(it.key) } ?: ""
                b?.let { r.set(it.rect) }
                // G6: long-press space = keyboard picker, also for TalkBack users
                if (b?.key?.type == KeyType.SPACE) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK, "Cambiar de teclado"))
            }
            node.contentDescription = desc
            node.className = "android.widget.Button"
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(android.graphics.Rect(r.left.toInt(), r.top.toInt(), max(r.left.toInt() + 1, r.right.toInt()), max(r.top.toInt() + 1, r.bottom.toInt())))
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
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
    }
}
