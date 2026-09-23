package com.docssuite.spreadsheet

import com.docssuite.core.FormulaEngine

/** Contenu et mises en forme après une opération sur la grille. */
data class SheetData<T>(val cells: Map<String, String>, val formats: Map<String, T>)

/**
 * Opérations de structure du tableur : insérer, supprimer, trier.
 *
 * Déplacer des cellules ne suffit pas — les formules qui les désignent doivent
 * suivre, sinon `=SOMME(A1:A5)` continuerait de pointer les anciennes lignes
 * après une insertion. Ces fonctions ne touchent à aucun état d'interface, ce
 * qui les rend vérifiables telles quelles.
 */
object SheetOps {

    fun <T> insertRow(cells: Map<String, String>, formats: Map<String, T>, at: Int): SheetData<T> =
        shift(cells, formats, rowAt = at, rowDelta = 1, colAt = -1, colDelta = 0)

    fun <T> deleteRow(cells: Map<String, String>, formats: Map<String, T>, at: Int): SheetData<T> =
        shift(cells, formats, rowAt = at, rowDelta = -1, colAt = -1, colDelta = 0)

    fun <T> insertColumn(cells: Map<String, String>, formats: Map<String, T>, at: Int): SheetData<T> =
        shift(cells, formats, rowAt = -1, rowDelta = 0, colAt = at, colDelta = 1)

    fun <T> deleteColumn(cells: Map<String, String>, formats: Map<String, T>, at: Int): SheetData<T> =
        shift(cells, formats, rowAt = -1, rowDelta = 0, colAt = at, colDelta = -1)

    private fun <T> shift(
        cells: Map<String, String>,
        formats: Map<String, T>,
        rowAt: Int,
        rowDelta: Int,
        colAt: Int,
        colDelta: Int
    ): SheetData<T> {
        val movedCells = LinkedHashMap<String, String>()
        val movedFormats = LinkedHashMap<String, T>()

        fun destination(key: String): String? {
            val row = rowOf(key) ?: return null
            val column = columnOf(key)
            // Une suppression efface la ligne ou la colonne visée.
            if (rowDelta < 0 && rowAt >= 0 && row == rowAt) return null
            if (colDelta < 0 && colAt >= 0 && column == colAt) return null
            val newRow = if (rowAt >= 0 && row >= rowAt) row + rowDelta else row
            val newColumn = if (colAt >= 0 && column >= colAt) column + colDelta else column
            if (newRow < 0 || newColumn < 0) return null
            return FormulaEngine.cellKey(newRow, newColumn)
        }

        cells.forEach { (key, value) ->
            val target = destination(key) ?: return@forEach
            movedCells[target] = adjustFormula(value, rowAt, rowDelta, colAt, colDelta)
        }
        formats.forEach { (key, value) ->
            val target = destination(key) ?: return@forEach
            movedFormats[target] = value
        }
        return SheetData(movedCells, movedFormats)
    }

    /**
     * Trie un bloc de lignes sur une colonne. Le contenu brut est déplacé en
     * bloc : chaque ligne garde ses cellules côte à côte.
     */
    fun <T> sortByColumn(
        cells: Map<String, String>,
        formats: Map<String, T>,
        column: Int,
        firstRow: Int,
        lastRow: Int,
        columns: Int,
        ascending: Boolean
    ): SheetData<T> {
        if (lastRow <= firstRow) return SheetData(cells, formats)

        val order = (firstRow..lastRow).sortedWith(
            compareBy(SortKey) { row -> sortKey(cells, FormulaEngine.cellKey(row, column)) }
        ).let { if (ascending) it else it.reversed() }

        val newCells = LinkedHashMap(cells)
        val newFormats = LinkedHashMap(formats)
        order.forEachIndexed { offset, sourceRow ->
            val targetRow = firstRow + offset
            for (c in 0 until columns) {
                val from = FormulaEngine.cellKey(sourceRow, c)
                val to = FormulaEngine.cellKey(targetRow, c)
                val value = cells[from]
                if (value == null) newCells.remove(to) else newCells[to] = value
                val format = formats[from]
                if (format == null) newFormats.remove(to) else newFormats[to] = format
            }
        }
        return SheetData(newCells, newFormats)
    }

