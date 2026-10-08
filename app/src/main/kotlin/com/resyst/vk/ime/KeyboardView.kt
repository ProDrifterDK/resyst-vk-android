package com.resyst.vk.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.resyst.vk.core.ColorMath
import com.resyst.vk.core.DeleteSwipe
import com.resyst.vk.core.EditOp
import com.resyst.vk.core.EditPad
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
import com.resyst.vk.core.PopupGeometry
import com.resyst.vk.core.Quick
import com.resyst.vk.core.QuickAction
import com.resyst.vk.core.ShiftState
import com.resyst.vk.core.SpaceGesture
import com.resyst.vk.core.StripPlan
import com.resyst.vk.core.UpdateNotice
import com.resyst.vk.core.UpdateSurface
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

    interface Listener {
        fun onKeyDown(key: Key)
        fun onKeyUp(key: Key)
        fun onKeyCommit(key: Key)
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
    }

    var listener: Listener? = null

    // ── state pushed by the service ──────────────────────────────────────
    private var rows: List<List<Key>> = emptyList()
    private var baseRowCount = 5
    private var layer = Layer.LETTERS
    private var settings = KbSettings()
    private var palette = Palette.of("lab", null)
    private var shift = ShiftState.OFF
    private var enterDesc = "Intro"
    private var enterIcon = Icon.ENTER
    private var suggestions: List<String> = emptyList()
    private var pasteLabel: String? = null
    private var pasteImage = false
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
    val clipPanel = ClipboardPanel(resources.displayMetrics.density)
    var clipboardOpen = false
        private set

    /** The emoji panel (r8), same overlay pattern as the clipboard. */
    val emojiPanel = EmojiPanel(resources.displayMetrics.density)
    var emojiOpen = false
        private set

    /** r10: the quick panel under ⚙ (UX-3), same overlay pattern. */
    val quickPanel = QuickPanel(resources.displayMetrics.density)
    var quickOpen = false
        private set
    private var mode = Mode.NONE
    /** r10 (bet 5): the edit panel, opened from the quick panel's «Edición». */
    val editPanel = EditPanel(resources.displayMetrics.density)
    var editOpen = false
        private set
    private val panelOpen get() = clipboardOpen || emojiOpen || quickOpen || editOpen

    /** The edit panel; [selecting] = the arrows extend the selection; [secret] closes Copiar/Cortar. */
    fun showEdit(selecting: Boolean) {
        cancelPointers()
        hideClipboard(); hideEmoji(); hideQuick()
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

    /** The quick panel with [tiles]; [update] = the r9 notice line (null = none). */
    fun showQuick(tiles: List<Quick.Tile>, update: String?, mode: Mode) {
        cancelPointers()
        hideClipboard(); hideEmoji(); hideEdit()
        this.mode = mode
        quickOpen = true
        layoutPanel()
        quickPanel.set(tiles, update)
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
    fun setUpdate(version: String?, surface: UpdateSurface) {
        val sf = if (version == null) UpdateSurface.NONE else surface
        if (version == updateVersion && sf == updateSurface) return
        updateVersion = version
        updateSurface = sf
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
        emojiOpen = true
        layoutPanel()
        emojiPanel.open(recents)
        invalidate(); a11y.invalidateRoot()
    }

    fun updateEmojiRecents(recents: List<String>) {
        emojiPanel.setRecents(recents)
        if (emojiOpen) { invalidate(); a11y.invalidateRoot() }
    }

    fun hideEmoji() {
        if (!emojiOpen) return
        cancelPointers()
        emojiOpen = false
        invalidate(); a11y.invalidateRoot()
    }

    // ── geometry ─────────────────────────────────────────────────────────
    private val dp = resources.displayMetrics.density
    private class Box(val key: Key, val rect: RectF, val cellLeft: Float, val cellRight: Float, val row: Int)
    private val boxes = ArrayList<Box>()
    private val rowBoxes = ArrayList<List<Box>>()
    private var rowH = 0f
    private val stripH get() = 42 * dp

    private enum class StripKind { PASTE, SUGGESTION, SETTINGS, DAYNIGHT, UPDATE, UPDATE_X, NO_MEMORY }
    private class StripItem(val kind: StripKind, val text: String, val rect: RectF)
    private val stripItems = ArrayList<StripItem>()

    // ── touch ────────────────────────────────────────────────────────────
    private class Ptr(val id: Int, var box: Box?, val downX: Float) {
        /** r10: this ⌫ touch became a word-delete swipe (no more repeats). */
        var swiped = false
        var popup: VariantPopup? = null
        var popupItems: List<String> = emptyList()
        var popupTop = 0f
        var sel = 0
        var cursorMode = false
        var cursorX = 0f
        var longFired = false
        var strip: StripItem? = null
        /** r10: a one-handed rail button (move to that side / OFF = full width). */
        var rail: OneHand? = null
        /** r10 (bet 4): where/when the finger landed, for the space flick; a flick commits nothing. */
        var downY = 0f
        var downAt = 0L
        var flicked = false
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
        if (s.oneHanded != settings.oneHanded) cancelPointers()
        settings = s
        palette = p
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

    /** The leading "Pegar" chip: [label] = what it pastes (already masked), null = no chip. */
    fun setPasteOffer(label: String?, image: Boolean) {
        if (label == pasteLabel && image == pasteImage) return
        pasteLabel = label; pasteImage = image
        layoutStrip(); invalidate(); a11y.invalidateRoot()
    }

    fun showClipboard(items: List<com.resyst.vk.core.ClipboardHistory.Entry>, now: Long) {
        cancelPointers()
        emojiOpen = false
        quickOpen = false
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
            val label = UpdateNotice.fit(upd, room - CHIP_ICON_W * dp - CHIP_X_W * dp - CHIP_TEXT_END * dp,
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
            val label = if (pasteImage) "Pegar imagen" else paste
            val room = r - l
            val want = text.measureText(label) + 44 * dp
            val slot = if (shown.isEmpty()) room else room / (shown.size + 1)
            val w = min(want, max(slot, min(room, 96 * dp)))
            stripItems += StripItem(StripKind.PASTE, label, RectF(l, 6 * dp, l + w, h - 6 * dp))
            l += w + 4 * dp
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
        if (emojiOpen) { emojiDown(id, x, y); return }
        if (quickOpen) { quickDown(id, x, y); return }
        if (editOpen) { editDown(id, x, y); return }
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
        val p = Ptr(id, box, x).also { it.downY = y; it.downAt = SystemClock.uptimeMillis() }
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
        if (id == quickPtr || id == editPtr) return
        if (id == emojiPtr) { emojiMove(y); return }
        if (id == panelPtr) { panelMove(y); return }
        val p = ptrs[id] ?: return
        val b = p.box ?: return
        val popup = p.popup
        if (popup != null) {
            val s = popup.indexAt(x)
            if (s != p.sel) { p.sel = s; invalidate() }
            return
        }
        if (b.key.type == KeyType.BACKSPACE && !p.swiped) {
            // r10 (UX-6): a leftward swipe on ⌫ deletes the previous word (QE6), once per touch
            if (DeleteSwipe.isSwipe(x - p.downX, y - p.downY, dp)) {
                handler.removeCallbacksAndMessages(p)
                p.swiped = true
                listener?.onDeleteWord()
                invalidate()
            }
            return
        }
        if (b.key.type == KeyType.SPACE) {
            if (!p.cursorMode && !p.longFired && !p.flicked) {
                // r10 (FL1/FL2): a quick vertical flick switches the language, a horizontal drag moves the cursor
                when (SpaceGesture.classify((x - p.downX) / dp, (y - p.downY) / dp, SystemClock.uptimeMillis() - p.downAt)) {
                    SpaceGesture.Kind.FLICK -> {
                        p.flicked = true
                        handler.removeCallbacksAndMessages(p) // FL4: no picker after a flick
                        listener?.onSpaceFlick()
                        invalidate()
                    }
                    SpaceGesture.Kind.CURSOR -> {
                        p.cursorMode = true
                        p.cursorX = x
                        handler.removeCallbacksAndMessages(p)
                    }
                    SpaceGesture.Kind.NONE -> Unit
                }
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
        if (id == quickPtr) { quickUp(x, y); return }
        if (id == editPtr) { editUp(x, y); return }
        if (id == emojiPtr) { emojiUp(x, y); return }
        if (id == panelPtr) { panelUp(x, y); return }
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
        val popup = p.popup
        when {
            popup != null -> listener?.onVariant(p.popupItems[p.sel])
            p.cursorMode || p.longFired || p.flicked -> Unit
            b.key.type == KeyType.BACKSPACE || b.key.type == KeyType.SHIFT -> Unit
            b.key.type == KeyType.EMOJI -> listener?.onEmojiKey()
            else -> listener?.onKeyCommit(b.key)
        }
        listener?.onKeyUp(b.key)
        invalidate()
    }

    private fun stripTap(s: StripItem) {
        when (s.kind) {
            StripKind.SETTINGS -> listener?.onQuickPanel()
            StripKind.SUGGESTION -> listener?.onSuggestion(s.text)
            StripKind.PASTE -> listener?.onPasteOffer()
            StripKind.NO_MEMORY -> listener?.onNoMemory()
            StripKind.DAYNIGHT -> listener?.onDayNight()
            StripKind.UPDATE -> if (!chipExiting) listener?.onUpdateChip()
            StripKind.UPDATE_X -> if (!chipExiting) listener?.onUpdateDismiss()
        }
    }

    // ── quick panel touch: one finger, tap = act ──────────────────────────
    private var quickPtr = -1
    private var quickHit: QuickPanel.Hit? = null

    private fun quickDown(id: Int, x: Float, y: Float) {
        if (quickPtr != -1) return
        quickPtr = id
        quickHit = quickPanel.hitAt(x, y)?.takeIf { it.enabled }
        invalidate()
    }

    private fun quickUp(x: Float, y: Float) {
        quickPtr = -1
        val h = quickHit
        quickHit = null
        if (h != null && quickPanel.hitAt(x, y) === h) quickAct(h)
        invalidate()
    }

    private fun quickAct(h: QuickPanel.Hit) {
        if (!h.enabled) return
        when (h.act) {
            QuickPanel.Act.CLOSE -> hideQuick()
            QuickPanel.Act.UPDATE -> { hideQuick(); listener?.onUpdateChip() }
            QuickPanel.Act.TILE -> h.action?.let { listener?.onQuickAction(it) }
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

    private fun emojiDown(id: Int, x: Float, y: Float) {
        if (emojiPtr != -1) return
        emojiPtr = id
        val hit = emojiPanel.hitAt(x, y)
        emojiHit = hit
        emojiDownY = y; emojiLastY = y
        emojiScrolling = false
        if (hit?.act == EmojiPanel.Act.DELETE) {
            listener?.onEmojiPanel(EmojiPanel.Act.DELETE, "")
            scheduleEmojiRepeat(REPEAT_START_MS)
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

    private fun emojiMove(y: Float) {
        val h = emojiHit
        val inGrid = h == null || h.act == EmojiPanel.Act.EMOJI
        if (!emojiScrolling && inGrid && abs(y - emojiDownY) > 10 * dp) {
            emojiScrolling = true
            emojiHit = null
        }
        if (emojiScrolling && emojiPanel.scrollBy(emojiLastY - y)) { invalidate(); a11y.invalidateRoot() }
        emojiLastY = y
    }

    private fun emojiUp(x: Float, y: Float) {
        handler.removeCallbacksAndMessages(emojiToken)
        emojiPtr = -1
        val h = emojiHit
        emojiHit = null
        if (!emojiScrolling && h != null && h.act != EmojiPanel.Act.DELETE && emojiPanel.hitAt(x, y)?.let { it.act == h.act && it.index == h.index } == true) emojiAct(h)
        invalidate()
    }

    private fun emojiAct(h: EmojiPanel.Hit) {
        when (h.act) {
            EmojiPanel.Act.TAB -> emojiPanel.selectTab(h.index)
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
        handler.removeCallbacksAndMessages(editToken)
        editPtr = -1
        editHit = null
        quickPtr = -1
        quickHit = null
        handler.removeCallbacksAndMessages(panelToken)
        panelPtr = -1
        panelHit = null
        handler.removeCallbacksAndMessages(emojiToken)
        emojiPtr = -1
        emojiHit = null
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
    override fun onDraw(canvas: Canvas) {
        val t = palette.theme
        canvas.drawColor(t.bg)
        if (clipboardOpen) {
            clipPanel.draw(canvas, palette, radius(), ::typeface, if (panelScrolling || panelLong) null else panelHit)
            return
        }
        if (emojiOpen) {
            emojiPanel.draw(canvas, palette, radius(), ::typeface, if (emojiScrolling) null else emojiHit)
            return
        }
        if (quickOpen) {
            quickPanel.draw(canvas, palette, radius(), ::typeface, quickHit, mode)
            return
        }
        if (editOpen) {
            editPanel.draw(canvas, palette, radius(), ::typeface, editHit)
            return
        }
        drawStrip(canvas)
        drawRail(canvas)
        val pressed = HashSet<Box>()
        for (p in ptrs.values) p.box?.let { if (!p.cursorMode) pressed += it }
        for (b in boxes) drawKey(canvas, b, b in pressed)
        for (p in ptrs.values) {
            val b = p.box ?: continue
            val pop = p.popup
            if (pop != null) drawVariants(canvas, p, pop)
            else if (settings.popups && !secretField && b.key.type == KeyType.CHAR && !p.cursorMode) drawPreview(canvas, b)
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
                StripKind.PASTE -> {
                    val pressed = s in pressedStrip
                    fill.color = if (pressed) ColorMath.withAlpha(palette.accent, 0.3f) else palette.accentSoft
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                    stroke.color = palette.accentGlow
                    stroke.strokeWidth = 1 * dp
                    c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
                    KeyIcons.draw(c, Icon.CLIPBOARD, r.left + 17 * dp, r.centerY(), 14 * dp, palette.accent, stroke, fill)
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
                StripKind.DAYNIGHT -> {
                    val pressed = s in pressedStrip
                    if (pressed) {
                        fill.color = t.keyHi
                        tmp.set(r.left + 3 * dp, 6 * dp, r.right - 3 * dp, stripH - 6 * dp)
                        c.drawRoundRect(tmp, radius(), radius(), fill)
                    }
                    KeyIcons.draw(c, if (dayNight == true) Icon.SUN else Icon.MOON, r.centerX(), r.centerY(), 17 * dp, if (pressed) palette.accent else t.muted, stroke, fill)
                }
                StripKind.SETTINGS -> {
                    val on = s in pressedStrip
                    if (on) {
                        fill.color = t.keyHi
                        tmp.set(r.left + 4 * dp, 6 * dp, r.right - 4 * dp, stripH - 6 * dp)
                        c.drawRoundRect(tmp, radius(), radius(), fill)
                    }
                    KeyIcons.draw(c, Icon.GEAR, r.centerX(), r.centerY(), 19 * dp, if (on || gearDot) palette.accent else t.muted, stroke, fill)
                    if (gearDot) { // r9: an update waits — amber dot, ringed in the strip color
                        fill.color = t.bg
                        c.drawCircle(r.centerX() + 8 * dp, r.centerY() - 8 * dp, 5 * dp, fill)
                        fill.color = palette.accent
                        c.drawCircle(r.centerX() + 8 * dp, r.centerY() - 8 * dp, 3.5f * dp, fill)
                    }
                }
                StripKind.NO_MEMORY -> drawNoMemory(c, r, s in pressedStrip)
                StripKind.UPDATE_X -> Unit // drawn with its chip
                StripKind.UPDATE -> drawUpdateChip(c, s, pressedStrip)
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

    /**
     * r9 update chip: accent-soft pill with an amber hairline, a filled amber disc holding a
     * download arrow, the version line in the accent and the action quieter, then a ✕ cell behind
     * a hairline divider. Fades + slides 8 dp in; fades out.
     */
    private fun drawUpdateChip(c: Canvas, s: StripItem, pressedStrip: Set<StripItem>) {
        val label = updateLabel ?: return
        val x = stripItems.firstOrNull { it.kind == StripKind.UPDATE_X } ?: return
        val t = palette.theme
        val a = chipAnim.coerceIn(0f, 1f)
        if (a <= 0f) return
        val dx = (1f - a) * 8 * dp
        // same 6 dp inset as the profile + paste pills: one height across the strip
        val body = RectF(s.rect.left + dx, 6 * dp, x.rect.right + dx, stripH - 6 * dp)
        val save = c.saveLayerAlpha(body.left - 2 * dp, 0f, body.right + 2 * dp, stripH, (a * 255).toInt())
        val rad = body.height() / 2
        fill.shader = null
        fill.color = if (s in pressedStrip) ColorMath.withAlpha(palette.accent, 0.3f) else palette.accentSoft
        c.drawRoundRect(body, rad, rad, fill)
        // the paste chip's hairline: amber, but the disc alone carries full strength (Enter stays the hero)
        stroke.color = palette.accentGlow
        stroke.strokeWidth = 1 * dp
        stroke.strokeCap = Paint.Cap.BUTT
        c.drawRoundRect(body, rad, rad, stroke)
        // icon: amber disc + download arrow in the accent's ink
        val cx = body.left + 16 * dp
        val cy = body.centerY()
        fill.color = palette.accent
        c.drawCircle(cx, cy, 10 * dp, fill)
        stroke.color = palette.accentInk
        stroke.strokeWidth = 1.6f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy - 4.8f * dp, cx, cy + 2.2f * dp, stroke)
        c.drawLine(cx - 3.4f * dp, cy - 1f * dp, cx, cy + 2.5f * dp, stroke)
        c.drawLine(cx + 3.4f * dp, cy - 1f * dp, cx, cy + 2.5f * dp, stroke)
        c.drawLine(cx - 4.2f * dp, cy + 5.2f * dp, cx + 4.2f * dp, cy + 5.2f * dp, stroke)
        // text
        val tx = body.left + CHIP_ICON_W * dp
        val tp = chipTitlePaint(label.twoLine).apply { color = palette.accent }
        val ap = chipActionPaint(label.twoLine).apply { color = t.textMod }
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
        val xr = RectF(x.rect.left + dx, body.top, x.rect.right + dx, body.bottom)
        if (x in pressedStrip) {
            fill.color = ColorMath.withAlpha(palette.accent, 0.22f)
            c.drawCircle(xr.centerX(), xr.centerY(), 13 * dp, fill)
        }
        fill.color = ColorMath.withAlpha(palette.accent, 0.3f)
        c.drawRect(xr.left, body.top + body.height() * 0.24f, xr.left + dp, body.bottom - body.height() * 0.24f, fill)
        stroke.color = if (x in pressedStrip) palette.accent else t.textMod
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
        val t = palette.theme
        if (pressed) {
            fill.color = t.keyHi
            tmp.set(r.left + 3 * dp, 8 * dp, r.right - 3 * dp, stripH - 8 * dp)
            c.drawRoundRect(tmp, radius(), radius(), fill)
        }
        val cx = r.centerX()
        val cy = r.centerY()
        stroke.color = if (pressed) palette.accent else t.muted
        stroke.strokeWidth = 1.5f * dp
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawCircle(cx, cy, 6.5f * dp, stroke)
        c.drawLine(cx - 4.6f * dp, cy + 4.6f * dp, cx + 4.6f * dp, cy - 4.6f * dp, stroke)
    }

    /** r10 (UX-13): the one-handed rail — move to the other side (top) / back to full width (bottom). */
    private fun drawRail(c: Canvas) {
        if (rail.isEmpty()) return
        val t = palette.theme
        val pressed = ptrs.values.mapNotNull { it.rail }.toSet()
        for ((side, r) in rail) {
            val on = side in pressed
            // drawn like a modifier key (fill + edge), so the rail reads as buttons, not dead space
            fill.shader = null
            fill.color = if (on) t.keyHi else t.keyMod
            tmp.set(r.left + 5 * dp, r.top + 4 * dp, r.right - 5 * dp, r.bottom - 4 * dp)
            c.drawRoundRect(tmp, radius() + 2 * dp, radius() + 2 * dp, fill)
            stroke.color = if (on) palette.accentGlow else t.edgeHi
            stroke.strokeWidth = dp
            c.drawRoundRect(tmp, radius() + 2 * dp, radius() + 2 * dp, stroke)
            val icon = when (side) { OneHand.LEFT -> Icon.ARROW_LEFT; OneHand.RIGHT -> Icon.ARROW_RIGHT; OneHand.OFF -> Icon.EXPAND }
            KeyIcons.draw(c, icon, r.centerX(), r.centerY(), min(24 * dp, r.width() * 0.5f), if (on) palette.accent else t.textMod, stroke, fill)
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
                // QI2: off = outline, once/auto = filled (accent), locked = filled + bar (on the accent key)
                val icon = when (shift) {
                    ShiftState.OFF -> Icon.SHIFT
                    ShiftState.LOCKED -> Icon.SHIFT_LOCK
                    ShiftState.ONCE, ShiftState.AUTO -> Icon.SHIFT_ON
                }
                val color = if (shift == ShiftState.ONCE || shift == ShiftState.AUTO) palette.accent else ink
                KeyIcons.draw(c, icon, cx, cy, KeyIcons.size(rowH, tmp.width(), dp), color, stroke, fill)
            }
            KeyType.ENTER -> KeyIcons.draw(c, enterIcon, cx, cy, KeyIcons.size(rowH, tmp.width(), dp), ink, stroke, fill)
            KeyType.BACKSPACE -> KeyIcons.draw(c, Icon.BACKSPACE, cx, cy, KeyIcons.size(rowH, tmp.width(), dp), ink, stroke, fill)
            KeyType.EMOJI -> KeyIcons.draw(c, Icon.EMOJI, cx, cy, KeyIcons.size(rowH, tmp.width(), dp), ink, stroke, fill)
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
        KeyType.EMOJI -> "Emojis"
    }

    private inner class KeyA11y : ExploreByTouchHelper(this) {
        private val stripBase = 10_000
        private val panelBase = 20_000
        private val emojiBase = 30_000
        private val quickBase = 40_000
        private val railBase = 50_000
        private val editBase = 60_000

        override fun getVirtualViewAt(x: Float, y: Float): Int {
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
            if (id >= editBase) {
                val h = editPanel.hits.getOrNull(id - editBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                node.isEnabled = h?.enabled ?: false
            } else if (id >= railBase) {
                val e = rail.getOrNull(id - railBase)
                desc = when (e?.first) {
                    OneHand.LEFT -> "Mover el teclado a la izquierda"
                    OneHand.RIGHT -> "Mover el teclado a la derecha"
                    OneHand.OFF -> "Teclado a todo el ancho"
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
            } else if (id >= panelBase) {
                val h = clipPanel.hits.getOrNull(id - panelBase)
                desc = h?.desc ?: ""
                h?.let { r.set(it.rect) }
                if (h?.act == ClipboardPanel.Act.ROW) node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            } else if (id >= stripBase) {
                val s = stripItems.getOrNull(id - stripBase)
                desc = when (s?.kind) {
                    StripKind.SETTINGS -> if (gearDot) "Ajustes de Resyst VK. Actualización ${updateVersion} disponible" else "Ajustes de Resyst VK"
                    StripKind.NO_MEMORY -> "Sin memoria: ${s.text}"
                    StripKind.UPDATE -> "Resyst VK ${s.text} disponible. Toca para actualizar"
                    StripKind.UPDATE_X -> "Descartar el aviso de la versión ${s.text}"
                    StripKind.SUGGESTION -> "Sugerencia: ${s.text}"
                    StripKind.PASTE -> if (pasteImage) "Pegar imagen del portapapeles" else "Pegar del portapapeles: ${s.text}"
                    StripKind.DAYNIGHT -> if (dayNight == true) "Cambiar a tema claro" else "Cambiar a tema oscuro"
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
    }
}
