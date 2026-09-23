package com.docssuite.fileformats

import org.xmlpull.v1.XmlPullParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Outils partagés par les lecteurs de documents : assemblage des blocs
 * (paragraphes et tableaux, y compris imbriqués ou mal formés) et décodage
 * des textes dont on ignore l'encodage.
 */

/**
 * Assemble les blocs d'un document à mesure qu'un lecteur les rencontre.
 *
 * Les lecteurs n'ont qu'à signaler « début de tableau, de ligne, de
 * cellule » : le constructeur referme ce qu'un fichier oublie de fermer,
 * complète les lignes courtes, place les cellules prolongées par une fusion
 * verticale et aplatit les tableaux imbriqués dans la cellule qui les
 * contient. Aucun fichier, si étrange soit-il, ne doit produire une grille
 * que l'éditeur ne saurait pas afficher.
 */
internal class BlockBuilder {

    private class CellDraft {
        val paragraphs = ArrayList<TextParagraph>()
        var colSpan = 1
        var rowSpan = 1
        var fill = 0L
        var mergedAbove = false
    }

    private class TableDraft {
        val rows = ArrayList<TableRow>()
        var cells: ArrayList<TableCell>? = null
        var cell: CellDraft? = null
        var column = 0
        var header = false
        var headerRows = 0

        /** Colonne → (lignes encore couvertes, largeur de la fusion). */
        val pendingDown = HashMap<Int, Pair<Int, Int>>()
    }

    private val blocks = ArrayList<DocBlock>()
    private val tables = ArrayList<TableDraft>()

    val inTable: Boolean get() = tables.isNotEmpty()

    fun paragraph(paragraph: TextParagraph) {
        val table = tables.lastOrNull()
        if (table == null) {
            blocks.add(paragraph)
            return
        }
        // Du texte hors cellule à l'intérieur d'un tableau : on le range dans
        // une cellule plutôt que de le perdre.
        if (table.cell == null) startCell()
        table.cell!!.paragraphs.add(paragraph)
    }

    fun startTable() {
        // Un tableau dans une cellule est aplati à sa fermeture ; il faut
        // pour cela qu'une cellule l'accueille.
        tables.lastOrNull()?.let { if (it.cell == null) startCell() }
        tables.add(TableDraft())
    }

    fun startRow(header: Boolean = false) {
        val table = tables.lastOrNull() ?: run { startTable(); tables.last() }
        if (table.cells != null) endRow()
        table.cells = ArrayList()
        table.column = 0
        if (header) markHeaderRow()
    }

    fun startCell(colSpan: Int = 1, rowSpan: Int = 1, fill: Long = 0L) {
        val table = tables.lastOrNull() ?: run { startTable(); tables.last() }
        if (table.cells == null) startRow()
        if (table.cell != null) endCell()
        table.cell = CellDraft().apply {
            this.colSpan = colSpan.coerceIn(1, MAX_COLUMNS)
            this.rowSpan = rowSpan.coerceIn(1, MAX_ROWS)
            this.fill = fill
        }
    }

    /** La ligne en cours est un en-tête, si elle suit directement les précédents. */
    fun markHeaderRow() {
        val table = tables.lastOrNull() ?: return
        if (table.cells != null && table.rows.size == table.headerRows) table.headerRows++
    }

    /** Réglages connus après l'ouverture de la cellule (Word les met en tête). */
    fun cellSpan(span: Int) {
        tables.lastOrNull()?.cell?.colSpan = span.coerceIn(1, MAX_COLUMNS)
    }

    fun cellFill(color: Long) {
        tables.lastOrNull()?.cell?.fill = color
    }

    fun cellMergedAbove() {
        tables.lastOrNull()?.cell?.mergedAbove = true
    }

    fun endCell() {
        val table = tables.lastOrNull() ?: return
        val draft = table.cell ?: return
        val cells = table.cells ?: ArrayList<TableCell>().also { table.cells = it }
        placeCoveredCells(table)
        cells.add(
            TableCell(
                paragraphs = draft.paragraphs.ifEmpty { listOf(TextParagraph()) },
                fill = draft.fill,
                colSpan = draft.colSpan,
                mergedAbove = draft.mergedAbove
            )
        )
        if (draft.rowSpan > 1) table.pendingDown[table.column] = (draft.rowSpan - 1) to draft.colSpan
        table.column += draft.colSpan
        table.cell = null
    }

    fun endRow() {
        val table = tables.lastOrNull() ?: return
        if (table.cell != null) endCell()
        val cells = table.cells ?: return
        // Les fusions verticales qui descendent au-delà de la dernière
        // cellule de la ligne doivent quand même y laisser leur place.
        while (table.pendingDown.keys.any { it >= table.column }) {
            val next = table.pendingDown.keys.filter { it >= table.column }.min()
            while (table.column < next) {
                cells.add(TableCell()); table.column++
            }
            placeCoveredCells(table)
        }
        if (cells.isNotEmpty()) table.rows.add(TableRow(cells.toList()))
        table.cells = null
    }

