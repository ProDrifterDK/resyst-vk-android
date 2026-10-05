package com.resyst.vk.settings

import android.content.Context
import android.content.SharedPreferences
import com.resyst.vk.core.ProfileCodec
import com.resyst.vk.core.ProfileStore

/** SharedPreferences-backed profile store, shared by the IME service and the settings screen. */
class SettingsRepo(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): ProfileStore = ProfileCodec.decode(prefs.all)

    fun save(store: ProfileStore) {
        val e = prefs.edit()
        for ((k, v) in ProfileCodec.encode(store)) e.putString(k, v)
        e.apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE = "resyst_vk_profiles"
    }
}
