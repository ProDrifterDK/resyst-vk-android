package com.resyst.vk.core

/**
 * r14b (TR1): where every user-visible string comes from. The core stays pure Kotlin: it names a
 * resource ([get], [plural]) or an id-derived resource name ([named], e.g. `palette_lab`), and the
 * caller supplies the language: the app reads the device's resources (ime/ResTxt), the JVM tests
 * read `values/` and `values-en/` from the sources (TestTxt). Nothing here caches text: ask when drawing,
 * so a locale change shows at the next draw (TR7).
 */
interface Txt {
    fun get(id: Int, vararg args: Any): String

    /** A count-dependent line (`<plurals>`): `one` for 1, `other` otherwise; [n] is also argument 1. */
    fun plural(id: Int, n: Int, vararg args: Any): String

    /** A string whose name is built from an id kept in the data (`palette_` + `lab`); null when the name is not a resource. */
    fun named(name: String, vararg args: Any): String?

    /** The language the resolved resources are written in: "es" (the default) or "en". Drives the site address and word forms Kotlin must pick. */
    val lang: String
}

/** A resource named by an id kept in the data; a missing name shows the name itself, never a crash in the IME. */
fun Txt.need(name: String, vararg args: Any): String = named(name, *args) ?: name

/** Names that live as resources and are found by the id the data stores (TR3). */
object Names {
    fun palette(t: Txt, id: String): String = t.need("palette_$id")
    fun accent(t: Txt, id: String): String = t.need("accent_$id")
}
