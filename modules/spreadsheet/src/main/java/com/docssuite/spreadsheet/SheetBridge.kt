package com.docssuite.spreadsheet

import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.FormulaEngine
import com.docssuite.fileformats.CellRef
import com.docssuite.fileformats.CellStyle
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.Workbook

/** Traduction entre l'état du tableur et le modèle pivot des fichiers. */

internal fun buildWorkbook(
    name: String,
    cells: Map<String, String>,
    formats: Map<String, CellFormat>,
    columns: Int,
    rows: Int
): Workbook {
    // La grille affichée est toujours plus grande que les données : on
    // n'exporte que la zone utilisée. Sans cela, un CSV ou un PDF issu d'un
    // classeur importé de 5000 lignes vides ferait des milliers de pages.
    var usedColumns = 0
    var usedRows = 0
    (cells.keys + formats.keys).forEach { ref ->
        val position = CellRef.parse(ref) ?: return@forEach
        usedRows = maxOf(usedRows, position.first + 1)
        usedColumns = maxOf(usedColumns, position.second + 1)
    }

    return Workbook(
        title = name,
        sheets = listOf(
            Sheet(
                name = name.take(31).ifBlank { "Feuille1" },
                cells = cells.toMap(),
                styles = formats.mapValues { (_, format) ->
                    CellStyle(
                        bold = format.bold,
                        italic = format.italic,
                        color = format.color,
                        background = format.background,
                        align = format.align
                    )
                },
                columns = usedColumns.coerceIn(1, columns),
                rows = usedRows.coerceIn(1, rows)
            )
        )
    )
}

/**
 * Valeur calculée d'une cellule, telle qu'elle s'affiche dans l'app. Les
 * fichiers Office stockent ce résultat à côté de la formule, pour que le
 * tableau s'affiche avant même d'être recalculé.
 */
internal fun sheetDisplayValue(sheet: Sheet, ref: String): String =
    FormulaEngine.displayValue(ref, sheet.cells)

/** Ce qu'un classeur importé donne pour l'éditeur. */
internal class ImportedSheet(
    val name: String,
    val cells: Map<String, String>,
    val formats: Map<String, CellFormat>,
    val columns: Int,
    val rows: Int
)

/**
 * L'éditeur n'affiche qu'une feuille : les suivantes sont recopiées sous leur
 * nom, en dessous, plutôt que d'être perdues en silence.
 */
internal fun decodeWorkbook(workbook: Workbook, fallbackName: String): ImportedSheet {
    val cells = LinkedHashMap<String, String>()
    val formats = LinkedHashMap<String, CellFormat>()
    var columns = 0
    var rowOffset = 0

    workbook.sheets.forEachIndexed { index, sheet ->
        if (index > 0) {
            cells[CellRef.key(rowOffset + 1, 0)] = sheet.name
            rowOffset += 3
        }
        var lastRow = 0
        sheet.cells.forEach { (ref, value) ->
            val position = CellRef.parse(ref) ?: return@forEach
            val row = position.first + rowOffset
            cells[CellRef.key(row, position.second)] = value
            lastRow = maxOf(lastRow, position.first + 1)
            columns = maxOf(columns, position.second + 1)
        }
        sheet.styles.forEach { (ref, style) ->
            val position = CellRef.parse(ref) ?: return@forEach
            formats[CellRef.key(position.first + rowOffset, position.second)] =
                CellFormat(
                    bold = style.bold,
                    italic = style.italic,
                    color = style.color,
                    background = style.background,
                    align = style.align
                )
        }
        rowOffset += maxOf(lastRow, sheet.rows.coerceAtMost(lastRow + 1))
    }

    return ImportedSheet(
        name = workbook.sheets.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: fallbackName,
        cells = cells,
        formats = formats,
        columns = columns.coerceIn(4, 702),
        rows = rowOffset.coerceIn(20, 5000)
    )
}

/** Enregistre un classeur importé et renvoie son identifiant. */
fun saveImportedWorkbook(
    storage: DocumentStorage,
    workbook: Workbook,
    name: String
): String {
    val decoded = decodeWorkbook(workbook, name)
    val id = storage.newId()
    storage.save(
        id,
        name,
        DocType.SHEET,
        encodeSheet(decoded.cells, decoded.formats, decoded.columns, decoded.rows, emptyList())
    )
    return id
}
