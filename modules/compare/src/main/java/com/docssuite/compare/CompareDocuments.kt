package com.docssuite.compare

import com.docssuite.fileformats.DocBlock
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun

/** Ce qui est arrivé à un paragraphe d'une version à l'autre. */
enum class Kind { SAME, ADDED, REMOVED, CHANGED }

/**
 * Une ligne du rapport. Pour un paragraphe retouché, [words] dit quels mots
 * ont bougé ; ailleurs elle est vide.
 */
data class Row(
    val kind: Kind,
    val before: String = "",
    val after: String = "",
    val words: List<Edit<String>> = emptyList()
)

data class Comparison(val rows: List<Row>) {
    val added: Int get() = rows.count { it.kind == Kind.ADDED }
    val removed: Int get() = rows.count { it.kind == Kind.REMOVED }
    val changed: Int get() = rows.count { it.kind == Kind.CHANGED }
    val same: Int get() = rows.count { it.kind == Kind.SAME }
    val identical: Boolean get() = added == 0 && removed == 0 && changed == 0

    /** « 2 ajouts, 1 suppression, 3 paragraphes retouchés » */
    val summary: String
        get() = if (identical) {
            "Les deux versions sont identiques."
        } else {
            listOfNotNull(
                count(added, "ajout", "ajouts"),
                count(removed, "suppression", "suppressions"),
                count(changed, "paragraphe retouché", "paragraphes retouchés")
            ).joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        }

    private fun count(n: Int, one: String, many: String): String? =
        if (n == 0) null else "$n ${if (n == 1) one else many}"
}

/**
 * Comparaison de deux versions d'un texte : d'abord les paragraphes, pour voir
 * ce qui a été ajouté ou retiré ; puis, dans ceux qui se correspondent, les
 * mots, pour montrer la retouche exacte.
 */
object CompareDocuments {

    /**
     * En dessous de cette part de mots communs, deux paragraphes ne sont plus
     * une retouche l'un de l'autre mais bien un retrait et un ajout distincts.
     */
    private const val SAME_ENOUGH = 0.3

    fun of(before: TextDocument, after: TextDocument): Comparison =
        of(paragraphs(before), paragraphs(after))

    /**
     * Les lignes vides et les espaces de bord sont de la mise en page, pas du
     * contenu : les écarter évite d'annoncer un changement là où le texte est
     * le même.
     */
    fun of(before: List<String>, after: List<String>): Comparison {
        val edits = Diff.of(content(before), content(after))
        val rows = ArrayList<Row>(edits.size)
        var i = 0
        while (i < edits.size) {
            when (edits[i].change) {
                Change.KEPT -> {
                    rows.add(Row(Kind.SAME, edits[i].value, edits[i].value))
                    i++
                }
                Change.REMOVED -> {
                    // Une série de suppressions suivie d'une série d'ajouts :
                    // les paragraphes se répondent souvent deux à deux.
                    val gone = ArrayList<String>()
                    while (i < edits.size && edits[i].change == Change.REMOVED) gone.add(edits[i++].value)
                    val arrived = ArrayList<String>()
                    while (i < edits.size && edits[i].change == Change.ADDED) arrived.add(edits[i++].value)
                    rows.addAll(pair(gone, arrived))
                }
                Change.ADDED -> {
                    rows.add(Row(Kind.ADDED, after = edits[i].value))
                    i++
                }
            }
        }
        return Comparison(rows)
    }

    private fun content(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }

    private fun paragraphs(document: TextDocument): List<String> =
        document.paragraphs.map { it.plainText }

    private fun pair(gone: List<String>, arrived: List<String>): List<Row> {
        val rows = ArrayList<Row>(gone.size + arrived.size)
        var i = 0
        while (i < gone.size && i < arrived.size) {
            val words = Diff.of(words(gone[i]), words(arrived[i]))
            if (similarity(words) >= SAME_ENOUGH) {
                rows.add(Row(Kind.CHANGED, gone[i], arrived[i], words))
            } else {
                rows.add(Row(Kind.REMOVED, before = gone[i]))
                rows.add(Row(Kind.ADDED, after = arrived[i]))
            }
            i++
        }
        for (j in i until gone.size) rows.add(Row(Kind.REMOVED, before = gone[j]))
        for (j in i until arrived.size) rows.add(Row(Kind.ADDED, after = arrived[j]))
        return rows
    }

    internal fun words(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Part de mots restés en place, sur le plus long des deux paragraphes. */
    private fun similarity(words: List<Edit<String>>): Double {
        val kept = words.count { it.change == Change.KEPT }
        val longest = maxOf(
            words.count { it.change != Change.ADDED },
            words.count { it.change != Change.REMOVED }
        )
        return if (longest == 0) 1.0 else kept.toDouble() / longest
    }

    /**
     * Le rapport en document : ajouts surlignés en vert, suppressions barrées
     * en rouge. De quoi le relire plus tard, l'imprimer ou l'envoyer.
     */
    fun report(
        comparison: Comparison,
        beforeName: String,
        afterName: String,
        includeUnchanged: Boolean = false
    ): TextDocument {
        val blocks = ArrayList<DocBlock>()
        blocks.add(heading("Comparaison"))
        blocks.add(plain("Avant : $beforeName"))
        blocks.add(plain("Après : $afterName"))
        blocks.add(plain(comparison.summary))
        blocks.add(TextParagraph())

        comparison.rows.forEach { row ->
            when (row.kind) {
                Kind.SAME -> if (includeUnchanged) blocks.add(plain(row.before))
                Kind.ADDED -> blocks.add(TextParagraph(listOf(added(row.after))))
                Kind.REMOVED -> blocks.add(TextParagraph(listOf(removed(row.before))))
                Kind.CHANGED -> blocks.add(TextParagraph(row.words.map { edit ->
                    when (edit.change) {
                        Change.KEPT -> TextRun(edit.value + " ")
                        Change.ADDED -> added(edit.value + " ")
                        Change.REMOVED -> removed(edit.value + " ")
                    }
                }))
            }
        }
        return TextDocument("Comparaison", blocks)
    }

    private fun heading(text: String) = TextParagraph(listOf(TextRun(text, bold = true, size = 22)), heading = 1)

    private fun plain(text: String) = TextParagraph(listOf(TextRun(text)))

    private fun added(text: String) = TextRun(text, highlight = 0xFFBBF7D0L, color = 0xFF14532DL)

    private fun removed(text: String) = TextRun(text, strike = true, color = 0xFF991B1BL)
}
