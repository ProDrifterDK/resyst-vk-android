package com.resyst.vk.ime

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.resyst.vk.core.WhatsNew

/**
 * r13c (WN1): the one fact the "Novedades" notice keeps, the last versionName seen, in its own
 * private prefs file. Local only: nothing here, or anywhere near it, makes a request.
 */
object WhatsNewStore {
    private const val TAG = "ResystVK"
    private const val PREFS = "resyst_vk_news"
    private const val KEY = "seen"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Run once per process, at the first field: the running versionName when its notice is still to
     * be shown, else null. A fresh install (or a downgrade) writes its baseline silently.
     */
    fun pending(context: Context): String? {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val current = pi.versionName ?: return null
        val p = prefs(context)
        val seen = p.getString(KEY, null)
        return when (val s = WhatsNew.start(seen, current, WhatsNew.updatedInPlace(pi.firstInstallTime, pi.lastUpdateTime))) {
            is WhatsNew.Start.Pending -> {
                Log.i(TAG, "whats new: pending $current (seen=${seen ?: "none"})")
                current
            }
            is WhatsNew.Start.Quiet -> {
                s.write?.let { p.edit().putString(KEY, it).apply() }
                Log.i(TAG, "whats new: quiet ($current, seen=${seen ?: "none"}, baseline=${s.write != null})")
                null
            }
        }
    }

    /** Tapped, dismissed or typed past: this version's notice never shows again. */
    fun markSeen(context: Context, version: String) {
        prefs(context).edit().putString(KEY, version).apply()
    }
}
