package com.resyst.vk.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.util.Log
import com.resyst.vk.core.KeyFont
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * DM Sans (variable, bundled under OFL) plus the alternative key fonts. r14: Atkinson Hyperlegible Next (LEGIBLE) and
 * Nunito (ROUNDED), variable too, subset to what the keys draw (assets/fonts/README.txt).
 *
 * r14 (PE2): every weight of a bundled font shares ONE copy of its file, memory-mapped from a private copy in
 * no_backup/ (clean, file-backed pages, not Java heap). 0.13.0 built each weight with Typeface.Builder(assets), which
 * decompressed a fresh copy of the font per weight (DM Sans: ~240 KB of native memory × 5 weights). Android 8-9 (no
 * Font.Builder) keep that path; so does any failure to map.
 */
object Fonts {
    private const val TAG = "ResystVK"
    private val cache = HashMap<String, Typeface>()
    private val mapped = HashMap<String, ByteBuffer?>()

    private fun asset(font: KeyFont): String? = when (font) {
        KeyFont.BRAND -> "fonts/DMSans-var.ttf"
        KeyFont.LEGIBLE -> "fonts/AtkinsonHyperlegibleNext-var.ttf"
        KeyFont.ROUNDED -> "fonts/Nunito-var.ttf"
        KeyFont.TECH, KeyFont.HUMAN -> null
    }

    fun get(context: Context, font: KeyFont, weight: Int): Typeface = synchronized(cache) {
        val key = "${font.name}-$weight"
        cache[key]?.let { return it }
        val path = asset(font)
        val tf = when {
            path != null -> variable(context, path, weight)
            font == KeyFont.TECH -> Typeface.create(Typeface.MONOSPACE, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            else -> Typeface.create("serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
        cache[key] = tf
        tf
    }

    private fun variable(context: Context, path: String, weight: Int): Typeface {
        if (Build.VERSION.SDK_INT >= 29) {
            val buf = synchronized(mapped) { mapped.getOrPut(path) { map(context, path) } }
            if (buf != null) {
                val shared = runCatching {
                    val f = Font.Builder(buf).setFontVariationSettings("'wght' $weight").setWeight(weight.coerceIn(1, 1000)).build()
                    Typeface.CustomFallbackBuilder(FontFamily.Builder(f).build()).setSystemFallback("sans-serif").build()
                }.onFailure { Log.w(TAG, "font: shared build failed for $path", it) }.getOrNull()
                if (shared != null) return shared
            }
        }
        return runCatching {
            Typeface.Builder(context.assets, path).setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: Typeface.DEFAULT
    }

    /** The font file copied once into no_backup/fonts/ (named after the app's install time: an update rewrites it), mapped read-only. */
    private fun map(context: Context, path: String): ByteBuffer? = runCatching {
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.noBackupFilesDir, "fonts").apply { mkdirs() }
        val name = path.substringAfterLast('/')
        val file = File(dir, "$stamp-$name")
        if (!file.exists()) {
            dir.listFiles()?.filter { it.name.endsWith("-$name") }?.forEach { it.delete() }
            val tmp = File(dir, "$name.tmp")
            context.assets.open(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) error("rename")
        }
        RandomAccessFile(file, "r").use { raf -> raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()) }
    }.onFailure { Log.w(TAG, "font: map failed for $path", it) }.getOrNull()
}
