package com.resyst.vk.ime

import android.content.Context
import android.graphics.Typeface
import com.resyst.vk.core.KeyFont

/** DM Sans (variable, bundled under OFL) plus the alternative key fonts. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    fun get(context: Context, font: KeyFont, weight: Int): Typeface = synchronized(cache) {
        cache.getOrPut("${font.name}-$weight") {
            when (font) {
                KeyFont.BRAND -> runCatching {
                    Typeface.Builder(context.assets, "fonts/DMSans-var.ttf")
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                }.getOrNull() ?: Typeface.DEFAULT
                KeyFont.TECH -> Typeface.create(Typeface.MONOSPACE, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
                KeyFont.HUMAN -> Typeface.create("serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }
}
