package com.resyst.vk.core

/**
 * r10 regional Spanish (F-2, bet 4). The shipped es.txt comes from subtitles and leans
 * peninsular (coche 407 vs carro 2405, vosotros 760, no cachai / fome / pololo). A region is a
 * list of re-rank rules applied when the lexicon loads — curated in the repo, never learned from
 * users. [ES_CL] = Latin American rules + Chilean ones on top; [ES_ES] = the r8 list untouched.
 */
enum class Region(val label: String, val rules: List<String>) {
    ES_CL("Chile", listOf("es-419", "es-CL")),
    ES_419("Latinoamérica", listOf("es-419")),
    ES_ES("España", emptyList()),
}

object Regional {
    /**
     * One rule. [rank] = the 0-based rank the word moves (or is inserted) to; null = demote to
     * the tail of the list (the word stays known: typed exactly it is never "corrected", RG1).
     */
    data class Rule(val word: String, val rank: Int?)

    /**
     * Rule file: one rule per line, `#` comments. `word <rank>` promotes/inserts, `-word`
     * demotes, `word = other` puts word right where `other` was (and `other` moves one down).
     * A promotion never moves a word DOWN: the target is min(its rank in [base], the rule's).
     * Malformed lines are skipped (RG5). Words are lowercased; letters + apostrophes only.
     */
    fun parse(text: String, base: List<String> = emptyList()): List<Rule> {
        val rankOf = HashMap<String, Int>(base.size * 2).also { m -> base.forEachIndexed { i, w -> m.putIfAbsent(w, i) } }
        fun up(w: String, r: Int) = Rule(w, minOf(r, rankOf[w] ?: Int.MAX_VALUE))
        val out = ArrayList<Rule>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("-")) {
                val w = line.drop(1).trim().lowercase()
                if (valid(w)) out += Rule(w, null)
                continue
            }
            val eq = line.split('=').map { it.trim().lowercase() }
            if (eq.size == 2) {
                val (w, other) = eq
                val r = rankOf[other]
                if (valid(w) && r != null) out += up(w, r)
                continue
            }
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 2) continue
            val w = parts[0].lowercase()
            val r = parts[1].toIntOrNull() ?: continue
            if (valid(w) && r >= 0) out += up(w, r)
        }
        return out
    }

    private fun valid(w: String) = w.isNotEmpty() && w.length <= Suggest.MAX_LEN && w.all { it.isLetter() || it == '\'' }

    /**
     * Applies [rules] (later rules win for the same word): moved/inserted words land at their
     * target rank, demoted words go to the tail in rule order, everything else keeps its
     * relative order. No word is lost or duplicated (RG2).
     */
    fun apply(words: List<String>, rules: List<Rule>): List<String> {
        if (rules.isEmpty()) return words
        val last = LinkedHashMap<String, Int?>()
        for (r in rules) { last.remove(r.word); last[r.word] = r.rank }
        val moved = last.keys
        val rest = words.filter { it !in moved }.distinct()
        val placed = last.entries.filter { it.value != null }.sortedBy { it.value!! }
        val out = ArrayList<String>(rest.size + last.size)
        var i = 0
        for ((w, rank) in placed) {
            while (out.size < rank!! && i < rest.size) out += rest[i++]
            out += w
        }
        while (i < rest.size) out += rest[i++]
        val known = words.toHashSet()
        for ((w, rank) in last) if (rank == null && w in known) out += w
        return out
    }

    /** [words] re-ranked by every rule file of [region], read through [read] (missing file = no rules). */
    fun forRegion(words: List<String>, region: Region, read: (String) -> String?): List<String> {
        var list = words
        for (name in region.rules) {
            val text = read(name) ?: continue
            list = apply(list, parse(text, list))
        }
        return list
    }
}