    fun endTable() {
        val table = tables.lastOrNull() ?: return
        if (table.cells != null) endRow()
        tables.removeAt(tables.size - 1)
        if (table.rows.isEmpty()) return

        val built = TextTable(table.rows.toList(), headerRow = table.headerRows > 0).normalized()
        val outer = tables.lastOrNull()
        if (outer == null) {
            blocks.add(built)
        } else {
            // Tableau imbriqué : une ligne de texte par rangée, dans la
            // cellule qui le contient.
            val cell = outer.cell ?: CellDraft().also { outer.cell = it }
            built.rows.forEach { row ->
                val text = row.cells.filterNot { it.mergedAbove }
                    .joinToString(" | ") { it.plainText.replace('\n', ' ').trim() }
                cell.paragraphs.add(TextParagraph(listOf(TextRun(text))))
            }
        }
    }

    fun build(): List<DocBlock> {
        while (tables.isNotEmpty()) endTable()
        return blocks.toList()
    }

    private fun placeCoveredCells(table: TableDraft) {
        val cells = table.cells ?: return
        while (true) {
            val (remaining, span) = table.pendingDown[table.column] ?: return
            cells.add(TableCell(colSpan = span, mergedAbove = true))
            if (remaining <= 1) table.pendingDown.remove(table.column)
            else table.pendingDown[table.column] = (remaining - 1) to span
            table.column += span
        }
    }

    companion object {
        /** Bornes de sûreté : une fusion déclarée sur 60 000 colonnes existe. */
        const val MAX_COLUMNS = 64
        const val MAX_ROWS = 4096
    }
}

/**
 * Une ligne de tableau décrite par les bords droits de ses cellules, comme
 * dans RTF et les .doc : une cellule fusionnée n'y est qu'une cellule plus
 * large. On retrouve les fusions en posant toutes les lignes sur une grille
 * commune.
 */
internal class GridRow(
    val cells: List<List<TextParagraph>>,
    /** Bord droit de chaque cellule, en twips, ou vide s'il est inconnu. */
    val rightEdges: List<Int>,
    /** Pour chaque cellule : 0 = normale, 2 = prolonge celle du dessus. */
    val vertical: List<Int> = emptyList(),
    val fills: List<Long> = emptyList(),
    val header: Boolean = false
)

internal fun BlockBuilder.gridTable(rows: List<GridRow>) {
    if (rows.isEmpty()) return
    // Les bords proches de quelques twips sont un même bord arrondi différemment.
    val edges = rows.flatMap { it.rightEdges }.sorted().fold(ArrayList<Int>()) { acc, edge ->
        if (acc.isEmpty() || edge - acc.last() > 30) acc.add(edge)
        acc
    }
    fun column(edge: Int): Int = edges.indexOfFirst { kotlin.math.abs(it - edge) <= 30 }.coerceAtLeast(0)

    startTable()
    rows.forEach { row ->
        startRow(header = row.header)
        var previous = -1
        row.cells.forEachIndexed { index, paragraphs ->
            val edge = row.rightEdges.getOrNull(index)
            val span = if (edge == null || row.rightEdges.size != row.cells.size) 1
            else (column(edge) - previous).coerceAtLeast(1)
            if (edge != null && row.rightEdges.size == row.cells.size) previous = column(edge)
            startCell(colSpan = span)
            row.fills.getOrNull(index)?.takeIf { it != 0L }?.let { cellFill(it) }
            if (row.vertical.getOrNull(index) == 2) cellMergedAbove()
            else paragraphs.forEach { paragraph(it) }
            endCell()
        }
        endRow()
    }
    endTable()
}

// ---------------------------------------------------------------- XML

/**
 * Passe un sous-arbre entier. Le parseur doit être sur la balise ouvrante ;
 * il s'arrête sur la balise fermante correspondante.
 */
internal fun XmlPullParser.skipSubtree() {
    if (eventType != XmlPullParser.START_TAG) return
    // `<a/>` produit lui aussi un START_TAG suivi d'un END_TAG.
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

// ---------------------------------------------------------------- encodages

/**
 * Décode un texte d'origine inconnue. Un BOM tranche ; sinon on tente
 * l'UTF-8 strict, et s'il échoue c'est presque toujours un fichier « ANSI »
 * de Windows, en cp1252 en Europe de l'Ouest.
 */
fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    // UTF-16 sans BOM : un octet nul sur deux dans un texte latin.
    if (bytes.size >= 4) {
        val sample = minOf(bytes.size, 512) and 1.inv()
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sample) if (bytes[i].toInt() == 0) {
            if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        if (oddZeros > sample / 4 && evenZeros == 0) return String(bytes, Charsets.UTF_16LE)
        if (evenZeros > sample / 4 && oddZeros == 0) return String(bytes, Charsets.UTF_16BE)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (notUtf8: CharacterCodingException) {
        String(bytes, CP1252)
    }
}
