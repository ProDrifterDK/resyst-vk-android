package com.resyst.vk.ime

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.resyst.vk.core.FieldInfo
import com.resyst.vk.core.FieldKind
import com.resyst.vk.core.HapticEvent
import com.resyst.vk.core.Haptics
import com.resyst.vk.core.ImeAction
import com.resyst.vk.core.Key
import com.resyst.vk.core.KeyStyle
import com.resyst.vk.core.KeyType
import com.resyst.vk.core.KeyboardEngine
import com.resyst.vk.core.KeyboardLayouts
import com.resyst.vk.core.Layer
import com.resyst.vk.core.LayoutSpec
import com.resyst.vk.core.Out
import com.resyst.vk.core.Palette
import com.resyst.vk.core.ProfileStore
import com.resyst.vk.core.SoundKind
import com.resyst.vk.core.Subtypes
import com.resyst.vk.core.Suggest
import com.resyst.vk.settings.SettingsActivity
import com.resyst.vk.settings.SettingsRepo

/**
 * The system keyboard. Android binds this service (BIND_INPUT_METHOD) whenever any app
 * requests text input; every character reaches the app through InputConnection.commitText.
 */
class ResystImeService : InputMethodService(), KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var repo: SettingsRepo
    private lateinit var store: ProfileStore
    private val engine = KeyboardEngine()
    private var view: KeyboardView? = null
    private var sound: KeySoundPlayer? = null
    private var lexicon: Lexicon? = null
    private var currentWord = ""
    private var fieldKind = FieldKind.TEXT
    private var noSuggestField = false
    private lateinit var haptics: HapticPlayer
    private lateinit var subtypes: SubtypeSync

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepo(this)
        store = repo.load()
        repo.prefs.registerOnSharedPreferenceChangeListener(this)
        sound = KeySoundPlayer(this)
        lexicon = Lexicon(this)
        haptics = HapticPlayer(this)
        subtypes = SubtypeSync(this)
        subtypes.enableAllOnce()
    }

    override fun onDestroy() {
        repo.prefs.unregisterOnSharedPreferenceChangeListener(this)
        sound?.release()
        sound = null
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        store = repo.load()
        syncSubtype(systemSubtype = null)
        applySettings()
    }

    private val s get() = store.activeProfile.settings

    // ── languages ⇄ IME subtypes ────────────────────────────────────────
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        syncSubtype(systemSubtype = newSubtype)
    }

    /**
     * Three-way merge between the active profile's language and the system subtype
     * (see [Subtypes.reconcile]). [systemSubtype] comes from the change callback; null means
     * "ask the system".
     */
    private fun syncSubtype(systemSubtype: InputMethodSubtype?) {
        if (!subtypes.isCurrentIme()) return // only the current IME owns the system's subtype
        val app = s.lang
        val system = subtypes.langOf(systemSubtype) ?: subtypes.systemLang()
        val last = subtypes.lastSynced
        when (val d = Subtypes.reconcile(app, system, last)) {
            Subtypes.Sync.NONE -> subtypes.lastSynced = app
            Subtypes.Sync.PULL_FROM_SYSTEM -> {
                val lang = system ?: return
                Log.i(TAG, "subtype: pull $lang (app=$app last=$last)")
                subtypes.lastSynced = lang
                store = store.update(store.active) { it.copy(lang = lang) }
                repo.save(store) // listener → sync sees agreement → NONE
                applySettings()
            }
            Subtypes.Sync.PUSH_TO_SYSTEM -> {
                val target = subtypes.subtypeFor(app)
                val id = subtypes.info?.id
                if (target == null || id == null || !subtypes.isEnabled(app)) {
                    // U6: the user disabled this language in system settings; keep the app's
                    // layout, never fight the system (no lastSynced update, no loop).
                    Log.w(TAG, "subtype: $app is not enabled in system settings; not pushing ($d)")
                    return
                }
                Log.i(TAG, "subtype: push $app (system=$system last=$last)")
                subtypes.lastSynced = app // set first: our own callback must see agreement
                if (Build.VERSION.SDK_INT >= 28) {
                    runCatching { switchInputMethod(id, target) }.onFailure { Log.w(TAG, "push failed", it) }
                } else {
                    @Suppress("DEPRECATION")
                    val token = window?.window?.attributes?.token ?: return
                    runCatching { getSystemService(InputMethodManager::class.java)?.setInputMethodAndSubtype(token, id, target) }
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.listener = this
        v.reserveNavBar = true
        view = v
        applySettings()
        return v
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        fieldKind = when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            cls == InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
            variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
            else -> FieldKind.TEXT
        }
        noSuggestField = fieldKind != FieldKind.TEXT || flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val rawAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        // Multi-line text fields get a newline on Enter unless they ask for a real action.
        val multiLine = cls == InputType.TYPE_CLASS_TEXT && flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 &&
            (noEnterAction || rawAction == EditorInfo.IME_ACTION_NONE || rawAction == EditorInfo.IME_ACTION_UNSPECIFIED)
        val action = if (noEnterAction) ImeAction.NONE else
            when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_GO -> ImeAction.GO
                EditorInfo.IME_ACTION_SEARCH -> ImeAction.SEARCH
                EditorInfo.IME_ACTION_SEND -> ImeAction.SEND
                EditorInfo.IME_ACTION_NEXT -> ImeAction.NEXT
                EditorInfo.IME_ACTION_DONE -> ImeAction.DONE
                EditorInfo.IME_ACTION_PREVIOUS -> ImeAction.PREVIOUS
                else -> ImeAction.NONE
            }
        val autoCap = fieldKind == FieldKind.TEXT && info.inputType and (
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
        engine.start(FieldInfo(fieldKind, multiLine, action, autoCap))
        view?.setEnter(enterLabel(action, multiLine), enterDesc(action, multiLine))
        subtypes.enableAllOnce()
        syncSubtype(systemSubtype = null)
        applySettings()
        refreshContext()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        view?.reset()
        currentWord = ""
        view?.setSuggestions(emptyList())
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshContext()
    }

    // ── settings → view ─────────────────────────────────────────────────
    private fun applySettings() {
        val v = view ?: return
        val st = s
        engine.autoCapEnabled = st.autoCap
        engine.doubleSpacePeriod = st.doubleSpace
        v.setStyle(st, Palette.of(st.theme, st.accent))
        v.setProfile(store.activeProfile.icon, store.activeProfile.name)
        sound?.configure(st.sound, st.soundPack)
        if (st.suggest) lexicon?.warm(st.lang)
        rebuildLayout()
    }

    private fun rebuildLayout() {
        val v = view ?: return
        val st = s
        val showSwitch = runCatching { shouldOfferSwitchingToNextInputMethod() }.getOrDefault(false)
        val spec = LayoutSpec(st.lang, st.effectiveTopRow, showSwitch, fieldKind)
        val rows = KeyboardLayouts.rows(engine.layer, spec)
        // Height is anchored to the letters layer so switching layers never jumps.
        val base = if (engine.layer == Layer.NUMPAD) 4 else st.baseRowCount
        v.setKeyboard(rows, base, engine.layer)
        v.setShift(engine.shift)
    }

    // ── KeyboardView.Listener ───────────────────────────────────────────
    override fun onKeyDown(key: Key) {
        feedback(key)
        if (key.type == KeyType.SHIFT) {
            engine.shiftDown(SystemClock.uptimeMillis())
            view?.setShift(engine.shift)
        }
    }

    override fun onKeyUp(key: Key) {
        if (key.type == KeyType.SHIFT) {
            engine.shiftUp()
            view?.setShift(engine.shift)
        }
    }

    override fun onKeyCommit(key: Key) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        val layerBefore = engine.layer
        run(engine.press(key, before, SystemClock.uptimeMillis()), ic)
        if (engine.layer != layerBefore) rebuildLayout()
        view?.setShift(engine.shift)
    }

    override fun onVariant(text: String) {
        val ic = currentInputConnection ?: return
        run(engine.variant(text), ic)
        view?.setShift(engine.shift)
    }

    override fun onLongPressOpened() {
        pulse(HapticEvent.LONG_PRESS)
    }

    private fun pulse(event: HapticEvent) {
        val st = s
        Haptics.pulseFor(event, st.haptics)?.let { haptics.play(it, st.hapticStrength) }
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        run(engine.pickSuggestion(word, currentWord), ic)
        ic.endBatchEdit()
        feedback(null)
    }

    override fun onCursorDrag(steps: Int) {
        if (currentInputConnection == null) return
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
        pulse(HapticEvent.CURSOR_TICK)
    }

    override fun onSpaceLongPress() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    override fun onProfileTap() {
        val next = store.withActive(store.nextId())
        store = next
        repo.save(next) // listener reloads + re-applies
        applySettings()
    }

    override fun onOpenSettings() {
        val i = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ── effects ─────────────────────────────────────────────────────────
    private fun run(outs: List<Out>, ic: InputConnection) {
        for (o in outs) when (o) {
            is Out.Commit -> ic.commitText(o.text, 1)
            is Out.DeleteBefore -> ic.deleteSurroundingText(o.chars, 0)
            is Out.Action -> ic.performEditorAction(o.action.toEditorInfo())
            Out.Backspace -> backspace(ic)
            Out.EnterKey -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            Out.SwitchIme -> switchToNextIme()
        }
    }

    private fun backspace(ic: InputConnection) {
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(2, 0)
        if (before.isNullOrEmpty()) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        // never split a surrogate pair (emoji)
        val n = if (before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
        ic.deleteSurroundingText(n, 0)
    }

    private fun switchToNextIme() {
        if (Build.VERSION.SDK_INT >= 28) {
            if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        } else {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    private fun feedback(key: Key?) {
        val st = s
        pulse(HapticEvent.KEY)
        if (st.sound) {
            val kind = when {
                key == null -> SoundKind.KEY
                key.type == KeyType.SPACE -> SoundKind.SPACE
                key.type == KeyType.ENTER -> SoundKind.ENTER
                key.style == KeyStyle.MOD -> SoundKind.MOD
                else -> SoundKind.KEY
            }
            sound?.play(kind, st.volume)
        }
    }

    /** Re-reads the text around the cursor: auto-capitalization + suggestions. */
    private fun refreshContext() {
        val ic = currentInputConnection ?: return
        val v = view ?: return
        val info = currentInputEditorInfo
        val caps = info != null && ic.getCursorCapsMode(info.inputType) != 0
        val wasLayer = engine.layer
        engine.updateAutoCap(caps)
        v.setShift(engine.shift)
        if (wasLayer != engine.layer) rebuildLayout()
        val st = s
        if (!st.suggest || noSuggestField) { currentWord = ""; v.setSuggestions(emptyList()); return }
        val before = ic.getTextBeforeCursor(48, 0) ?: ""
        val after = ic.getTextAfterCursor(1, 0) ?: ""
        currentWord = if (after.isNotEmpty() && after[0].isLetter()) "" else Suggest.currentWord(before)
        val sugg = lexicon?.get(st.lang)?.suggest(currentWord, 3) ?: emptyList()
        v.setSuggestions(sugg)
    }

    private companion object {
        const val TAG = "ResystVK"
    }

    private fun ImeAction.toEditorInfo(): Int = when (this) {
        ImeAction.GO -> EditorInfo.IME_ACTION_GO
        ImeAction.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        ImeAction.SEND -> EditorInfo.IME_ACTION_SEND
        ImeAction.NEXT -> EditorInfo.IME_ACTION_NEXT
        ImeAction.DONE -> EditorInfo.IME_ACTION_DONE
        ImeAction.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
        ImeAction.NONE -> EditorInfo.IME_ACTION_NONE
    }

    private fun enterLabel(a: ImeAction, multiLine: Boolean) = if (multiLine) "⏎" else when (a) {
        ImeAction.SEARCH -> "⌕"
        ImeAction.SEND -> "➤"
        ImeAction.GO -> "→"
        ImeAction.NEXT -> "⇥"
        ImeAction.PREVIOUS -> "⇤"
        ImeAction.DONE -> "✓"
        ImeAction.NONE -> "⏎"
    }

    private fun enterDesc(a: ImeAction, multiLine: Boolean) = if (multiLine) "Nueva línea" else when (a) {
        ImeAction.SEARCH -> "Buscar"
        ImeAction.SEND -> "Enviar"
        ImeAction.GO -> "Ir"
        ImeAction.NEXT -> "Siguiente"
        ImeAction.PREVIOUS -> "Anterior"
        ImeAction.DONE -> "Listo"
        ImeAction.NONE -> "Intro"
    }
}
