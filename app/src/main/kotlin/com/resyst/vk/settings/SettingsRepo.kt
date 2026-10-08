package com.resyst.vk.settings

import android.content.Context
import android.content.SharedPreferences
import com.resyst.vk.core.ProfileCodec
import com.resyst.vk.core.ProfileStore

/** SharedPreferences-backed profile store, shared by the IME service and the settings screen. */
class SettingsRepo(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): ProfileStore = ProfileCodec.decode(prefs.all)

    /**
     * Writes the whole store. Keys the current codec no longer writes (r1–r9 `p.*`, a deleted
     * tema's `t.<id>.*`) are dropped, so a migrated v1 store is rewritten once as v2.
     * Keys other subsystems keep in this file and the codec doesn't own are left alone.
     */
    fun save(store: ProfileStore) {
        val enc = ProfileCodec.encode(store)
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k !in enc && ProfileCodec.owns(k)) e.remove(k)
        for ((k, v) in enc) e.putString(k, v)
        e.apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE = "resyst_vk_profiles"
    }
}
