package com.resyst.vk.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only (r11c E2E): moves the stored last update attempt INSIDE the running process, so the
 * throttle can be exercised on one IME pid without waiting 12 h. A `run-as` edit of the prefs file
 * is not enough there: SharedPreferences keeps the map in memory and the next commit overwrites
 * the file. The release logic and its intervals are untouched; this only rewrites the stamp.
 *
 *   adb shell am broadcast -n com.resyst.vk.debug/com.resyst.vk.debug.E2EUpdateClock --el by_ms N
 *     → attemptAt -= N            (the last attempt happened N ms earlier)
 *   … --el ahead_ms N             → attemptAt = now + N (the clock was moved back)
 */
class E2EUpdateClock : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = c.getSharedPreferences("resyst_vk_update", Context.MODE_PRIVATE)
        val at = p.getLong("attemptAt", 0L)
        val next = when {
            i.hasExtra("ahead_ms") -> System.currentTimeMillis() + i.getLongExtra("ahead_ms", 0L)
            else -> at - i.getLongExtra("by_ms", 0L)
        }
        p.edit().putLong("attemptAt", next).commit()
        Log.i("ResystE2E", "update clock: attemptAt $at → $next (ok=${p.getBoolean("attemptOk", false)})")
    }
}
