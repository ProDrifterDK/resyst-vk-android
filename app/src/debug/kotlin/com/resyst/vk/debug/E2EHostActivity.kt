package com.resyst.vk.debug

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only host for the E2E script: one focused EditText whose input type comes from
 * the "kind" extra (text | email | number | multiline | search). Editor actions are logged
 * under the tag "ResystE2E" so the script can assert them.
 */
class E2EHostActivity : Activity() {
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
        val field = EditText(this).apply {
            tag = "e2e-field"
            contentDescription = "e2e-field"
            setTextColor(0xFFE9E4D6.toInt())
            setHintTextColor(0xFF8F8A7A.toInt())
            hint = "e2e"
            gravity = Gravity.TOP or Gravity.START
            textSize = 20f
            inputType = when (kind) {
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "number" -> InputType.TYPE_CLASS_NUMBER
                "multiline" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                "search" -> InputType.TYPE_CLASS_TEXT
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            imeOptions = if (kind == "search") EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE
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
    }
}
