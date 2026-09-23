package com.docssuite.texteditor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Modèle de l'éditeur de documents : une suite de blocs, chacun étant soit un
 * texte riche, soit un tableau dont chaque cellule est elle-même un texte
 * riche. Toutes les opérations sont des fonctions pures, testables sans
 * interface.
 */

/** [align] : 0 gauche, 1 centre, 2 droite, 3 justifié ; [heading] : 0 à 3. */
data class ParaStyle(val align: Int = 0, val heading: Int = 0, val indent: Int = 0)

/**
 * Un champ de texte riche. La valeur porte le texte, la sélection et la
 * composition du clavier ; [styles] a un élément par caractère ; [paras] un
 * par paragraphe (une ligne de plus que le nombre de sauts de ligne).
 */
data class RichField(
    val value: TextFieldValue = TextFieldValue(""),
    val styles: List<CharStyle> = emptyList(),
    val paras: List<ParaStyle> = listOf(ParaStyle())
) {
    val text: String get() = value.text

    /** Répare les longueurs après une opération ou un fichier incohérent. */
    fun normalized(): RichField {
        val length = text.length
        val fixedStyles = when {
            styles.size == length -> styles
            styles.size > length -> styles.take(length)
            else -> styles + List(length - styles.size) { styles.lastOrNull() ?: CharStyle() }
        }
        val count = text.count { it == '\n' } + 1
        val fixedParas = when {
            paras.size == count -> paras
            paras.size > count -> paras.take(count)
            else -> paras + List(count - paras.size) { paras.lastOrNull()?.copy(heading = 0) ?: ParaStyle() }
        }
        val selection = TextRange(value.selection.start.coerceIn(0, length), value.selection.end.coerceIn(0, length))
        val fixedValue = if (selection == value.selection) value else value.copy(selection = selection)
        return if (fixedStyles === styles && fixedParas === paras && fixedValue === value) this
        else RichField(fixedValue, fixedStyles, fixedParas)
    }

    companion object {
        fun of(text: String, style: CharStyle = CharStyle()): RichField = RichField(
            value = TextFieldValue(text),
            styles = List(text.length) { style },
            paras = List(text.count { it == '\n' } + 1) { ParaStyle() }
        )
    }
}

data class CellField(
    val field: RichField = RichField(),
    val fill: Long = 0L,
    val colSpan: Int = 1,
    val mergedAbove: Boolean = false
)

data class TableData(
    val rows: List<List<CellField>>,
    val headerRow: Boolean = false
) {
    val columnCount: Int get() = rows.maxOfOrNull { row -> row.sumOf { it.colSpan } } ?: 0
}

sealed interface EditorBlock {
    val id: Long

    data class Text(override val id: Long, val field: RichField) : EditorBlock
    data class Table(override val id: Long, val table: TableData) : EditorBlock
}

/** Où se trouve le curseur : un bloc de texte, ou une cellule d'un tableau. */
data class Target(val block: Int, val row: Int = -1, val cell: Int = -1) {
    val inTable: Boolean get() = row >= 0
}

data class EditorDoc(
    val blocks: List<EditorBlock>,
    /** Interligne en pourcentage. */
    val lineSpacing: Int = 100
)

private var nextBlockId = 1L

fun newBlockId(): Long = synchronized(EditorModel) { nextBlockId++ }

object EditorModel {

    const val HEADING_NONE = 0
    val BULLETS = listOf("• ", "◦ ", "▪ ")
    private val listPrefix = Regex("^(• |◦ |▪ |\\d{1,3}[.)] )")

    fun headingSize(level: Int): Int = when (level) {
        1 -> 24
        2 -> 20
        3 -> 18
        else -> 16
    }

    fun emptyDoc(): EditorDoc = EditorDoc(listOf(EditorBlock.Text(newBlockId(), RichField())))

    // ------------------------------------------------------------ accès

    fun field(doc: EditorDoc, target: Target): RichField? {
        val block = doc.blocks.getOrNull(target.block) ?: return null
        return when (block) {
            is EditorBlock.Text -> if (target.inTable) null else block.field
            is EditorBlock.Table -> block.table.rows.getOrNull(target.row)?.getOrNull(target.cell)
                ?.takeIf { !it.mergedAbove }?.field
        }
    }

