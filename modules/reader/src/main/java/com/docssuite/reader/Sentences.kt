package com.docssuite.reader

/**
 * Découpe un texte en phrases à lire une à une. Chaque phrase est une plage
 * du texte d'origine : l'écran la surligne telle quelle. Une phrase ne
 * franchit jamais un retour à la ligne (titres, listes, cellules restent
 * séparés) et les abréviations courantes (M., Mme., p. ex., J. Dupont) ne
 * coupent pas la phrase.
 */
object Sentences {

    /** La synthèse vocale refuse les textes trop longs : au-delà, on coupe à une virgule ou une espace. */
    const val MAX_LENGTH = 600

    private val abbreviations = setOf(
        "m", "mm", "mme", "mmes", "mlle", "mlles", "dr", "pr", "me", "mgr", "st", "ste",
        "cf", "ex", "p", "pp", "vol", "chap", "fig", "no", "n°", "art", "av", "bd", "env",
        "min", "max", "tél", "tel", "réf", "ref", "janv", "févr", "fév", "avr", "juil", "sept", "oct", "nov", "déc",
        "mr", "mrs", "ms", "vs"
    )

    private val closers = setOf('"', '»', '”', '’', '\'', ')', ']')
    private val spaces = setOf(' ', '\u00A0', '\u202F')

    fun split(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            splitLine(text, lineStart, lineEnd, out)
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun splitLine(text: String, from: Int, to: Int, out: MutableList<IntRange>) {
        var start = from
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < to && (text[end] == '.' || text[end] == '!' || text[end] == '?' || text[end] == '…')) end++
                // Guillemets et parenthèses fermants, y compris avec l'espace à la française : « Oui ? »
                while (end < to) {
                    if (text[end] in closers) end++
                    else if (text[end] in spaces && end + 1 < to && text[end + 1] == '»') end += 2
                    else break
                }
                val followedBySpace = end >= to || text[end].isWhitespace()
                if (followedBySpace && !continues(text, start, i, end, to)) {
                    add(text, start, end, out)
                    start = end
                }
                i = end
            } else {
                i++
            }
        }
        add(text, start, to, out)
    }

    /**
     * La phrase continue si la suite commence par une minuscule (« etc. et… »,
     * « Alors… on y va »), ou si le point suit une abréviation ou une initiale
     * (« M. Dupont », « J. Rowling »).
     */
    private fun continues(text: String, sentenceStart: Int, mark: Int, after: Int, lineEnd: Int): Boolean {
        val next = (after until lineEnd).firstOrNull { !text[it].isWhitespace() }?.let { text[it] } ?: return false
        if (next.isLowerCase()) return true
        if (text[mark] != '.' || after != mark + 1) return false
        var wordStart = mark
        while (wordStart > sentenceStart && (text[wordStart - 1].isLetter() || text[wordStart - 1] == '°')) wordStart--
        val word = text.substring(wordStart, mark)
        return word.lowercase() in abbreviations || (word.length == 1 && word[0].isUpperCase())
    }

    /** Ajoute la phrase sans ses espaces de bord, en coupant celles qui dépassent [MAX_LENGTH]. */
    private fun add(text: String, from: Int, to: Int, out: MutableList<IntRange>) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end || (start until end).none { text[it].isLetterOrDigit() }) return
        while (end - start > MAX_LENGTH) {
            val limit = start + MAX_LENGTH
            val cut = listOf(";", ":", ",", " ")
                .map { sep -> text.lastIndexOf(sep, limit - 1).let { if (it > start + MAX_LENGTH / 3) it + 1 else -1 } }
                .firstOrNull { it > 0 } ?: limit
            add(text, start, cut, out)
            start = cut
            while (start < end && text[start].isWhitespace()) start++
        }
        if (start < end) out.add(start until end)
    }
}
