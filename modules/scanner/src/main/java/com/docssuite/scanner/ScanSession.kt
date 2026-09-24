package com.docssuite.scanner

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Une page du scan en cours : la photo d'origine, les réglages choisis, et
 * la page finale déjà calculée (JPEG) qui servira à l'export.
 */
data class ScanPage(
    val id: String,
    val source: File,
    /** Dimensions de la photo telle que décodée par [ScanImaging.decodeSource] : c'est le repère de [quad]. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val quad: Quad,
    val quarterTurns: Int,
    val filter: ScanFilter,
    val rendered: File,
    /** Change à chaque nouveau rendu, pour que les vignettes se rechargent. */
    val version: Int = 0
)

/**
 * Le scan en cours est écrit sur disque à chaque changement : l'appareil
 * photo est une autre application, et Android peut fermer la nôtre pendant
 * qu'on cadre la feuille. Au retour, les pages déjà prises sont toujours là.
 */
class ScanSession(val directory: File) {

    private val file get() = File(directory, "session.json")

    fun save(pages: List<ScanPage>) {
        directory.mkdirs()
        val array = JSONArray()
        pages.forEach { page ->
            array.put(
                JSONObject()
                    .put("id", page.id)
                    .put("source", page.source.name)
                    .put("sourceWidth", page.sourceWidth)
                    .put("sourceHeight", page.sourceHeight)
                    .put("quad", JSONArray().apply { page.quad.toFloatArray().forEach { put(it.toDouble()) } })
                    .put("quarterTurns", page.quarterTurns)
                    .put("filter", page.filter.name)
                    .put("rendered", page.rendered.name)
                    .put("version", page.version)
            )
        }
        // Écriture puis renommage : une coupure en plein milieu ne laisse
        // jamais un fichier à moitié écrit.
        val temp = File(directory, "session.json.tmp")
        temp.writeText(JSONObject().put("pages", array).toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    /** Les pages enregistrées dont les fichiers existent encore ; une page orpheline est ignorée. */
    fun load(): List<ScanPage> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONObject(text).getJSONArray("pages") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val o = array.getJSONObject(index)
                val q = o.getJSONArray("quad")
                val c = FloatArray(8) { q.getDouble(it).toFloat() }
                val page = ScanPage(
                    id = o.getString("id"),
                    source = File(directory, o.getString("source")),
                    sourceWidth = o.getInt("sourceWidth"),
                    sourceHeight = o.getInt("sourceHeight"),
                    quad = Quad(Pt(c[0], c[1]), Pt(c[2], c[3]), Pt(c[4], c[5]), Pt(c[6], c[7])),
                    quarterTurns = o.getInt("quarterTurns"),
                    filter = ScanFilter.valueOf(o.getString("filter")),
                    rendered = File(directory, o.getString("rendered")),
                    version = o.optInt("version")
                )
                page.takeIf { it.source.exists() && it.rendered.exists() }
            }.getOrNull()
        }
    }

    /** Efface tout : photos, pages et état. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }
}
