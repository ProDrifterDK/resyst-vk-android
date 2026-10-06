package com.resyst.vk.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.resyst.vk.core.Bar
import com.resyst.vk.core.ClipOffer
import com.resyst.vk.core.ClipRules
import com.resyst.vk.core.ClipSnapshot
import com.resyst.vk.core.ClipboardHistory
import com.resyst.vk.core.Corrector
import com.resyst.vk.core.FieldInfo
import com.resyst.vk.core.FieldKind
import com.resyst.vk.core.FieldPolicy
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
import com.resyst.vk.core.Learner
import com.resyst.vk.core.Out
import com.resyst.vk.core.Palette
import com.resyst.vk.core.ProfileStore
import com.resyst.vk.core.SoundKind
import com.resyst.vk.core.Subtypes
import com.resyst.vk.core.Suggest
import com.resyst.vk.core.ValueMemory
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
    private var policy = FieldPolicy(FieldKind.TEXT, suggestions = false, incognito = false)
    private val fieldKind get() = policy.kind
    private val noSuggestField get() = !policy.suggestions
    /** Learns finished words; the gate is re-checked on every edit (X1–X3). */
    private val learner = Learner { personalWords() }

    /** The learned model, or null whenever this field / these settings may not use it. */
    private fun personalWords() = PersonalStore.words?.takeIf { policy.personalWords(s) }
    private fun personalValues() = PersonalStore.values?.takeIf { policy.personalValues(s) }
    /** Remembers this field's final value once (action key, then field exit). */
    private var valueSession = ValueMemory.Session(null, FieldKind.TEXT)
    /** The bar currently offers whole field values (a pick replaces the typed chunk). */
    private var barShowsValues = false
    private lateinit var haptics: HapticPlayer
    private lateinit var subtypes: SubtypeSync

    // ── clipboard (r6) ──────────────────────────────────────────────────
    private var clipboard: ClipboardManager? = null
    /** The chip currently shown, null = none. */
    private var offer: ClipOffer? = null
    /** Stamp of the clip pasted / dismissed from the chip: it doesn't come back. */
    private var consumedStamp = 0L
    /** Stamp of a clip copied while a secret field was focused: its chip label is masked. */
    private var secretStamp = 0L
    /** The user typed in this field: the chip steps aside until the next field. */
    private var typedSinceStart = false
    private val clipIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val clipStoreListener: () -> Unit = { view?.updateClipboard(historyItems(), System.currentTimeMillis()) }

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepo(this)
        store = repo.load()
        repo.prefs.registerOnSharedPreferenceChangeListener(this)
        sound = KeySoundPlayer(this)
        lexicon = Lexicon(this)
        PersonalStore.init(this)
        haptics = HapticPlayer(this)
        subtypes = SubtypeSync(this)
        subtypes.enableAllOnce()
        // cold start: the first field may open before the history is read from disk
        ClipStore.init(this) { if (isInputViewShown) { captureClip(fromListener = false); refreshClip() } }
        ClipStore.listeners += clipStoreListener
        // The default IME may read the clipboard and is told about every new primary clip while
        // its process lives (ClipboardService.isDefaultIme), with no "pasted" toast.
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        repo.prefs.unregisterOnSharedPreferenceChangeListener(this)
        clipboard?.removePrimaryClipChangedListener(clipListener)
        ClipStore.listeners -= clipStoreListener
        ClipStore.flush()
        PersonalStore.flush()
        sound?.release()
        sound = null
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        store = repo.load()
        syncSubtype(systemSubtype = null)
        applySettings()
        if (!ClipRules.mayShowHistory(policy, clipS)) view?.hideClipboard()
        refreshClip()
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
        hideSystemImeSwitcher()
        applySettings()
        return v
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // ── the system keyboard-switch globe (r7, see core/ImeSwitcher) ─────
    /** API 36+: the framework asks for our own switcher once its IME nav bar is hidden. */
    private var customSwitcherRequested = false

    /**
     * Hide the IME navigation bar (back chevron + globe) the system draws inside this window on
     * gesture navigation; long-press space is the switcher. API 35+ honors it; older versions
     * ignore it and KeyboardView keeps the 88 px floor (ImeSwitcher.systemGlobeHidden = false).
     */
    private fun hideSystemImeSwitcher() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching { window?.window?.decorView?.windowInsetsController?.hide(WindowInsets.Type.captionBar()) }
            .onFailure { Log.w(TAG, "captionBar hide failed", it) }
    }

    override fun onCustomImeSwitcherButtonRequestedVisible(visible: Boolean) {
        Log.i(TAG, "custom IME switcher requested visible=$visible")
        customSwitcherRequested = visible
        view?.setSwitchAvailable(visible || canSwitch())
    }

    @Suppress("DEPRECATION")
    private fun canSwitch(): Boolean = runCatching { shouldOfferSwitchingToNextInputMethod() }.getOrDefault(false)

    override fun onWindowShown() {
        super.onWindowShown()
        hideSystemImeSwitcher()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        policy = FieldPolicy.of(info.inputType, info.imeOptions)
        learner.reset()
        view?.hideClipboard()
        if (!restarting) typedSinceStart = false
        if (!restarting) valueSession = ValueMemory.Session(PersonalStore.values, fieldKind)
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
        captureClip(fromListener = false) // a copy made while this process wasn't running
        refreshClip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        rememberValue()
        view?.hideClipboard()
        ClipStore.flush()
        super.onFinishInputView(finishingInput)
        view?.reset()
        currentWord = ""
        learner.reset()
        PersonalStore.flush()
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
        val lang = st.lang
        engine.corrector = if (st.suggest && st.spaceCorrects && !noSuggestField) {
            Corrector { word, sentenceStart -> Bar.correction(word, sentenceStart, lexicon?.get(lang), personalWords(), lang) }
        } else null
        v.setStyle(st, Palette.of(st.theme, st.accent))
        v.setProfile(store.activeProfile.icon, store.activeProfile.name)
        sound?.configure(st.sound, st.soundPack)
        if (st.suggest) lexicon?.warm(st.lang)
        rebuildLayout()
    }

    private fun rebuildLayout() {
        val v = view ?: return
        val st = s
        v.setSwitchAvailable(customSwitcherRequested || canSwitch())
        val spec = LayoutSpec(st.lang, st.effectiveTopRow, fieldKind)
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
        val before = ic.getTextBeforeCursor(WINDOW, 0) ?: ""
        val after = if (key.type == KeyType.SPACE) ic.getTextAfterCursor(1, 0) ?: "" else ""
        val layerBefore = engine.layer
        val outs = engine.press(key, before, SystemClock.uptimeMillis(), after)
        if (key.type == KeyType.CHAR && offer != null) { typedSinceStart = true; refreshClip() }
        // delete + commit (correction, undo, double-space) must land as one edit
        if (outs.size > 1) ic.beginBatchEdit()
        run(outs, ic)
        if (outs.size > 1) ic.endBatchEdit()
        learn(before, outs, if (key.type == KeyType.BACKSPACE) Learner.Edit.BACKSPACE else Learner.Edit.KEY)
        if (outs.any { it is Out.Action || it == Out.EnterKey }) rememberValue()
        if (engine.layer != layerBefore) rebuildLayout()
        view?.setShift(engine.shift)
    }

    override fun onVariant(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(WINDOW, 0) ?: ""
        val outs = engine.variant(text)
        run(outs, ic)
        learn(before, outs, Learner.Edit.KEY)
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
        if (barShowsValues) {
            val outs = ValueMemory.pick(ic.getTextBeforeCursor(ValueMemory.MAX_LEN, 0) ?: "", word)
            ic.beginBatchEdit()
            run(outs, ic)
            ic.endBatchEdit()
            feedback(null)
            return
        }
        val before = ic.getTextBeforeCursor(WINDOW, 0) ?: ""
        val outs = engine.pickSuggestion(word, currentWord)
        ic.beginBatchEdit()
        run(outs, ic)
        ic.endBatchEdit()
        learn(before, outs, Learner.Edit.PICK)
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

    // ── clipboard ───────────────────────────────────────────────────────
    private val clipS get() = store.clip

    private fun historyItems(): List<ClipboardHistory.Entry> = ClipStore.history?.items() ?: emptyList()

    private fun onClipChanged() {
        captureClip(fromListener = true)
        refreshClip()
    }

    /**
     * Records the primary clip into the history when the gate allows (W1–W4). A copy refused by
     * the gate is remembered by its stamp, so a later re-read never captures it. In a secret
     * field only the clip's description (its stamp) is read, never its content.
     */
    private fun captureClip(fromListener: Boolean) {
        val cm = clipboard ?: return
        val h = ClipStore.history
        if (h == null) { // not loaded yet: only note a secret copy, the load callback re-runs this
            if (fromListener && isInputViewShown && policy.secret) secretStamp = runCatching { cm.primaryClipDescription?.timestamp }.getOrNull() ?: 0L
            return
        }
        val now = System.currentTimeMillis()
        val p = if (fromListener && !isInputViewShown) null else policy
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        val stamp = desc.timestamp
        val sensitive = isSensitive(desc)
        if (fromListener && p?.secret == true) secretStamp = stamp
        var changed = clipS.purgeHour && h.purge(now, ClipboardHistory.PURGE_MS)
        if (!ClipRules.mayCapture(p, clipS, sensitive)) {
            // focus-time re-read in a closed field: leave the clip for an ordinary field later
            if (fromListener || sensitive || !clipS.history) h.ignore(stamp)
            if (changed) ClipStore.changed()
            return
        }
        val text = clipText(runCatching { cm.primaryClip }.getOrNull())
        if (text != null) {
            val r = h.capture(text, now, stamp)
            if (r == ClipboardHistory.Capture.ADDED || r == ClipboardHistory.Capture.BUMPED) changed = true
            if (r == ClipboardHistory.Capture.TOO_LARGE) h.ignore(stamp)
            Log.i(TAG, "clipboard: capture ${text.length} chars → $r")
        } else h.ignore(stamp)
        if (changed) ClipStore.changed()
    }

    /** The clip's first text item, or null (no text, or larger than any limit we keep). */
    private fun clipText(clip: ClipData?): String? {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val cs = item.text ?: return null
        if (cs.length > ClipboardHistory.MAX_BYTES) return null
        return cs.toString()
    }

    private fun isSensitive(desc: ClipDescription): Boolean =
        desc.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    /** The chip and the history button for the current field (never read in a secret field). */
    private fun refreshClip() {
        val v = view ?: return
        v.setClipButton(ClipRules.mayShowHistory(policy, clipS))
        val info = currentInputEditorInfo
        val o = if (policy.secret || typedSinceStart || info == null) null else {
            val snap = snapshot()?.let { if (it.stamp != 0L && it.stamp == secretStamp) it.copy(sensitive = true) else it }
            ClipRules.offer(snap, policy, EditorInfoCompat.getContentMimeTypes(info).toList(), System.currentTimeMillis(), consumedStamp)
        }
        offer = o
        when (o) {
            is ClipOffer.Text -> v.setPasteOffer(o.label, image = false)
            is ClipOffer.Image -> v.setPasteOffer("Pegar imagen", image = true)
            null -> v.setPasteOffer(null, image = false)
        }
    }

    private fun snapshot(): ClipSnapshot? {
        val cm = clipboard ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        val desc = clip.description
        val mimes = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }
        val uri = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val cs = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        val text = cs?.takeIf { it.length <= ClipboardHistory.MAX_BYTES }?.toString()
            ?: cs?.let { "" } // too large: present but not offered
        return ClipSnapshot(text?.ifEmpty { null }, mimes, uri != null, desc.timestamp, isSensitive(desc))
    }

    override fun onPasteOffer() {
        val ic = currentInputConnection ?: return
        when (val o = offer) {
            is ClipOffer.Text -> {
                ic.commitText(o.text, 1)
                consumedStamp = o.stamp
                feedback(null)
            }
            is ClipOffer.Image -> {
                consumedStamp = o.stamp
                feedback(null)
                pasteImage(o)
            }
            null -> return
        }
        learner.reset()
        refreshClip()
    }

    /**
     * Rich paste via commitContent. The image is copied into our own cache first and offered
     * through our FileProvider with a read grant: a URI owned by the copying app can't always be
     * re-granted by the IME. Capped at [IMAGE_MAX_BYTES]; the field may still refuse it.
     */
    private fun pasteImage(o: ClipOffer.Image) {
        val info = currentInputEditorInfo ?: return
        val src: Uri = runCatching { clipboard?.primaryClip?.getItemAt(0)?.uri }.getOrNull() ?: return
        val token = currentInputConnection
        clipIo.execute {
            val file = runCatching { copyImage(src, o.mime) }.onFailure { Log.w(TAG, "clipboard: image copy failed", it) }.getOrNull()
            android.os.Handler(mainLooper).post {
                val ic = currentInputConnection
                if (ic == null || ic !== token) return@post // the field changed meanwhile
                val uri = file?.let { FileProvider.getUriForFile(this, "$packageName.updates", it) } ?: src
                val content = InputContentInfoCompat(uri, ClipDescription("Resyst VK", arrayOf(o.mime)), null)
                val ok = runCatching {
                    InputConnectionCompat.commitContent(ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
                }.getOrDefault(false)
                Log.i(TAG, "clipboard: commitContent ${o.mime} → $ok")
                if (!ok) Toast.makeText(this, "El campo no acepta imágenes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyImage(src: Uri, mime: String): java.io.File? {
        val dir = java.io.File(cacheDir, CLIP_DIR).apply { mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("paste.") }?.forEach { it.delete() } // only the last paste is kept
        val ext = mime.substringAfter('/').filter { it.isLetterOrDigit() }.take(5).ifEmpty { "img" }
        val f = java.io.File(dir, "paste.$ext")
        contentResolver.openInputStream(src)?.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > IMAGE_MAX_BYTES) { f.delete(); return null }
                    out.write(buf, 0, n)
                }
            }
        } ?: return null
        return f
    }

    override fun onClipboardButton() {
        if (!ClipRules.mayShowHistory(policy, clipS)) return // W5
        val h = ClipStore.history
        val now = System.currentTimeMillis()
        if (h != null && clipS.purgeHour && h.purge(now, ClipboardHistory.PURGE_MS)) ClipStore.changed()
        view?.showClipboard(historyItems(), now)
    }

    override fun onClipboardPanel(act: com.resyst.vk.ime.ClipboardPanel.Act, id: Long) {
        val v = view ?: return
        val h = ClipStore.history
        when (act) {
            ClipboardPanel.Act.CLOSE -> v.hideClipboard()
            ClipboardPanel.Act.ROW, ClipboardPanel.Act.PASTE -> {
                if (!ClipRules.mayShowHistory(policy, clipS)) { v.hideClipboard(); return }
                val e = h?.get(id) ?: return
                v.hideClipboard()
                currentInputConnection?.commitText(e.text, 1)
                learner.reset()
                feedback(null)
            }
            ClipboardPanel.Act.PIN -> {
                val e = h?.get(id) ?: return
                if (h.setPinned(id, !e.pinned)) ClipStore.changed()
                else Toast.makeText(this, "Puedes fijar hasta ${ClipboardHistory.MAX_PINS}", Toast.LENGTH_SHORT).show()
            }
            ClipboardPanel.Act.DELETE -> if (h?.delete(id) == true) ClipStore.changed()
            ClipboardPanel.Act.CLEAR_YES -> ClipStore.clear(this)
            else -> Unit
        }
    }

    override fun onOpenSettings() {
        val i = Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    /** Feeds the edit just applied to the personal model (gated inside [learner]). */
    private fun learn(before: CharSequence, outs: List<Out>, kind: Learner.Edit) {
        val b = before.toString()
        if (learner.afterEdit(s.lang, b, outs, windowFull = b.length >= WINDOW, kind = kind)) PersonalStore.changed()
    }

    /** The field's whole text into the value memory (email fields, gate open — F1/X1). */
    private fun rememberValue() {
        if (personalValues() == null) return
        val ic = currentInputConnection ?: return
        val n = ValueMemory.MAX_LEN + 1
        val text = "${ic.getTextBeforeCursor(n, 0) ?: ""}${ic.getTextAfterCursor(n, 0) ?: ""}"
        if (valueSession.commit(text)) PersonalStore.changed()
    }

    // ── effects ─────────────────────────────────────────────────────────
    private fun run(outs: List<Out>, ic: InputConnection) {
        for (o in outs) when (o) {
            is Out.Commit -> ic.commitText(o.text, 1)
            is Out.DeleteBefore -> ic.deleteSurroundingText(o.chars, 0)
            is Out.Action -> ic.performEditorAction(o.action.toEditorInfo())
            Out.Backspace -> backspace(ic)
            Out.EnterKey -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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
        val values = personalValues()
        barShowsValues = values != null
        if (values != null) {
            currentWord = ""
            val before = ic.getTextBeforeCursor(ValueMemory.MAX_LEN, 0) ?: ""
            val after = ic.getTextAfterCursor(1, 0) ?: ""
            v.setSuggestions(ValueMemory.bar(before, after, values, fieldKind))
            return
        }
        if (!st.suggest || noSuggestField) { currentWord = ""; v.setSuggestions(emptyList()); return }
        val before = ic.getTextBeforeCursor(WINDOW, 0) ?: ""
        val after = ic.getTextAfterCursor(1, 0) ?: ""
        currentWord = if (after.isNotEmpty() && after[0].isLetter()) "" else Suggest.currentWord(before)
        val sugg = Bar.words(before, after, before.length >= WINDOW, st.lang, lexicon?.get(st.lang), personalWords(), engine.shift)
        v.setSuggestions(sugg)
    }

    private companion object {
        const val TAG = "ResystVK"
        /** Chars read before the cursor; a full read may start mid-word (see Tokens). */
        const val WINDOW = 64
        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); password managers set it on older APIs too. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        const val CLIP_DIR = "clip"
        const val IMAGE_MAX_BYTES = 10L * 1024 * 1024
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
