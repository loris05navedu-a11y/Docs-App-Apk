package com.docssuite.texteditor

/** Texte et styles après un remplacement, avec le nombre d'occurrences traitées. */
data class ReplaceResult(
    val text: String,
    val styles: List<CharStyle>,
    val count: Int,
    /** Position juste après la dernière insertion, pour y replacer le curseur. */
    val caret: Int
)

/**
 * Recherche et remplacement dans le document.
 *
 * Le document garde un style par caractère : toute modification du texte doit
 * découper la liste des styles au même endroit, faute de quoi la mise en forme
 * glisserait d'un caractère à chaque remplacement.
 */
object TextSearch {

    /** Occurrence suivante à partir de [from], en repartant du début si besoin. */
    fun findNext(text: String, query: String, from: Int, matchCase: Boolean): IntRange? {
        if (query.isEmpty() || text.isEmpty()) return null
        val start = from.coerceIn(0, text.length)
        val ignoreCase = !matchCase
        val at = text.indexOf(query, start, ignoreCase)
        if (at >= 0) return at until (at + query.length)
        val wrapped = text.indexOf(query, 0, ignoreCase)
        return if (wrapped >= 0) wrapped until (wrapped + query.length) else null
    }

    /** Occurrence précédente, en repartant de la fin si besoin. */
    fun findPrevious(text: String, query: String, before: Int, matchCase: Boolean): IntRange? {
        if (query.isEmpty() || text.isEmpty()) return null
        val ignoreCase = !matchCase
        val limit = (before - 1).coerceAtLeast(-1)
        val at = if (limit < 0) -1 else text.lastIndexOf(query, limit, ignoreCase)
        if (at >= 0) return at until (at + query.length)
        val wrapped = text.lastIndexOf(query, text.length, ignoreCase)
        return if (wrapped >= 0) wrapped until (wrapped + query.length) else null
    }

    fun count(text: String, query: String, matchCase: Boolean): Int {
        if (query.isEmpty()) return 0
        var total = 0
        var index = 0
        while (true) {
            val at = text.indexOf(query, index, !matchCase)
            if (at < 0) return total
            total++
            index = at + query.length
        }
    }

    /** Remplace une occurrence précise. */
    fun replaceRange(
        text: String,
        styles: List<CharStyle>,
        range: IntRange,
        replacement: String
    ): ReplaceResult {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        val newText = text.substring(0, start) + replacement + text.substring(end)
        val newStyles = spliceStyles(styles, start, end, replacement.length)
        return ReplaceResult(newText, newStyles, 1, start + replacement.length)
    }

    fun replaceAll(
        text: String,
        styles: List<CharStyle>,
        query: String,
        replacement: String,
        matchCase: Boolean
    ): ReplaceResult {
        if (query.isEmpty()) return ReplaceResult(text, styles, 0, 0)

        val builder = StringBuilder()
        val newStyles = ArrayList<CharStyle>(styles.size)
        var index = 0
        var replaced = 0
        var caret = 0

        while (index <= text.length) {
            val at = text.indexOf(query, index, !matchCase)
            if (at < 0) break
            builder.append(text, index, at)
            newStyles.addAll(styleSlice(styles, index, at))

            val carried = styleAt(styles, at)
            builder.append(replacement)
            repeat(replacement.length) { newStyles.add(carried) }

            replaced++
            index = at + query.length
            caret = builder.length
        }
        if (replaced == 0) return ReplaceResult(text, styles, 0, 0)

        builder.append(text, index, text.length)
        newStyles.addAll(styleSlice(styles, index, text.length))
        return ReplaceResult(builder.toString(), newStyles, replaced, caret)
    }

    /**
     * Remplace `[start, end)` par [insertedLength] caractères, en conservant la
     * mise en forme de part et d'autre. Le texte inséré reprend le style du
     * premier caractère remplacé.
     */
    private fun spliceStyles(
        styles: List<CharStyle>,
        start: Int,
        end: Int,
        insertedLength: Int
    ): List<CharStyle> {
        val carried = styleAt(styles, start)
        val out = ArrayList<CharStyle>(styles.size - (end - start) + insertedLength)
        out.addAll(styleSlice(styles, 0, start))
        repeat(insertedLength) { out.add(carried) }
        out.addAll(styleSlice(styles, end, maxOf(end, styles.size)))
        return out
    }

    /**
     * La liste des styles peut être plus courte que le texte : le style par
     * défaut comble le reste plutôt que de lever une erreur.
     */
    private fun styleSlice(styles: List<CharStyle>, from: Int, to: Int): List<CharStyle> {
        if (to <= from) return emptyList()
        return (from until to).map { styles.getOrElse(it) { CharStyle() } }
    }

    private fun styleAt(styles: List<CharStyle>, index: Int): CharStyle =
        styles.getOrNull(index) ?: styles.getOrNull(index - 1) ?: CharStyle()
}
