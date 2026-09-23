package com.docssuite.spreadsheet

import com.docssuite.core.FormulaEngine
import org.json.JSONArray
import org.json.JSONObject

/** Une feuille du classeur, telle que l'éditeur la garde en mémoire. */
internal data class SheetState(
    val name: String,
    val cells: Map<String, String> = emptyMap(),
    val formats: Map<String, CellFormat> = emptyMap(),
    val columns: Int = 12,
    val rows: Int = 40,
    val charts: List<EmbeddedChart> = emptyList(),
    /** La ligne 1 reste visible quand on fait défiler. */
    val freezeRow: Boolean = false,
    /** La colonne A reste visible quand on fait défiler. */
    val freezeColumn: Boolean = false
)

/**
 * Opérations sur l'ensemble des feuilles : noms, renommage (qui réécrit les
 * formules qui visaient l'ancien nom) et table des cellules donnée au moteur
 * de calcul.
 */
internal object WorkbookOps {

    /**
     * Cellules vues par le moteur depuis la feuille [active] : les siennes en
     * clé courte (`A1`), et celles de toutes les feuilles en clé qualifiée
     * (`VENTES!A1`) pour les formules qui vont chercher ailleurs.
     */
    fun engineCells(sheets: List<SheetState>, active: Int): Map<String, String> {
        val out = HashMap<String, String>()
        sheets.forEach { sheet ->
            sheet.cells.forEach { (ref, value) -> out[FormulaEngine.qualifiedKey(sheet.name, ref)] = value }
        }
        sheets.getOrNull(active)?.cells?.let { out.putAll(it) }
        return out
    }

    fun uniqueName(sheets: List<SheetState>, base: String = "Feuille"): String {
        val taken = sheets.map { it.name.lowercase() }.toSet()
        var n = sheets.size + 1
        while ("$base$n".lowercase() in taken) n++
        return "$base$n"
    }

    /** Un nom est valable s'il n'est pas vide, pas déjà pris, et sans caractère interdit par Excel. */
    fun validName(sheets: List<SheetState>, index: Int, name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return "Le nom ne peut pas être vide."
        if (clean.length > 31) return "31 caractères au plus, comme dans Excel."
        if (clean.any { it in "[]:*?/\\'" }) return "Les caractères [ ] : * ? / \\ et ' ne sont pas permis."
        if (sheets.withIndex().any { (i, s) -> i != index && s.name.equals(clean, ignoreCase = true) }) {
            return "Une autre feuille porte déjà ce nom."
        }
        return null
    }

    /** La forme sous laquelle une formule désigne la feuille. */
    fun reference(name: String): String =
        if (name.all { it.isLetterOrDigit() || it == '_' }) name else "'${name.replace("'", "''")}'"

    /** Remplace `Ancien!` et `'Ancien nom'!` par le nouveau nom, hors des textes entre guillemets. */
    fun renameInFormula(value: String, old: String, new: String): String {
        if (!value.startsWith("=")) return value
        val pattern = Regex(
            "'" + Regex.escape(old.replace("'", "''")) + "'!|(?<![A-Za-zÀ-ÿ0-9_.])" + Regex.escape(old) + "!",
            RegexOption.IGNORE_CASE
        )
        val out = StringBuilder()
        var quoted = false
        var start = 0
        fun flush(end: Int) {
            val segment = value.substring(start, end)
            out.append(if (quoted) segment else pattern.replace(segment) { reference(new) + "!" })
            start = end
        }
        value.forEachIndexed { i, c ->
            if (c == '"') {
                flush(if (quoted) i + 1 else i)
                quoted = !quoted
            }
        }
        flush(value.length)
        return out.toString()
    }

    fun rename(sheets: List<SheetState>, index: Int, name: String): List<SheetState> {
        val old = sheets.getOrNull(index)?.name ?: return sheets
        return sheets.mapIndexed { i, sheet ->
            val cells = sheet.cells.mapValues { (_, value) -> renameInFormula(value, old, name) }
            if (i == index) sheet.copy(name = name, cells = cells) else sheet.copy(cells = cells)
        }
    }

    // ------------------------------------------------------------ enregistrement

    fun encode(sheets: List<SheetState>, active: Int): String = JSONObject().apply {
        put("v", 2)
        put("active", active)
        put("sheets", JSONArray().apply {
            sheets.forEach { sheet ->
                put(JSONObject(encodeSheet(sheet.cells, sheet.formats, sheet.columns, sheet.rows, sheet.charts)).apply {
                    put("name", sheet.name)
                    put("freezeRow", sheet.freezeRow)
                    put("freezeColumn", sheet.freezeColumn)
                })
            }
        })
    }.toString()

    /** Relit un classeur ; ceux d'avant les feuilles multiples n'en ont qu'une. */
    fun decode(payload: String): Pair<List<SheetState>, Int> {
        val root = JSONObject(payload)
        val array = root.optJSONArray("sheets")
        if (array == null) {
            val single = decodeSheet(payload)
            return listOf(
                SheetState("Feuille1", single.cells, single.formats, single.columns, single.rows, single.charts)
            ) to 0
        }
        val sheets = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val decoded = decodeSheet(o.toString())
            SheetState(
                name = o.optString("name").ifBlank { "Feuille${i + 1}" },
                cells = decoded.cells,
                formats = decoded.formats,
                columns = decoded.columns,
                rows = decoded.rows,
                charts = decoded.charts,
                freezeRow = o.optBoolean("freezeRow"),
                freezeColumn = o.optBoolean("freezeColumn")
            )
        }.ifEmpty { listOf(SheetState("Feuille1")) }
        return sheets to root.optInt("active", 0).coerceIn(0, sheets.lastIndex)
    }
}
