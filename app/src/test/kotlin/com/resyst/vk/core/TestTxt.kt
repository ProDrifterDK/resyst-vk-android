package com.resyst.vk.core

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * r14b: [Txt] for the JVM tests. Reads the real `values/` (Spanish, the default) and `values-en/` resource
 * files from the sources, so a test sees the same words the phone shows. R ids are mapped back to names by
 * reflection on the generated R class.
 */
class TestTxt(override val lang: String) : Txt {
    private val strings: Map<String, String>
    private val plurals: Map<String, Map<String, String>>
    private val locale = Locale.forLanguageTag(lang)

    init {
        val dirs = if (lang == "en") listOf("values", "values-en") else listOf("values")
        val s = LinkedHashMap<String, String>()
        val p = LinkedHashMap<String, Map<String, String>>()
        for (d in dirs) {
            val parsed = Res.parse(File(Res.resDir(), d))
            s.putAll(parsed.strings)
            p.putAll(parsed.plurals)
        }
        strings = s
        plurals = p
    }

    override fun get(id: Int, vararg args: Any): String {
        val name = Res.stringName(id) ?: error("no string with id $id")
        return String.format(locale, strings[name] ?: error("string $name is not in values/"), *args)
    }

    override fun plural(id: Int, n: Int, vararg args: Any): String {
        val name = Res.pluralsName(id) ?: error("no plurals with id $id")
        val forms = plurals[name] ?: error("plurals $name is not in values/")
        val one = n == 1
        val text = (if (one) forms["one"] else null) ?: forms["other"] ?: error("plurals $name has no 'other'")
        return String.format(locale, text, n, *args)
    }

    override fun named(name: String, vararg args: Any): String? =
        strings[name]?.let { String.format(locale, it, *args) }

    companion object {
        val ES = TestTxt("es")
        val EN = TestTxt("en")
    }
}

/** Reading the resource XML the way aapt does: `\'`, `\"`, `\n`, `\uXXXX`, and `%%`-free plain text. */
object Res {
    data class Parsed(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>, val untranslatable: Set<String>)

    fun resDir(): File = File(System.getProperty("vk.resDir") ?: "src/main/res")

    fun parse(dir: File): Parsed {
        val s = LinkedHashMap<String, String>()
        val p = LinkedHashMap<String, Map<String, String>>()
        val a = LinkedHashMap<String, List<String>>()
        val skip = LinkedHashSet<String>()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                if (e.getAttribute("translatable") == "false") skip.add(name)
                when (e.tagName) {
                    "string" -> s[name] = unescape(e.textContent)
                    "plurals" -> {
                        val items = LinkedHashMap<String, String>()
                        val c = e.getElementsByTagName("item")
                        for (j in 0 until c.length) {
                            val it = c.item(j) as Element
                            items[it.getAttribute("quantity")] = unescape(it.textContent)
                        }
                        p[name] = items
                    }
                    "string-array" -> {
                        val c = e.getElementsByTagName("item")
                        a[name] = (0 until c.length).map { unescape(c.item(it).textContent) }
                    }
                }
            }
        }
        return Parsed(s, p, a, skip)
    }

    fun unescape(raw: String): String {
        var t = raw.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (val n = t[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u' -> { sb.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun names(cls: String): Map<Int, String> =
        Class.forName("com.resyst.vk.R\$$cls").fields.associate { (it.get(null) as Int) to it.name }

    private val stringNames by lazy { names("string") }
    private val pluralNames by lazy { names("plurals") }

    fun stringName(id: Int): String? = stringNames[id]
    fun pluralsName(id: Int): String? = pluralNames[id]
}
