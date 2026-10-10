package com.resyst.vk.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import java.io.File

/**
 * Debug-only host for the E2E script: one focused EditText whose input type comes from
 * the "kind" extra (text, email, password, number, multiline, search, rich, social, handle,
 * incognito). Editor actions
 * are logged under the tag "ResystE2E" so the script can assert them.
 *
 * Clipboard (r6): while this activity has focus, `adb shell am broadcast -a
 * com.resyst.vk.debug.SET_CLIP --es text …` (optional `--ez sensitive true`, `--ez image true`) puts a clip
 * on the system clipboard exactly like a user's "Copiar" in an app would. A "rich" field
 * declares support for any image type (commitContent) and logs every image it receives.
 */
class E2EHostActivity : Activity() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = setClip(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind") ?: "text"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            setBackgroundColor(0xFF08080F.toInt())
        }
        val title = TextView(this).apply {
            text = "✦ E2E host · $kind"
            setTextColor(0xFFC9A84C.toInt())
            textSize = 16f
        }
        val field = (if (kind == "rich") RichField(this) else EditText(this)).apply {
            tag = "e2e-field"
            contentDescription = "e2e-field"
            setTextColor(0xFFE9E4D6.toInt())
            setHintTextColor(0xFF8F8A7A.toInt())
            hint = "e2e"
            gravity = Gravity.TOP or Gravity.START
            textSize = 20f
            inputType = when (kind) {
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                "number" -> InputType.TYPE_CLASS_NUMBER
                "multiline" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                "search" -> InputType.TYPE_CLASS_TEXT
                // r8: a social-app composer (Instagram-style): prose, but opts out of suggestions
                // because the app draws its own @mention / #hashtag dropdown
                "social" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                // a username / handle box with the same opt-out: must stay uncorrected
                "handle" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT // explicit: without it the framework infers NO_SUGGESTIONS (0x4000) and the IME disables autocorrect
            }
            imeOptions = when (kind) {
                "search" -> EditorInfo.IME_ACTION_SEARCH
                // r11: an incognito composer (browser private tab, IME_FLAG_NO_PERSONALIZED_LEARNING)
                "incognito" -> EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                else -> EditorInfo.IME_ACTION_DONE
            }
            setOnEditorActionListener { _, actionId, _ ->
                Log.i("ResystE2E", "editorAction=$actionId text=${text}")
                true
            }
        }
        root.addView(title)
        root.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 360))
        setContentView(root)
        field.requestFocus()
        field.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        if (intent.hasExtra("text") || intent.getBooleanExtra("image", false)) setClip(intent)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(ACTION_SET_CLIP)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    private fun setClip(i: Intent) {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = if (i.getBooleanExtra("image", false)) {
            val dir = File(cacheDir, "clip").apply { mkdirs() }
            val f = File(dir, "e2e-source.png")
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0xC9, 0xA8, 0x4C)) }
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", f)
            ClipData.newUri(contentResolver, "e2e image", uri)
        } else {
            ClipData.newPlainText("e2e", i.getStringExtra("text") ?: "")
        }
        if (i.getBooleanExtra("sensitive", false)) {
            clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
        Log.i("ResystE2E", "setClip mimes=${(0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }}")
    }

    /** A field that takes images like a messenger's compose box (commitContent). */
    private class RichField(c: Context) : EditText(c) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val ic = super.onCreateInputConnection(outAttrs) ?: return null
            EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf("image/*"))
            return InputConnectionCompat.createWrapper(ic, outAttrs) { info, flags, _ ->
                val desc: ClipDescription = info.description
                val ok = runCatching {
                    if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) info.requestPermission()
                    context.contentResolver.openInputStream(info.contentUri)?.use { it.readBytes().size } ?: -1
                }.getOrElse { Log.w("ResystE2E", "commitContent read failed", it); -1 }
                Log.i("ResystE2E", "commitContent mime=${desc.getMimeType(0)} bytes=$ok uri=${info.contentUri}")
                if (ok > 0) append("[imagen $ok B]")
                ok > 0
            }
        }
    }

    companion object {
        const val ACTION_SET_CLIP = "com.resyst.vk.debug.SET_CLIP"
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
