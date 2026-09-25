package com.docssuite.mailmerge

import com.docssuite.core.DocumentSearch
import com.docssuite.core.FormulaEngine
import com.docssuite.fileformats.CellRef
import com.docssuite.fileformats.Sheet
import com.docssuite.fileformats.TextDocument
import com.docssuite.fileformats.TextParagraph
import com.docssuite.fileformats.TextTable
import java.util.Date

/**
 * Un champ du modèle s'écrit `{{Nom}}`. Les espaces autour du nom sont
 * tolérés : `{{ Nom }}` est le même champ.
 */
internal val fieldPattern = Regex("\\{\\{\\s*([^{}\\n]{1,60}?)\\s*}}")

/** Nom de champ « replié » : `{{nom}}` retrouve la colonne « Nom ». */
internal fun foldKey(name: String): String = DocumentSearch.fold(name.trim()).text

/**
 * Une ligne de données : ce qui remplace les champs dans un courrier.
 * Une colonne absente du tableau et un champ vide sont deux choses
 * différentes — voir [value].
 */
class Record internal constructor(private val byKey: Map<String, String>) {

    /** La valeur, ou `null` si le tableau n'a pas cette colonne du tout. */
    fun value(field: String): String? = byKey[foldKey(field)]

    fun has(field: String): Boolean = value(field) != null

    /** Ce qui s'affiche dans l'aperçu de la liste des destinataires. */
    internal fun firstFilled(fields: List<String>): String =
        fields.firstNotNullOfOrNull { value(it)?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: ""
}

/** Les destinataires lus dans une feuille de calcul. */
class Recipients(val headers: List<String>, val records: List<Record>) {
    val isEmpty: Boolean get() = records.isEmpty() || headers.isEmpty()

    companion object {
        val empty = Recipients(emptyList(), emptyList())
    }
}

/**
 * Lecture des destinataires : la première ligne remplie donne les noms de
 * colonnes, les suivantes les enregistrements. Les formules sont lues à leur
 * valeur affichée — une colonne « Total » calculée arrive donc dans le
 * courrier, pas sa formule.
 */
object MergeData {

    fun read(sheet: Sheet): Recipients {
        val grid = grid(sheet)
        val headerRow = grid.keys.minOrNull() ?: return Recipients.empty
        val headers = ArrayList<String>()
        val columns = ArrayList<Int>()
        grid[headerRow].orEmpty().toSortedMap().forEach { (column, value) ->
            val name = value.trim()
            // Une colonne sans titre ne peut pas être visée par un champ ;
            // un titre en double ne remplacerait que la première colonne.
            if (name.isNotEmpty() && headers.none { foldKey(it) == foldKey(name) }) {
                headers.add(name)
                columns.add(column)
            }
        }
        if (headers.isEmpty()) return Recipients.empty

        val records = grid.keys.filter { it > headerRow }.sorted().mapNotNull { row ->
            val cells = grid[row].orEmpty()
            val values = headers.indices.associate { i -> foldKey(headers[i]) to cells[columns[i]].orEmpty().trim() }
            // Une ligne entièrement vide n'est pas un destinataire.
            if (values.values.all { it.isEmpty() }) null else Record(values)
        }
        return Recipients(headers, records)
    }

    /** `ligne → (colonne → valeur affichée)`, lignes et colonnes vides absentes. */
    private fun grid(sheet: Sheet): Map<Int, Map<Int, String>> {
        val out = HashMap<Int, HashMap<Int, String>>()
        sheet.cells.forEach { (ref, raw) ->
            if (raw.isBlank()) return@forEach
            val at = CellRef.parse(ref) ?: return@forEach
            val shown = if (raw.trimStart().startsWith("=")) {
                FormulaEngine.displayValue(ref, sheet.cells)
            } else {
                raw
            }
            if (shown.isNotBlank()) out.getOrPut(at.first) { HashMap() }[at.second] = shown
        }
        return out
    }
}

/**
 * Ce que le modèle attend et ce que les données savent fournir. Un champ
 * inconnu des colonnes reste visible dans le courrier produit, sous sa forme
 * `{{Nom}}` : mieux vaut un trou signalé qu'un trou silencieux.
 */
class MergePlan(val fields: List<String>, val headers: List<String>) {

    val unknown: List<String> = fields.filter { field ->
        headers.none { foldKey(it) == foldKey(field) } && foldKey(field) !in builtIns
    }

    /** Colonnes fournies que le modèle n'utilise pas : souvent un champ oublié. */
    val unused: List<String> = headers.filter { header ->
        fields.none { foldKey(it) == foldKey(header) }
    }

    val ready: Boolean get() = fields.isNotEmpty() && unknown.isEmpty()

    private companion object {
        val builtIns = setOf("date")
    }
}

/** Les champs du modèle, dans l'ordre où ils apparaissent, sans doublon. */
object MergeFields {

    fun of(document: TextDocument): List<String> {
        val found = LinkedHashMap<String, String>()
        fun scan(text: String) {
            fieldPattern.findAll(text).forEach { match ->
                val name = match.groupValues[1].trim()
                if (name.isNotEmpty()) found.putIfAbsent(foldKey(name), name)
            }
        }
        document.blocks.forEach { block ->
            when (block) {
                is TextParagraph -> scan(block.plainText)
                is TextTable -> block.rows.forEach { row ->
                    row.cells.forEach { cell -> cell.paragraphs.forEach { scan(it.plainText) } }
                }
            }
        }
        scan(document.title)
        return found.values.toList()
    }
}

/**
 * [nameField] nomme les documents produits : « Lettre — Dupont » plutôt que
 * « Lettre — 3 ». [dropEmptied] supprime les lignes qui n'avaient qu'un champ
 * resté vide, pour qu'une adresse sans complément ne laisse pas de trou.
 */
data class MergeOptions(
    val nameField: String? = null,
    val dropEmptied: Boolean = true,
    val today: Date = Date()
)

/** Un courrier produit, prêt à être enregistré. */
data class MergedLetter(val document: TextDocument, val name: String)
