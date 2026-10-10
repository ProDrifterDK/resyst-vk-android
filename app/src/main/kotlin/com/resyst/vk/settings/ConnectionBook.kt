package com.resyst.vk.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.resyst.vk.core.ConnectionLog

/**
 * r11b (GL4): the one writer of the stored [ConnectionLog]. The updater and the GIF client both
 * log through here, under one lock, so neither overwrites the other's entry. Stored where r10
 * kept it (the updater's prefs, key "connections"), so existing logs keep reading.
 */
object ConnectionBook {
    private const val TAG = "ResystVK"
    private const val PREFS = "resyst_vk_update"
    private const val KEY = "connections"

    /** Settings re-renders the book while visible. Always called on the main thread. */
    val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun read(context: Context): ConnectionLog =
        ConnectionLog.decode(prefs(context).getString(KEY, null))

    /** Logs one request; returns its number (for [amend]). Any thread. */
    @Synchronized
    fun add(context: Context, what: ConnectionLog.What, why: ConnectionLog.Why, outcome: String, media: Int = 0): Int {
        val log = read(context)
        val n = log.nextN
        val next = log.add(ConnectionLog.Entry(System.currentTimeMillis(), what, why, outcome, media))
        prefs(context).edit().putString(KEY, next.encode()).apply()
        // a GIF outcome carries the search words: only debuggable builds print it (the book itself has it)
        Log.i(TAG, "connection log: ${what.id} (${why.id})" + if (!what.gif || com.resyst.vk.BuildConfig.DEBUG) " → $outcome" else "")
        notifyChanged()
        return n
    }

    /** Entry [n] now says [media] files were fetched for it (GL1/GL3). Any thread. */
    @Synchronized
    fun amend(context: Context, n: Int, media: Int) {
        val log = read(context)
        val next = log.amend(n, media)
        if (next == log) return
        prefs(context).edit().putString(KEY, next.encode()).apply()
        Log.i(TAG, "connection log: #$n media=$media")
        notifyChanged()
    }

    private fun notifyChanged() = main.post { for (l in listeners.toList()) l() }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
