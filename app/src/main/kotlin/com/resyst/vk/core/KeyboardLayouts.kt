package com.resyst.vk.core

/**
 * Touch layouts. The desktop VK uses a 60-column ISO grid; on a phone the same Spanish
 * layout (QWERTY + ñ after l, accents row, AltGr symbols) is re-flowed into 10-unit rows.
 * AltGr characters live on the symbol layers and in long-press variants.
 */
object KeyboardLayouts {
    const val ROW_UNITS = 10f

    private val ACCENT_ROW = listOf("á", "é", "í", "ó", "ú", "ü", "ñ", "¿", "¡", "@")
    private val DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    private fun ch(c: String, lang: Lang, w: Float = 1f, style: KeyStyle = KeyStyle.NORMAL, hint: String? = null): Key {
        val v = Variants.forKey(c, lang)
        val variants = if (hint != null && hint !in v) v + hint else v
        return Key(KeyType.CHAR, c, c, w, hint = hint, variants = variants, style = style)
    }

    private fun gap(w: Float) = Key(KeyType.SPACER, "", width = w)
    private fun shift(w: Float) = Key(KeyType.SHIFT, "⇧", width = w, style = KeyStyle.MOD)
    private fun backspace(w: Float) = Key(KeyType.BACKSPACE, "⌫", width = w, style = KeyStyle.MOD)
    private fun layer(label: String, to: Layer, w: Float) = Key(KeyType.LAYER, label, width = w, target = to, style = KeyStyle.MOD)
    private fun enter(w: Float) = Key(KeyType.ENTER, "⏎", width = w, style = KeyStyle.ACTION)
    private fun space(w: Float, label: String) = Key(KeyType.SPACE, label, " ", w)

    fun rows(layer: Layer, spec: LayoutSpec): List<List<Key>> = when (layer) {
        Layer.LETTERS -> letters(spec)
        Layer.SYMBOLS -> symbols(spec)
        Layer.SYMBOLS2 -> symbols2(spec)
        Layer.NUMPAD -> numpad(spec)
    }

    private fun letters(spec: LayoutSpec): List<List<Key>> {
        val l = spec.lang
        val rows = mutableListOf<List<Key>>()
        when (spec.topRow) {
            TopRow.ACCENTS -> rows += ACCENT_ROW.map { ch(it, l, style = KeyStyle.ACCENT) }
            TopRow.NUMBERS -> rows += DIGITS.map { ch(it, l, style = KeyStyle.NUM) }
            TopRow.NONE -> Unit
        }
        rows += "qwertyuiop".mapIndexed { i, c -> ch(c.toString(), l, hint = DIGITS[i]) }
        rows += if (l == Lang.ES) {
            "asdfghjklñ".map { ch(it.toString(), l) }
        } else {
            listOf(gap(0.5f)) + "asdfghjkl".map { ch(it.toString(), l) } + gap(0.5f)
        }
        rows += listOf(shift(1.5f)) + "zxcvbnm".map { ch(it.toString(), l) } + backspace(1.5f)
        rows += bottomRow(spec, layer("?123", Layer.SYMBOLS, 1.5f), letterRow = true)
        return rows
    }

    private fun symbols(spec: LayoutSpec): List<List<Key>> {
        val l = spec.lang
        return listOf(
            DIGITS.map { ch(it, l, style = KeyStyle.NUM) },
            listOf("@", "#", "€", "_", "&", "-", "+", "(", ")", "/").map { ch(it, l) },
            listOf(layer("=\\<", Layer.SYMBOLS2, 1.5f)) +
                listOf("*", "\"", "'", ":", ";", "!", "?").map { ch(it, l) } + backspace(1.5f),
            bottomRow(spec, layer("ABC", Layer.LETTERS, 1.5f), letterRow = false),
        )
    }

    private fun symbols2(spec: LayoutSpec): List<List<Key>> {
        val l = spec.lang
        return listOf(
            listOf("~", "`", "|", "\\", "{", "}", "[", "]", "<", ">").map { ch(it, l) },
            listOf("^", "°", "=", "%", "§", "¬", "£", "¥", "$", "¢").map { ch(it, l) },
            listOf(layer("?123", Layer.SYMBOLS, 1.5f)) +
                listOf("¿", "¡", "«", "»", "…", "·", "º").map { ch(it, l) } + backspace(1.5f),
            bottomRow(spec, layer("ABC", Layer.LETTERS, 1.5f), letterRow = false),
        )
    }

    private fun numpad(spec: LayoutSpec): List<List<Key>> {
        val l = spec.lang
        val w = 2.5f
        fun n(c: String) = ch(c, l, w, KeyStyle.NUM)
        return listOf(
            listOf(n("1"), n("2"), n("3"), backspace(w)),
            listOf(n("4"), n("5"), n("6"), ch(".", l, w)),
            listOf(n("7"), n("8"), n("9"), enter(w)),
            listOf(layer("ABC", Layer.LETTERS, w), ch("*", l, w), n("0"), ch("#", l, w)),
        )
    }

    private fun bottomRow(spec: LayoutSpec, layerKey: Key, letterRow: Boolean): List<Key> {
        val l = spec.lang
        val left = when {
            letterRow && spec.field == FieldKind.EMAIL -> ch("@", l)
            letterRow && spec.field == FieldKind.URL -> ch("/", l)
            else -> ch(",", l)
        }
        val switchKey = if (spec.showSwitchKey) listOf(Key(KeyType.SWITCH_IME, "🌐", style = KeyStyle.MOD)) else emptyList()
        val fixed = layerKey.width + switchKey.sumOf { it.width.toDouble() }.toFloat() + left.width + 1f + 1.5f
        val spaceLabel = if (l == Lang.ES) "español" else "english"
        return listOf(layerKey) + switchKey + left + space(ROW_UNITS - fixed, spaceLabel) + ch(".", l) + enter(1.5f)
    }
}
