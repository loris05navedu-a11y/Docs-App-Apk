package com.docssuite.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class DocType { TEXT, SHEET, DECK }

data class DocMeta(
    val id: String,
    val name: String,
    val type: DocType,
    val updatedAt: Long
)

/**
 * Sauvegarde locale simple (SharedPreferences + JSON) : un index de documents
 * et une entrée par document. Suffisant tant qu'on ne gère pas de gros fichiers.
 */
class DocumentStorage(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("docssuite_documents", Context.MODE_PRIVATE)

    fun newId(): String = "${System.currentTimeMillis()}-${(1000..9999).random()}"

    fun list(type: DocType? = null): List<DocMeta> {
        val array = JSONArray(prefs.getString(KEY_INDEX, "[]"))
        val out = ArrayList<DocMeta>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val docType = runCatching { DocType.valueOf(o.getString("type")) }.getOrNull() ?: continue
            if (type != null && docType != type) continue
            out.add(DocMeta(o.getString("id"), o.getString("name"), docType, o.optLong("updatedAt")))
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun save(id: String, name: String, type: DocType, payload: String) {
        prefs.edit().putString(KEY_DOC + id, payload).apply()
        val array = JSONArray(prefs.getString(KEY_INDEX, "[]"))
        val updated = JSONArray()
        var replaced = false
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.getString("id") == id) {
                updated.put(metaJson(id, name, type))
                replaced = true
            } else {
                updated.put(o)
            }
        }
        if (!replaced) updated.put(metaJson(id, name, type))
        prefs.edit().putString(KEY_INDEX, updated.toString()).apply()
    }

    fun load(id: String): String? = prefs.getString(KEY_DOC + id, null)

    fun delete(id: String) {
        val array = JSONArray(prefs.getString(KEY_INDEX, "[]"))
        val updated = JSONArray()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.getString("id") != id) updated.put(o)
        }
        prefs.edit().remove(KEY_DOC + id).putString(KEY_INDEX, updated.toString()).apply()
    }

    private fun metaJson(id: String, name: String, type: DocType) = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("updatedAt", System.currentTimeMillis())
    }

    private companion object {
        const val KEY_INDEX = "index"
        const val KEY_DOC = "doc_"
    }
}
