package com.docssuite.mailmerge

import com.docssuite.fileformats.Sheet
import org.json.JSONObject

/**
 * Les feuilles d'un tableur enregistré. Seules les cases comptent ici : la
 * mise en forme n'a rien à apporter à un publipostage. Les deux dispositions
 * de l'enregistrement sont acceptées, celle à plusieurs feuilles et
 * l'ancienne, à feuille unique.
 */
internal object SavedWorkbook {

    fun sheets(payload: String, fallbackName: String): List<Sheet> = runCatching {
        val root = JSONObject(payload)
        val array = root.optJSONArray("sheets")
            ?: return@runCatching listOf(sheet(root, fallbackName))
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { sheet(it, it.optString("name").ifBlank { "Feuille ${i + 1}" }) }
        }
    }.getOrDefault(emptyList())

    private fun sheet(node: JSONObject, name: String): Sheet {
        val cells = HashMap<String, String>()
        node.optJSONObject("cells")?.let { json ->
            json.keys().forEach { key -> cells[key] = json.optString(key) }
        }
        return Sheet(name.ifBlank { "Feuille1" }, cells)
    }
}
