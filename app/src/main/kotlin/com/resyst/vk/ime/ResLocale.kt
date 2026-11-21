package com.resyst.vk.ime

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * r14b: the keyboard's own words follow Android's per-app language too (Android 13+, Settings › Languages › App
 * languages). Activities get the chosen language from the system; a service does not, so the IME builds its views and
 * its [ResTxt] on a context in that language. With no app language chosen (or below Android 13) this is the context
 * itself: nothing changes for the common case.
 */
object ResLocale {
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT < 33) return context
        val chosen = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales }.getOrNull()
        if (chosen == null || chosen.isEmpty) return context
        val now = context.resources.configuration
        if (now.locales == chosen) return context
        return context.createConfigurationContext(Configuration(now).apply { setLocales(chosen) })
    }
}