    /** Les nombres passent avant le texte, les cases vides en dernier. */
    private fun sortKey(cells: Map<String, String>, ref: String): Triple<Int, Double, String> {
        val shown = FormulaEngine.displayValue(ref, cells)
        if (shown.isBlank()) return Triple(2, 0.0, "")
        val number = shown.replace(" ", "").replace(',', '.').toDoubleOrNull()
        return if (number != null) Triple(0, number, "")
        else Triple(1, 0.0, shown.lowercase())
    }

    private object SortKey : Comparator<Triple<Int, Double, String>> {
        override fun compare(a: Triple<Int, Double, String>, b: Triple<Int, Double, String>): Int {
            if (a.first != b.first) return a.first.compareTo(b.first)
            val byNumber = a.second.compareTo(b.second)
            return if (byNumber != 0) byNumber else a.third.compareTo(b.third)
        }
    }

    /**
     * Décale les références d'une formule. Le texte entre guillemets est laissé
     * intact : « A1 » dans un libellé n'est pas une référence.
     */
    fun adjustFormula(
        value: String,
        rowAt: Int,
        rowDelta: Int,
        colAt: Int,
        colDelta: Int
    ): String {
        if (!value.startsWith("=")) return value
        val out = StringBuilder("=")
        var i = 1
        while (i < value.length) {
            val ch = value[i]
            // Textes entre guillemets et noms de feuille entre apostrophes
            // passent tels quels.
            if (ch == '"' || ch == '\'') {
                val end = value.indexOf(ch, i + 1)
                if (end < 0) { out.append(value.substring(i)); break }
                out.append(value, i, end + 1)
                i = end + 1
                // `'Nom de feuille'!A1:B4` : la plage appartient à l'autre feuille.
                if (ch == '\'' && i < value.length && value[i] == '!') {
                    out.append('!'); i++
                    while (i < value.length && (value[i].isLetterOrDigit() || value[i] == '$' || value[i] == ':')) {
                        out.append(value[i]); i++
                    }
                }
                continue
            }
            if (ch.isLetter() && (i == 1 || !value[i - 1].isLetterOrDigit())) {
                var j = i
                while (j < value.length && value[j].isLetter()) j++
                val lettersEnd = j
                while (j < value.length && value[j].isDigit()) j++
                // Ce qui suit un « ! » appartient à une autre feuille, et un
                // nom suivi de « ! » est un nom de feuille : ni l'un ni l'autre
                // ne bouge quand on modifie cette feuille-ci.
                val isReference = lettersEnd > i && j > lettersEnd &&
                    (j >= value.length || (value[j] != '(' && value[j] != '!')) &&
                    value[i - 1] != '!'
                if (!isReference && j < value.length && value[j] == '!') {
                    out.append(value, i, j + 1)
                    i = j + 1
                    // La référence de l'autre feuille est recopiée sans décalage.
                    while (i < value.length && (value[i].isLetterOrDigit() || value[i] == '$' || value[i] == ':')) {
                        out.append(value[i]); i++
                    }
                    continue
                }
                if (isReference) {
                    val ref = value.substring(i, j)
                    out.append(moveReference(ref, rowAt, rowDelta, colAt, colDelta))
                    i = j
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun moveReference(
        ref: String,
        rowAt: Int,
        rowDelta: Int,
        colAt: Int,
        colDelta: Int
    ): String {
        val row = rowOf(ref) ?: return ref
        val column = columnOf(ref)
        if (rowDelta < 0 && rowAt >= 0 && row == rowAt) return "#REF!"
        if (colDelta < 0 && colAt >= 0 && column == colAt) return "#REF!"
        val newRow = if (rowAt >= 0 && row >= rowAt) row + rowDelta else row
        val newColumn = if (colAt >= 0 && column >= colAt) column + colDelta else column
        if (newRow < 0 || newColumn < 0) return "#REF!"
        return FormulaEngine.cellKey(newRow, newColumn)
    }

    private fun rowOf(key: String): Int? =
        key.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }

    private fun columnOf(key: String): Int =
        FormulaEngine.columnIndex(key.takeWhile { it.isLetter() })
}
