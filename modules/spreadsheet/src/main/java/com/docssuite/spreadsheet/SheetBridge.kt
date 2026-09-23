package com.docssuite.spreadsheet

import com.docssuite.core.DocType
import com.docssuite.core.DocumentStorage
import com.docssuite.core.FormulaEngine
import com.docssuite.fileformats.CellRef
import com.docssuite.fileformats.CellStyle
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.Workbook

/** Traduction entre l'état du tableur et le modèle pivot des fichiers. */

internal fun buildWorkbook(name: String, sheets: List<SheetState>): Workbook = Workbook(
    title = name,
    sheets = sheets.map { state ->
        // La grille affichée est toujours plus grande que les données : on
        // n'exporte que la zone utilisée. Sans cela, un CSV ou un PDF issu d'un
        // classeur importé de 5000 lignes vides ferait des milliers de pages.
        var usedColumns = 0
        var usedRows = 0
        (state.cells.keys + state.formats.keys).forEach { ref ->
            val position = CellRef.parse(ref) ?: return@forEach
            usedRows = maxOf(usedRows, position.first + 1)
            usedColumns = maxOf(usedColumns, position.second + 1)
        }
        Sheet(
            name = state.name.take(31).ifBlank { "Feuille1" },
            cells = state.cells.toMap(),
            styles = state.formats.mapValues { (_, format) ->
                CellStyle(
                    bold = format.bold,
                    italic = format.italic,
                    color = format.color,
                    background = format.background,
                    align = format.align
                )
            },
            columns = usedColumns.coerceIn(1, maxOf(1, state.columns)),
            rows = usedRows.coerceIn(1, maxOf(1, state.rows))
        )
    }
)

/** Ancienne signature, pour une feuille seule. */
internal fun buildWorkbook(
    name: String,
    cells: Map<String, String>,
    formats: Map<String, CellFormat>,
    columns: Int,
    rows: Int
): Workbook = buildWorkbook(name, listOf(SheetState(name.take(31).ifBlank { "Feuille1" }, cells, formats, columns, rows)))

/**
 * Valeur calculée d'une cellule, telle qu'elle s'affiche dans l'app. Les
 * fichiers Office stockent ce résultat à côté de la formule, pour que le
 * tableau s'affiche avant même d'être recalculé.
 */
internal fun sheetDisplayValue(sheet: Sheet, ref: String): String =
    FormulaEngine.displayValue(ref, sheet.cells)

/**
 * Valeurs affichées pour tout un classeur : chaque feuille est calculée avec
 * accès aux autres, pour que `=Ventes!D5` ait sa valeur dans le fichier.
 */
internal fun workbookDisplay(sheets: List<SheetState>): (Sheet, String) -> String {
    val engines = HashMap<String, Map<String, String>>()
    return { sheet, ref ->
        val engine = engines.getOrPut(sheet.name) {
            val index = sheets.indexOfFirst { it.name.take(31) == sheet.name }.coerceAtLeast(0)
            WorkbookOps.engineCells(sheets, index)
        }
        FormulaEngine.displayValue(ref, engine)
    }
}

/**
 * Un classeur importé garde toutes ses feuilles, chacune à sa place : les
 * formules qui en désignent une autre continuent de la trouver.
 */
internal fun decodeWorkbook(workbook: Workbook): List<SheetState> {
    val used = HashSet<String>()
    return workbook.sheets.mapIndexed { index, sheet ->
        var columns = 0
        var rows = 0
        sheet.cells.keys.forEach { ref ->
            val position = CellRef.parse(ref) ?: return@forEach
            rows = maxOf(rows, position.first + 1)
            columns = maxOf(columns, position.second + 1)
        }
        // Deux feuilles ne peuvent pas porter le même nom sans ambiguïté.
        var name = sheet.name.trim().ifBlank { "Feuille${index + 1}" }.take(31)
        var suffix = 2
        while (!used.add(name.lowercase())) name = "${sheet.name.take(27)} ($suffix)".also { suffix++ }
        SheetState(
            name = name,
            cells = sheet.cells,
            formats = sheet.styles.mapValues { (_, style) ->
                CellFormat(
                    bold = style.bold,
                    italic = style.italic,
                    color = style.color,
                    background = style.background,
                    align = style.align
                )
            },
            // Toujours un peu de place après les données pour continuer à saisir.
            columns = (columns + 2).coerceIn(12, 702),
            rows = (rows + 20).coerceIn(40, 100_000)
        )
    }.ifEmpty { listOf(SheetState("Feuille1")) }
}

/** Enregistre un classeur importé et renvoie son identifiant. */
fun saveImportedWorkbook(
    storage: DocumentStorage,
    workbook: Workbook,
    name: String
): String {
    val sheets = decodeWorkbook(workbook)
    val id = storage.newId()
    storage.save(id, name, DocType.SHEET, WorkbookOps.encode(sheets, 0))
    return id
}