    fun withField(doc: EditorDoc, target: Target, field: RichField): EditorDoc {
        val block = doc.blocks.getOrNull(target.block) ?: return doc
        val replaced: EditorBlock = when (block) {
            is EditorBlock.Text -> if (target.inTable) return doc else block.copy(field = field)
            is EditorBlock.Table -> {
                val rows = block.table.rows.mapIndexed { r, row ->
                    if (r != target.row) row else row.mapIndexed { c, cell ->
                        if (c == target.cell) cell.copy(field = field) else cell
                    }
                }
                block.copy(table = block.table.copy(rows = rows))
            }
        }
        return doc.copy(blocks = doc.blocks.toMutableList().also { it[target.block] = replaced })
    }

    /** Tous les champs éditables, dans l'ordre de lecture. */
    fun targets(doc: EditorDoc): List<Target> = doc.blocks.flatMapIndexed { index, block ->
        when (block) {
            is EditorBlock.Text -> listOf(Target(index))
            is EditorBlock.Table -> block.table.rows.flatMapIndexed { r, row ->
                row.mapIndexedNotNull { c, cell -> if (cell.mergedAbove) null else Target(index, r, c) }
            }
        }
    }

    fun allText(doc: EditorDoc): String = targets(doc).joinToString("\n") { field(doc, it)?.text.orEmpty() }

    // ------------------------------------------------------------ paragraphes

    fun paragraphStarts(text: String): List<Int> {
        val starts = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { index, c -> if (c == '\n') starts.add(index + 1) }
        return starts
    }

    fun paragraphIndexAt(text: String, offset: Int): Int {
        var count = 0
        for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') count++
        return count
    }

    /** Début et fin (hors saut de ligne) du paragraphe [index]. */
    fun paragraphBounds(text: String, index: Int): IntRange {
        val starts = paragraphStarts(text)
        val start = starts.getOrElse(index) { text.length }
        val end = starts.getOrNull(index + 1)?.minus(1) ?: text.length
        return start until end
    }

    /** Paragraphes touchés par la sélection (au moins celui du curseur). */
    fun selectedParagraphs(field: RichField): IntRange {
        val selection = field.value.selection
        val first = paragraphIndexAt(field.text, selection.min)
        val last = paragraphIndexAt(field.text, selection.max)
        return first..last
    }

    /**
     * Recale les styles de paragraphe après une modification du texte : les
     * paragraphes fusionnés par un effacement disparaissent, ceux créés par
     * un saut de ligne reprennent l'alignement et le retrait de leur voisin.
     */
    fun adjustParas(old: String, new: String, paras: List<ParaStyle>): List<ParaStyle> {
        if (old == new) return paras
        val maxCommon = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < maxCommon && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < maxCommon - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

        val removed = old.substring(prefix, old.length - suffix).count { it == '\n' }
        val inserted = new.substring(prefix, new.length - suffix).count { it == '\n' }
        if (removed == 0 && inserted == 0) return paras

        val at = paragraphIndexAt(old, prefix)
        val base = paras.getOrElse(at) { ParaStyle() }
        val out = ArrayList<ParaStyle>(paras.size - removed + inserted)
        for (i in 0..at) out.add(paras.getOrElse(i) { ParaStyle() })
        // Un titre ne se prolonge pas au paragraphe suivant, comme dans Word.
        repeat(inserted) { out.add(base.copy(heading = 0)) }
        for (i in (at + removed + 1) until paras.size) out.add(paras[i])
        return out
    }

    /** Remplace une plage du texte ; les caractères insérés prennent [style]. */
    fun replace(field: RichField, start: Int, end: Int, insert: String, style: CharStyle, caret: Int? = null): RichField {
        val text = field.text
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val newText = text.substring(0, s) + insert + text.substring(e)
        val styles = ArrayList<CharStyle>(newText.length)
        for (i in 0 until s) styles.add(field.styles.getOrElse(i) { CharStyle() })
        repeat(insert.length) { styles.add(style) }
        for (i in e until text.length) styles.add(field.styles.getOrElse(i) { CharStyle() })
        val position = (caret ?: (s + insert.length)).coerceIn(0, newText.length)
        return RichField(
            value = TextFieldValue(newText, TextRange(position)),
            styles = styles,
            paras = adjustParas(text, newText, field.paras)
        ).normalized()
    }

