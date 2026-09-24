package com.docssuite.core

import java.text.Normalizer

/** Un document trouvé, avec de quoi montrer pourquoi. */
data class SearchHit(
    val meta: DocMeta,
    /** Passage du contenu autour de la première occurrence ; vide si seul le nom correspond. */
    val snippet: String,
    /** Positions des mots cherchés dans [snippet]. */
    val snippetMatches: List<IntRange>,
    /** Positions des mots cherchés dans le nom du document. */
    val nameMatches: List<IntRange>,
    val score: Int
)

/**
 * Recherche plein texte dans les documents. « ecole » trouve « École »,
 * « coeur » trouve « cœur » ; avec plusieurs mots, chacun doit apparaître
 * (dans le nom ou le contenu), dans n'importe quel ordre.
 */
object DocumentSearch {

    /** Texte « replié » (minuscules, sans accents) et, pour chaque caractère, sa position d'origine. */
    class Folded(val text: String, val origin: IntArray) {
        fun toOriginal(range: IntRange): IntRange = origin[range.first]..origin[range.last]
    }

    fun fold(text: String): Folded {
        val out = StringBuilder(text.length)
        val origin = IntArray(text.length * 2 + 1)
        var n = 0
        fun add(c: Char, from: Int) {
            if (n == origin.size) return
            out.append(c)
            origin[n++] = from
        }
        text.forEachIndexed { i, c ->
            when {
                c.code < 128 -> add(c.lowercaseChar(), i)
                c == 'œ' || c == 'Œ' -> { add('o', i); add('e', i) }
                c == 'æ' || c == 'Æ' -> { add('a', i); add('e', i) }
                c == 'ß' -> { add('s', i); add('s', i) }
                c == ' ' || c == ' ' -> add(' ', i)
                c == '’' || c == 'ʼ' -> add('\'', i)
                else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).forEach { d ->
                    if (Character.getType(d) != Character.NON_SPACING_MARK.toInt()) add(d.lowercaseChar(), i)
                }
            }
        }
        return Folded(out.toString(), origin.copyOf(n))
    }

    fun terms(query: String): List<String> =
        fold(query).text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun occurrences(haystack: String, term: String): List<IntRange> {
        val found = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(term, from)
            if (at < 0) return found
            found.add(at until at + term.length)
            from = at + term.length
        }
    }

    /** Documents correspondant à [query], les plus pertinents d'abord. */
    fun search(query: String, documents: List<Pair<DocMeta, String>>): List<SearchHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val phrase = fold(query.trim()).text

        return documents.mapNotNull { (meta, content) ->
            val name = fold(meta.name)
            val body = fold(content)
            var score = 0
            for (term in terms) {
                val inName = name.text.contains(term)
                val count = occurrences(body.text, term).size
                if (!inName && count == 0) return@mapNotNull null
                if (inName) score += 10
                score += minOf(count, 5)
            }
            if (terms.size > 1 && name.text.contains(phrase)) score += 20
            if (terms.size > 1 && body.text.contains(phrase)) score += 5

            val (snippet, snippetMatches) = snippet(content, body, terms)
            SearchHit(
                meta = meta,
                snippet = snippet,
                snippetMatches = snippetMatches,
                nameMatches = terms.flatMap { occurrences(name.text, it) }.map(name::toOriginal).sortedBy { it.first },
                score = score
            )
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.meta.updatedAt })
    }

    /**
     * Un passage d'une ligne autour de la première occurrence, coupé aux
     * mots, avec « … » aux bords s'il est tronqué.
     */
    private fun snippet(content: String, body: Folded, terms: List<String>): Pair<String, List<IntRange>> {
        val first = terms.mapNotNull { term -> body.text.indexOf(term).takeIf { it >= 0 } }.minOrNull()
            ?: return "" to emptyList()
        val center = body.origin[first]
        var start = maxOf(0, center - BEFORE)
        var end = minOf(content.length, center + AFTER)
        if (start > 0) content.indexOf(' ', start).takeIf { it in start until center }?.let { start = it + 1 }
        if (end < content.length) content.lastIndexOf(' ', end).takeIf { it > center }?.let { end = it }

        val raw = content.substring(start, end).replace(Regex("\\s+"), " ").trim()
        val text = (if (start > 0) "…" else "") + raw + (if (end < content.length) "…" else "")
        val folded = fold(text)
        val matches = terms.flatMap { occurrences(folded.text, it) }.map(folded::toOriginal).sortedBy { it.first }
        return text to matches
    }

    private const val BEFORE = 40
    private const val AFTER = 90
}
