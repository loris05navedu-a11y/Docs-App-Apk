package com.docssuite.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Folder(val id: String, val name: String)

/**
 * Le rangement des documents : dossiers et favoris. Gardé à part du
 * contenu : ranger un document ne le modifie pas, et sa date de
 * modification ne bouge pas.
 */
class DocumentLibrary(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("docssuite_library", Context.MODE_PRIVATE)

    private fun read(): JSONObject = runCatching { JSONObject(prefs.getString(KEY, "{}")!!) }.getOrDefault(JSONObject())

    private fun write(root: JSONObject) {
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun folders(): List<Folder> {
        val array = read().optJSONArray("folders") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Folder(it.getString("id"), it.getString("name")) }
        }.sortedBy { DocumentSearch.fold(it.name).text }
    }

    fun createFolder(name: String): Folder {
        val folder = Folder(UUID.randomUUID().toString(), name.trim().ifBlank { "Nouveau dossier" })
        val root = read()
        val array = root.optJSONArray("folders") ?: JSONArray()
        array.put(JSONObject().put("id", folder.id).put("name", folder.name))
        write(root.put("folders", array))
        return folder
    }

    fun renameFolder(id: String, name: String) {
        val root = read()
        val array = root.optJSONArray("folders") ?: return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("id") == id) o.put("name", name.trim().ifBlank { o.optString("name") })
        }
        write(root)
    }

    /** Supprime le dossier ; ses documents restent, simplement sans dossier. */
    fun deleteFolder(id: String) {
        val root = read()
        val folders = root.optJSONArray("folders") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until folders.length()) folders.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let(kept::put)
        val placement = root.optJSONObject("placement") ?: JSONObject()
        placement.keys().asSequence().toList().forEach { doc -> if (placement.optString(doc) == id) placement.remove(doc) }
        write(root.put("folders", kept).put("placement", placement))
    }

    fun folderOf(docId: String): String? = read().optJSONObject("placement")?.optString(docId)?.takeIf { it.isNotEmpty() }

    fun moveTo(docId: String, folderId: String?) {
        val root = read()
        val placement = root.optJSONObject("placement") ?: JSONObject()
        if (folderId == null) placement.remove(docId) else placement.put(docId, folderId)
        write(root.put("placement", placement))
    }

    fun favorites(): Set<String> {
        val array = read().optJSONArray("favorites") ?: return emptySet()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    fun setFavorite(docId: String, favorite: Boolean) {
        val set = favorites().toMutableSet()
        if (favorite) set.add(docId) else set.remove(docId)
        write(read().put("favorites", JSONArray(set.sorted())))
    }

    /** À appeler quand un document est supprimé. */
    fun forget(docId: String) {
        moveTo(docId, null)
        setFavorite(docId, false)
    }

    /** Tout le rangement, pour une sauvegarde. */
    fun exportJson(): JSONObject = read()

    /**
     * Ajoute le rangement d'une sauvegarde à l'actuel : dossiers de même nom
     * fusionnés, jamais de dossier ni de favori perdu.
     */
    fun mergeJson(saved: JSONObject) {
        val existing = folders().associateBy { DocumentSearch.fold(it.name).text }
        val remap = HashMap<String, String>()
        saved.optJSONArray("folders")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                val target = existing[DocumentSearch.fold(name).text] ?: createFolder(name)
                remap[o.optString("id")] = target.id
            }
        }
        saved.optJSONObject("placement")?.let { placement ->
            placement.keys().forEach { doc ->
                remap[placement.optString(doc)]?.let { folder -> if (folderOf(doc) == null) moveTo(doc, folder) }
            }
        }
        saved.optJSONArray("favorites")?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let { setFavorite(it, true) }
        }
    }

    private companion object {
        const val KEY = "library"
    }
}