    private fun mapParas(field: RichField, range: IntRange, transform: (ParaStyle) -> ParaStyle): RichField {
        val paras = field.paras.toMutableList()
        for (i in range) if (i in paras.indices) paras[i] = transform(paras[i])
        return field.copy(paras = paras)
    }

    fun setAlign(field: RichField, align: Int): RichField =
        mapParas(field, selectedParagraphs(field)) { it.copy(align = align) }

    fun changeIndent(field: RichField, delta: Int): RichField =
        mapParas(field, selectedParagraphs(field)) { it.copy(indent = (it.indent + delta).coerceIn(0, 8)) }

    /**
     * Applique un niveau de titre aux paragraphes choisis. La taille et la
     * graisse suivent, pour que le titre se voie dès l'écran.
     */
    fun setHeading(field: RichField, level: Int): RichField {
        val range = selectedParagraphs(field)
        var result = mapParas(field, range) { it.copy(heading = level) }
        val styles = result.styles.toMutableList()
        for (index in range) {
            for (i in paragraphBounds(field.text, index)) {
                if (i in styles.indices) {
                    styles[i] = styles[i].copy(size = headingSize(level), bold = level > 0)
                }
            }
        }
        result = result.copy(styles = styles)
        return result
    }

    fun listPrefixOf(line: String): String? = listPrefix.find(line)?.value

    /**
     * Puces : si tous les paragraphes choisis en portent déjà, on les retire ;
     * sinon on en met partout (en remplaçant une numérotation éventuelle).
     */
    fun toggleBullets(field: RichField): RichField {
        val range = selectedParagraphs(field)
        val lines = range.map { field.text.substring(paragraphBounds(field.text, it).let { r -> r.first until r.last + 1 }) }
        val allBullets = lines.all { line -> BULLETS.any { line.startsWith(it) } }
        return rewritePrefixes(field, range) { index, line ->
            if (allBullets) null else BULLETS[(field.paras.getOrNull(index)?.indent ?: 0) % 3]
        }
    }

    fun toggleNumbering(field: RichField): RichField {
        val range = selectedParagraphs(field)
        val lines = range.map { field.text.substring(paragraphBounds(field.text, it).let { r -> r.first until r.last + 1 }) }
        val allNumbered = lines.all { line -> line.matches(Regex("^\\d{1,3}[.)] .*", RegexOption.DOT_MATCHES_ALL)) }
        var counter = 0
        return rewritePrefixes(field, range) { _, _ ->
            if (allNumbered) null else "${++counter}. "
        }
    }

    /** Remplace le préfixe de liste de chaque paragraphe (du dernier au premier). */
    private fun rewritePrefixes(
        field: RichField,
        range: IntRange,
        prefixFor: (Int, String) -> String?
    ): RichField {
        // Les préfixes se calculent dans l'ordre, puis s'appliquent à rebours
        // pour que les positions restent valables.
        val plan = range.map { index ->
            val bounds = paragraphBounds(field.text, index)
            val line = field.text.substring(bounds.first, bounds.last + 1)
            Triple(bounds.first, listPrefixOf(line)?.length ?: 0, prefixFor(index, line))
        }
        var result = field
        val selection = field.value.selection
        var start = selection.start
        var end = selection.end
        for ((lineStart, oldLength, newPrefix) in plan.reversed()) {
            val style = result.styles.getOrNull(lineStart + oldLength) ?: result.styles.getOrNull(lineStart) ?: CharStyle()
            val insert = newPrefix.orEmpty()
            result = replace(result, lineStart, lineStart + oldLength, insert, style.copy(underline = false, strike = false, highlight = 0L))
            val delta = insert.length - oldLength
            if (start >= lineStart) start = (start + delta).coerceAtLeast(lineStart)
            if (end >= lineStart) end = (end + delta).coerceAtLeast(lineStart)
        }
        return result.copy(value = result.value.copy(selection = TextRange(start, end))).normalized()
    }

