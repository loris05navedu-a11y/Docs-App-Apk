package com.docssuite.mailmerge

import com.docssuite.fileformats.DocBlock
import com.docssuite.fileformats.TableRow
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextRun
import com.docssuite.fileformats.TextTable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Publipostage : un modèle de courrier, un tableau de destinataires, autant de
 * courriers personnalisés.
 *
 * Le remplacement travaille sur le texte entier du paragraphe, pas sur chaque
 * fragment de mise en forme : un `{{Nom}}` dont l'éditeur a coupé les accolades
 * en deux morceaux (ce qui arrive dès qu'on repasse dessus en gras) est quand
 * même reconnu. La valeur insérée reprend la mise en forme du début du champ.
 */
object MailMerge {

    /** Un champ sur plusieurs lignes coupe le paragraphe : utile pour une adresse. */
    fun letter(
        model: TextDocument,
        record: Record,
        options: MergeOptions = MergeOptions(),
        index: Int = 0
    ): MergedLetter {
        val resolve = resolver(record, options.today)
        val blocks = model.blocks.flatMap { block -> merge(block, resolve, options.dropEmptied) }
        val title = fill(model.title, resolve)
        return MergedLetter(
            model.copy(title = title, blocks = blocks),
            name(model.title, record, options, index)
        )
    }

    fun run(
        model: TextDocument,
        recipients: Recipients,
        options: MergeOptions = MergeOptions()
    ): List<MergedLetter> = recipients.records.mapIndexed { i, record -> letter(model, record, options, i) }

    /**
     * Tous les courriers dans un seul document, séparés par un filet : pratique
     * pour relire ou imprimer d'un coup.
     */
    fun combine(letters: List<MergedLetter>, title: String): TextDocument {
        val blocks = ArrayList<DocBlock>()
        letters.forEachIndexed { i, letter ->
            if (i > 0) blocks.add(separator)
            blocks.addAll(letter.document.blocks)
        }
        return TextDocument(title, blocks, letters.firstOrNull()?.document?.lineSpacing ?: 100)
    }

    /** Remplace les champs dans un texte simple, sans mise en forme. */
    fun fill(text: String, resolve: (String) -> String?): String =
        fieldPattern.replace(text) { match ->
            resolve(match.groupValues[1].trim())?.let(::oneLine) ?: match.value
        }

    private fun resolver(record: Record, today: Date): (String) -> String? = { field ->
        record.value(field) ?: builtIn(field, today)
    }

    /** Les champs que les données n'ont pas à fournir. */
    private fun builtIn(field: String, today: Date): String? = when (foldKey(field)) {
        "date" -> SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(today)
        else -> null
    }

    private fun merge(block: DocBlock, resolve: (String) -> String?, dropEmptied: Boolean): List<DocBlock> =
        when (block) {
            is TextParagraph -> {
                val merged = apply(block, resolve)
                // Une ligne qui ne portait qu'un champ resté vide disparaît ;
                // une ligne laissée vide exprès dans le modèle reste.
                if (dropEmptied && block.plainText.isNotBlank()) {
                    merged.filter { it.plainText.isNotBlank() }
                } else {
                    merged
                }
            }
            is TextTable -> listOf(
                block.copy(
                    rows = block.rows.map { row ->
                        TableRow(
                            row.cells.map { cell ->
                                // Une cellule garde toujours au moins un paragraphe,
                                // sinon la grille se décalerait.
                                val paragraphs = cell.paragraphs.flatMap { apply(it, resolve) }
                                cell.copy(paragraphs = paragraphs.ifEmpty { listOf(TextParagraph()) })
                            }
                        )
                    }
                )
            )
        }

    private fun apply(paragraph: TextParagraph, resolve: (String) -> String?): List<TextParagraph> {
        val runs = paragraph.runs
        if (runs.isEmpty()) return listOf(paragraph)

        val text = StringBuilder()
        val owner = ArrayList<Int>()
        runs.forEachIndexed { i, run ->
            text.append(run.text)
            repeat(run.text.length) { owner.add(i) }
        }
        val whole = text.toString()
        val matches = fieldPattern.findAll(whole).toList()
        if (matches.isEmpty()) return listOf(paragraph)

        val out = ArrayList<TextRun>()
        // Le texte hors champs est redécoupé aux frontières des fragments
        // d'origine : chacun garde sa mise en forme.
        fun plain(from: Int, until: Int) {
            var i = from
            while (i < until) {
                val run = owner[i]
                var j = i
                while (j < until && owner[j] == run) j++
                out.add(runs[run].copy(text = whole.substring(i, j)))
                i = j
            }
        }

        var cursor = 0
        for (match in matches) {
            plain(cursor, match.range.first)
            val style = runs[owner[match.range.first]]
            val value = resolve(match.groupValues[1].trim())
            when {
                // Champ que les données ne connaissent pas : on le laisse voir.
                value == null -> out.add(style.copy(text = match.value))
                value.isNotEmpty() -> out.add(style.copy(text = value.replace("\r\n", "\n").replace('\r', '\n')))
            }
            cursor = match.range.last + 1
        }
        plain(cursor, whole.length)

        return lines(join(out), paragraph)
    }

    /** Fragments voisins de même mise en forme réunis : le document reste lisible. */
    private fun join(runs: List<TextRun>): List<TextRun> {
        val out = ArrayList<TextRun>()
        runs.filter { it.text.isNotEmpty() }.forEach { run ->
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == run.copy(text = "")) {
                out[out.size - 1] = last.copy(text = last.text + run.text)
            } else {
                out.add(run)
            }
        }
        return out
    }

    /** Un saut de ligne dans une valeur donne un nouveau paragraphe. */
    private fun lines(runs: List<TextRun>, template: TextParagraph): List<TextParagraph> {
        if (runs.none { it.text.contains('\n') }) return listOf(template.copy(runs = runs))
        val out = ArrayList<TextParagraph>()
        var current = ArrayList<TextRun>()
        runs.forEach { run ->
            val pieces = run.text.split('\n')
            pieces.forEachIndexed { i, piece ->
                if (i > 0) {
                    out.add(template.copy(runs = current.toList()))
                    current = ArrayList()
                }
                if (piece.isNotEmpty()) current.add(run.copy(text = piece))
            }
        }
        out.add(template.copy(runs = current.toList()))
        return out
    }

    private fun name(modelTitle: String, record: Record, options: MergeOptions, index: Int): String {
        val base = modelTitle.trim().ifEmpty { "Courrier" }
        val chosen = options.nameField?.let { record.value(it) }?.let(::oneLine).orEmpty()
        val label = chosen.ifEmpty { "${index + 1}" }
        return "$base — ${label.take(40).trim()}"
    }

    /** Une valeur glissée dans un nom ou un titre : une seule ligne, sans séparateur de chemin. */
    private fun oneLine(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("[/\\\\]"), "-").trim()

    private val separator = TextParagraph(listOf(TextRun("· · ·", color = 0xFF94A3B8L)), align = 1)
}
