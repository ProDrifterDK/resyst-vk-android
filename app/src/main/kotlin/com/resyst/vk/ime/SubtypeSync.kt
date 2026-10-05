package com.resyst.vk.ime

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.resyst.vk.core.Lang
import com.resyst.vk.core.Subtypes

/**
 * Platform side of [Subtypes]: finds our IME + its subtypes, enables both languages once,
 * and persists the language both sides last agreed on (`lastSynced`) in a prefs file of
 * its own, so writing it never triggers the profile listener.
 */
class SubtypeSync(private val context: Context) {

    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val state = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val info: InputMethodInfo?
        get() = imm?.inputMethodList?.firstOrNull {
            it.packageName == context.packageName && it.serviceName == ResystImeService::class.java.name
        }

    var lastSynced: Lang?
        get() = state.getString(KEY_LAST, null)?.let { v -> Lang.values().firstOrNull { it.name == v } }
        set(v) { if (v != lastSynced) state.edit().putString(KEY_LAST, v?.name).apply() }

    fun isCurrentIme(): Boolean {
        val id = info?.id ?: return false
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id
    }

    fun langOf(subtype: InputMethodSubtype?): Lang? {
        subtype ?: return null
        return Subtypes.langOf(subtype.languageTag.ifEmpty { null } ?: subtype.locale)
    }

    fun subtypeFor(lang: Lang): InputMethodSubtype? {
        val imi = info ?: return null
        return (0 until imi.subtypeCount).map { imi.getSubtypeAt(it) }.firstOrNull { langOf(it) == lang }
    }

    /** System's current language for our IME, or null when another IME is current. */
    fun systemLang(): Lang? = if (isCurrentIme()) langOf(imm?.currentInputMethodSubtype) else null

    /** Is [lang]'s subtype enabled (explicitly, or implicitly by the system locale)? */
    fun isEnabled(lang: Lang): Boolean {
        val imi = info ?: return false
        val enabled = imm?.getEnabledInputMethodSubtypeList(imi, true) ?: return false
        return enabled.any { langOf(it) == lang }
    }

    /**
     * U1: with no explicit choice Android only lists the subtype matching the system locale.
     * Enable every language once (API 34+); after that the user's own choice in system
     * settings is never overwritten (U5).
     */
    fun enableAllOnce() {
        if (state.getBoolean(KEY_SEEDED, false)) return
        if (Build.VERSION.SDK_INT < 34) return
        val imm = imm ?: return
        val imi = info ?: return
        // Only an enabled IME has a subtype list to write into.
        if (imm.enabledInputMethodList.none { it.id == imi.id }) return
        val explicit = imm.getEnabledInputMethodSubtypeList(imi, false)
        if (explicit.isEmpty()) {
            val hashes = (0 until imi.subtypeCount).map { imi.getSubtypeAt(it).hashCode() }.toIntArray()
            val ok = runCatching { imm.setExplicitlyEnabledInputMethodSubtypes(imi.id, hashes) }
                .onFailure { Log.w(TAG, "enable subtypes failed", it) }
                .isSuccess
            if (!ok) return
            Log.i(TAG, "enabled ${hashes.size} subtypes for ${imi.id}")
        }
        state.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    fun openSubtypeSettings() {
        val id = info?.id ?: return
        imm?.showInputMethodAndSubtypeEnabler(id)
    }

    companion object {
        const val TAG = "ResystVK"
        const val FILE = "resyst_vk_ime_state"
        private const val KEY_LAST = "subtype.lastSynced"
        private const val KEY_SEEDED = "subtype.seeded"
    }
}