    /**
     * Entrée dans une liste : l'élément suivant reçoit la puce ou le numéro
     * suivant ; Entrée sur un élément vide termine la liste. Renvoie `null`
     * si la modification n'est pas un simple retour à la ligne.
     */
    fun continueList(before: RichField, after: RichField, style: CharStyle): RichField? {
        val old = before.text
        val new = after.text
        if (new.length != old.length + 1) return null
        val caret = after.value.selection.start
        if (caret <= 0 || new[caret - 1] != '\n' || !after.value.selection.collapsed) return null
        if (old != new.removeRange(caret - 1, caret)) return null

        val previousIndex = paragraphIndexAt(new, caret - 1)
        val bounds = paragraphBounds(new, previousIndex)
        val previous = new.substring(bounds.first, bounds.last + 1)
        val prefix = listPrefixOf(previous) ?: return null

        if (previous == prefix) {
            // Élément vide : on sort de la liste en retirant la puce et le saut.
            return replace(after, bounds.first, caret, "", style, caret = bounds.first)
        }
        val next = Regex("^(\\d{1,3})([.)]) ").find(prefix)?.let { match ->
            "${match.groupValues[1].toInt() + 1}${match.groupValues[2]} "
        } ?: prefix
        return replace(after, caret, caret, next, style)
    }

