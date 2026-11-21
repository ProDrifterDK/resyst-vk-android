package com.resyst.vk.ime

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import com.resyst.vk.core.KbSettings
import com.resyst.vk.core.Palette
import com.resyst.vk.core.SystemColors
import com.resyst.vk.core.Themes

/**
 * r14: what the system says about how things should look — its dark mode (FS2), high contrast (LG5) and the
 * wallpaper's Material You palette (LG3). Read, never written; each read is cheap enough for every settings apply.
 */
object SystemLook {
    fun dark(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Android's "High contrast text" (Accessibility) or, on 14+, the "Color contrast" level set to high. A key the
     * platform does not let us read counts as off.
     */
    fun highContrast(c: Context): Boolean {
        val text = runCatching { Settings.Secure.getInt(c.contentResolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false)
        val level = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { (c.getSystemService(UiModeManager::class.java)?.contrast ?: 0f) >= 0.5f }.getOrDefault(false)
        } else false
        return text || level
    }

    private val A1 = intArrayOf(
        android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50, android.R.color.system_accent1_100,
        android.R.color.system_accent1_200, android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500,
        android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
        android.R.color.system_accent1_1000,
    )
    private val N1 = intArrayOf(
        android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_100,
        android.R.color.system_neutral1_200, android.R.color.system_neutral1_300, android.R.color.system_neutral1_400, android.R.color.system_neutral1_500,
        android.R.color.system_neutral1_600, android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
        android.R.color.system_neutral1_1000,
    )
    private val N2 = intArrayOf(
        android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50, android.R.color.system_neutral2_100,
        android.R.color.system_neutral2_200, android.R.color.system_neutral2_300, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500,
        android.R.color.system_neutral2_600, android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_1000,
    )

    /** The wallpaper's tonal palettes on Android 12+, null before (the "Tu fondo" stand-ins are drawn then). */
    fun colors(c: Context): SystemColors? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            SystemColors(A1.map { c.getColor(it) }.toIntArray(), N1.map { c.getColor(it) }.toIntArray(), N2.map { c.getColor(it) }.toIntArray())
        }.getOrNull()
    }

    /** The palette [s] is drawn with on this phone right now (system colours read only for "Tu fondo"). */
    fun palette(c: Context, s: KbSettings): Palette =
        Palette.of(s, highContrast(c), if (s.theme in Themes.DYNAMIC) colors(c) else null)
}
