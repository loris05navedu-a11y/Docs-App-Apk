package com.docssuite.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Le texte lisible d'un document enregistré, quel que soit son type, pour
 * la recherche. Seul le contenu compte : styles, couleurs et positions sont
 * ignorés. Un document illisible donne un texte vide plutôt qu'une erreur.
 */
object DocumentText {

    fun extract(type: DocType, payload: String): String = runCatching {
        when (type) {
            DocType.TEXT -> text(payload)
            DocType.SHEET -> sheet(payload)
            DocType.DECK -> deck(payload)
        }
    }.getOrDefault("")

    /** Documents : chaque champ `text`, paragraphes et cellules de tableau compris. */
    private fun text(payload: String): String {
        val parts = ArrayList<String>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "text" && child is String) parts.add(child) else walk(child)
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(JSONObject(payload))
        return parts.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Tableurs : nom de chaque feuille puis ses cellules, rangée par rangée. */
    private fun sheet(payload: String): String {
        val root = JSONObject(payload)
        val sheets = root.optJSONArray("sheets")
        val parts = ArrayList<String>()
        fun cells(sheet: JSONObject) {
            sheet.optString("name").takeIf { it.isNotBlank() }?.let(parts::add)
            val cells = sheet.optJSONObject("cells") ?: return
            cells.keys().asSequence()
                .mapNotNull { key -> cellPosition(key)?.let { it to cells.optString(key) } }
                .sortedWith(compareBy({ it.first.first }, { it.first.second }))
                .map { it.second }
                .filter { it.isNotBlank() }
                .forEach(parts::add)
            sheet.optJSONArray("charts")?.let { charts ->
                for (i in 0 until charts.length()) charts.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        if (sheets != null) {
            for (i in 0 until sheets.length()) sheets.optJSONObject(i)?.let(::cells)
        } else {
            cells(root)
        }
        return parts.joinToString("\n")
    }

    /** « B12 » → (rangée 11, colonne 1). */
    private fun cellPosition(key: String): Pair<Int, Int>? {
        val match = Regex("^([A-Za-z]+)(\\d+)$").find(key) ?: return null
        var column = 0
        match.groupValues[1].uppercase().forEach { column = column * 26 + (it - 'A' + 1) }
        return (match.groupValues[2].toInt() - 1) to (column - 1)
    }

    /** Présentations : titre, contenus et notes de chaque diapositive, puis le pied de page. */
    private fun deck(payload: String): String {
        val trimmed = payload.trimStart()
        val root = if (trimmed.startsWith("[")) JSONObject().put("slides", JSONArray(trimmed)) else JSONObject(trimmed)
        val parts = ArrayList<String>()
        val slides = root.optJSONArray("slides") ?: JSONArray()
        for (i in 0 until slides.length()) {
            val slide = slides.optJSONObject(i) ?: continue
            listOf("title", "content", "c2", "nt").forEach { key ->
                slide.optString(key).takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        root.optString("footer").takeIf { it.isNotBlank() }?.let(parts::add)
        return parts.joinToString("\n")
    }
}
