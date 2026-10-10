package com.resyst.vk.settings

import android.content.Context
import android.content.SharedPreferences
import com.resyst.vk.core.GifOptIn
import java.util.UUID

/**
 * r11b: the GIF search consent + anonymous ID ([GifOptIn]) in its own prefs file
 * (`resyst_vk_gif.xml`, excluded from backup and device transfer, GI2). The keyboard and the
 * settings screen share it; the keyboard listens ([listen]) so «Apagar» in settings stops the
 * GIF tab at once (cancel in flight, drop the feed).
 */
object GifStore {
    const val FILE = "resyst_vk_gif"

    fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(c: Context): GifOptIn {
        val p = prefs(c)
        return GifOptIn.decode { p.getString(it, null) }
    }

    /** The disclosure's «Activar». */
    fun accept(c: Context): GifOptIn = write(c, read(c).accept(::uuid))

    /** «Nuevo ID anónimo». */
    fun newId(c: Context): GifOptIn = write(c, read(c).newId(::uuid))

    /** «Apagar»: the id is deleted, nothing is in flight afterwards (GO1/GO4, GI1). */
    fun turnOff(c: Context): GifOptIn {
        KlipyClient.cancelAll()
        return write(c, GifOptIn())
    }

    private fun write(c: Context, s: GifOptIn): GifOptIn {
        val e = prefs(c).edit().clear() // nothing but the current state survives (an old id never lingers)
        if (s.active) for ((k, v) in s.encode()) if (v != null) e.putString(k, v)
        e.commit() // synchronous: the next read (keyboard or settings) must see it
        return s
    }

    private fun uuid(): String = UUID.randomUUID().toString()
}