    fun changeCase(field: RichField, mode: Int): RichField {
        val selection = field.value.selection
        if (selection.collapsed) return field
        val text = field.text.substring(selection.min, selection.max)
        val changed = when (mode) {
            0 -> text.uppercase()
            1 -> text.lowercase()
            else -> text.lowercase().split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase() }
            }
        }
        if (changed == text) return field
        // À longueur égale (le cas courant), chaque caractère garde son style.
        return if (changed.length == text.length) {
            field.copy(value = field.value.copy(text = field.text.replaceRange(selection.min, selection.max, changed)))
        } else {
            val style = field.styles.getOrElse(selection.min) { CharStyle() }
            val replaced = replace(field, selection.min, selection.max, changed, style)
            replaced.copy(value = replaced.value.copy(selection = TextRange(selection.min, selection.min + changed.length)))
        }
    }

    // ------------------------------------------------------------ tableaux

    fun newTable(rows: Int, columns: Int, header: Boolean): TableData = TableData(
        rows = List(rows.coerceIn(1, 60)) { r ->
            List(columns.coerceIn(1, 12)) {
                CellField(field = if (header && r == 0) RichField.of("", CharStyle(bold = true)) else RichField())
            }
        },
        headerRow = header
    )

    /**
     * Insère un tableau au curseur d'un bloc de texte : le texte est coupé en
     * deux autour du tableau, et un paragraphe vide suit toujours le tableau
     * pour qu'on puisse écrire après.
     */
    fun insertTable(doc: EditorDoc, target: Target, table: TableData): Pair<EditorDoc, Target> {
        val blocks = doc.blocks.toMutableList()
        val tableBlock = EditorBlock.Table(newBlockId(), table)
        val block = blocks.getOrNull(target.block)
        if (block is EditorBlock.Text && !target.inTable) {
            val field = block.field
            val caret = field.value.selection.max.coerceIn(0, field.text.length)
            val (before, after) = split(field, caret)
            blocks[target.block] = block.copy(field = before)
            blocks.add(target.block + 1, tableBlock)
            blocks.add(target.block + 2, EditorBlock.Text(newBlockId(), after))
        } else {
            val at = (target.block + 1).coerceIn(0, blocks.size)
            blocks.add(at, tableBlock)
            if (blocks.getOrNull(at + 1) !is EditorBlock.Text) {
                blocks.add(at + 1, EditorBlock.Text(newBlockId(), RichField()))
            }
        }
        val merged = doc.copy(blocks = mergeAdjacentText(blocks))
        return merged to Target(merged.blocks.indexOfFirst { it.id == tableBlock.id }, 0, 0)
    }

    /** Coupe un champ en deux à [offset] ; le saut de ligne à la coupure disparaît. */
    fun split(field: RichField, offset: Int): Pair<RichField, RichField> {
        val text = field.text
        var cut = offset.coerceIn(0, text.length)
        var beforeEnd = cut
        var afterStart = cut
        // Couper juste après ou juste avant un saut de ligne ne doit pas laisser
        // un paragraphe vide de part et d'autre.
        if (cut > 0 && text[cut - 1] == '\n') beforeEnd = cut - 1
        else if (cut < text.length && text[cut] == '\n') afterStart = cut + 1
        val paraIndex = paragraphIndexAt(text, cut)
        val before = RichField(
            value = TextFieldValue(text.substring(0, beforeEnd), TextRange(beforeEnd)),
            styles = field.styles.take(beforeEnd),
            paras = field.paras.take(paragraphIndexAt(text, beforeEnd) + 1)
        ).normalized()
        val afterParaStart = paragraphIndexAt(text, afterStart)
        val after = RichField(
            value = TextFieldValue(text.substring(afterStart), TextRange(0)),
            styles = field.styles.drop(afterStart),
            paras = field.paras.drop(afterParaStart).ifEmpty { listOf(field.paras.getOrElse(paraIndex) { ParaStyle() }) }
        ).normalized()
        return before to after
    }

    /** Deux blocs de texte voisins (après la suppression d'un tableau) n'en font qu'un. */
    fun mergeAdjacentText(blocks: List<EditorBlock>): List<EditorBlock> {
        val out = ArrayList<EditorBlock>()
        for (block in blocks) {
            val last = out.lastOrNull()
            if (block is EditorBlock.Text && last is EditorBlock.Text) {
                out[out.size - 1] = last.copy(field = join(last.field, block.field))
            } else {
                out.add(block)
            }
        }
        if (out.isEmpty() || out.first() !is EditorBlock.Text) out.add(0, EditorBlock.Text(newBlockId(), RichField()))
        if (out.last() !is EditorBlock.Text) out.add(EditorBlock.Text(newBlockId(), RichField()))
        return out
    }

    private fun join(first: RichField, second: RichField): RichField {
        if (first.text.isEmpty()) return second
        if (second.text.isEmpty() && second.paras.size <= 1) return first
        val text = first.text + "\n" + second.text
        return RichField(
            value = TextFieldValue(text, TextRange(first.text.length)),
            styles = first.styles + (first.styles.lastOrNull() ?: CharStyle()) + second.styles,
            paras = first.paras + second.paras
        ).normalized()
    }

    fun deleteTable(doc: EditorDoc, blockIndex: Int): EditorDoc {
        val blocks = doc.blocks.toMutableList()
        if (blocks.getOrNull(blockIndex) !is EditorBlock.Table) return doc
        blocks.removeAt(blockIndex)
        return doc.copy(blocks = mergeAdjacentText(blocks))
    }

    fun cellStart(row: List<CellField>, index: Int): Int = row.take(index).sumOf { it.colSpan }

    fun cellIndexAt(row: List<CellField>, column: Int): Int {
        var position = 0
        row.forEachIndexed { index, cell ->
            if (column < position + cell.colSpan) return index
            position += cell.colSpan
        }
        return row.lastIndex
    }

    fun insertRow(table: TableData, row: Int, below: Boolean): TableData {
        val model = table.rows.getOrNull(row) ?: return table
        val fresh = model.map { CellField(colSpan = it.colSpan) }
        val rows = table.rows.toMutableList()
        rows.add(if (below) row + 1 else row, fresh)
        return table.copy(rows = rows)
    }

    /** Supprime une ligne ; une fusion verticale qui y commençait passe à la ligne suivante. */
    fun deleteRow(table: TableData, row: Int): TableData? {
        if (table.rows.size <= 1) return null
        val rows = table.rows.toMutableList()
        val removed = rows.removeAt(row)
        if (row < rows.size) {
            val next = rows[row]
            rows[row] = next.mapIndexed { index, cell ->
                if (!cell.mergedAbove) return@mapIndexed cell
                val column = cellStart(next, index)
                val origin = removed.getOrNull(cellIndexAt(removed, column))
                if (origin != null && !origin.mergedAbove) {
                    cell.copy(mergedAbove = false, field = origin.field, fill = origin.fill)
                } else cell
            }
        }
        return repair(table.copy(rows = rows))
    }

    fun insertColumn(table: TableData, row: Int, cell: Int, after: Boolean): TableData {
        val reference = table.rows.getOrNull(row) ?: return table
        val start = cellStart(reference, cell)
        val column = if (after) start + (reference.getOrNull(cell)?.colSpan ?: 1) else start
        val rows = table.rows.map { cells ->
            val out = ArrayList<CellField>()
            var position = 0
            var placed = false
            for (current in cells) {
                if (!placed && position == column) {
                    out.add(CellField()); placed = true
                }
                if (!placed && column in (position + 1) until (position + current.colSpan)) {
                    // La nouvelle colonne passe au milieu d'une cellule fusionnée : elle s'élargit.
                    out.add(current.copy(colSpan = current.colSpan + 1)); placed = true
                } else {
                    out.add(current)
                }
                position += current.colSpan
            }
            if (!placed) out.add(CellField())
            out
        }
        return table.copy(rows = rows)
    }

    fun deleteColumn(table: TableData, row: Int, cell: Int): TableData? {
        val reference = table.rows.getOrNull(row) ?: return table
        val column = cellStart(reference, cell)
        if (table.columnCount <= 1) return null
        val rows = table.rows.map { cells ->
            val index = cellIndexAt(cells, column)
            cells.mapIndexedNotNull { i, current ->
                when {
                    i != index -> current
                    current.colSpan > 1 -> current.copy(colSpan = current.colSpan - 1)
                    else -> null
                }
            }
        }
        if (rows.any { it.isEmpty() }) return null
        return repair(table.copy(rows = rows))
    }

    /** Fusionne une cellule avec sa voisine de droite ; leurs textes se suivent. */
    fun mergeRight(table: TableData, row: Int, cell: Int): TableData {
        val cells = table.rows.getOrNull(row) ?: return table
        val current = cells.getOrNull(cell) ?: return table
        val next = cells.getOrNull(cell + 1) ?: return table
        if (current.mergedAbove || next.mergedAbove) return table
        val merged = if (next.field.text.isBlank()) current.field
        else if (current.field.text.isBlank()) next.field
        else join(current.field, next.field)
        val newRow = cells.toMutableList()
        newRow[cell] = current.copy(field = merged, colSpan = current.colSpan + next.colSpan)
        newRow.removeAt(cell + 1)
        return table.copy(rows = table.rows.toMutableList().also { it[row] = newRow })
    }

    fun splitCell(table: TableData, row: Int, cell: Int): TableData {
        val cells = table.rows.getOrNull(row) ?: return table
        val current = cells.getOrNull(cell) ?: return table
        if (current.colSpan <= 1) return table
        val newRow = cells.toMutableList()
        newRow[cell] = current.copy(colSpan = current.colSpan - 1)
        newRow.add(cell + 1, CellField())
        return table.copy(rows = table.rows.toMutableList().also { it[row] = newRow })
    }

    fun setFill(table: TableData, row: Int, cell: Int, fill: Long): TableData {
        val rows = table.rows.mapIndexed { r, cells ->
            if (r != row) cells else cells.mapIndexed { c, current -> if (c == cell) current.copy(fill = fill) else current }
        }
        return table.copy(rows = rows)
    }

    /**
     * Les fusions verticales ne tiennent que si la cellule du dessus occupe
     * la même colonne avec la même largeur ; sinon la cellule redevient libre.
     */
    fun repair(table: TableData): TableData {
        val rows = table.rows.mapIndexed { r, cells ->
            if (r == 0) cells.map { if (it.mergedAbove) it.copy(mergedAbove = false) else it }
            else cells.mapIndexed { index, cell ->
                if (!cell.mergedAbove) return@mapIndexed cell
                val above = table.rows[r - 1]
                val column = cellStart(cells, index)
                val aboveIndex = cellIndexAt(above, column)
                val aboveCell = above.getOrNull(aboveIndex)
                val aligned = aboveCell != null && cellStart(above, aboveIndex) == column && aboveCell.colSpan == cell.colSpan
                if (aligned) cell else cell.copy(mergedAbove = false)
            }
        }
        return table.copy(rows = rows)
    }
}
