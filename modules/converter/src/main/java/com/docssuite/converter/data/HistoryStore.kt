package com.docssuite.converter.data

import android.content.Context
import com.docssuite.converter.model.HistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Historique des conversions, enregistré en JSON dans le stockage privé.
 *
 * L'historique décrit les conversions passées ; il ne conserve pas de copie des
 * fichiers. Le résultat référencé peut donc avoir disparu, ce que chaque entrée
 * vérifie avant de proposer de l'ouvrir.
 */
class HistoryStore(private val context: Context) {

    private val file: File
        get() = File(context.filesDir, "conversion-history.json")

    fun list(): List<HistoryEntry> = read().sortedByDescending { it.timestamp }

    fun record(entry: HistoryEntry) {
        val entries = read().toMutableList()
        entries += entry
        write(entries.takeLast(MAX_ENTRIES))
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun delete(id: String) {
        val entries = read().toMutableList()
        val removed = entries.firstOrNull { it.id == id }
        entries.removeAll { it.id == id }
        removed?.outputFile()?.delete()
        write(entries)
    }

    fun clear() {
        read().forEach { it.outputFile()?.delete() }
        write(emptyList())
    }

    /** Efface les entrées plus anciennes que la durée choisie, et leurs fichiers. */
    fun prune(retention: HistoryRetention) {
        if (retention.days == 0) return
        val limit = System.currentTimeMillis() - retention.days * 24L * 60 * 60 * 1000
        val entries = read()
        val (kept, expired) = entries.partition { it.timestamp >= limit }
        if (expired.isEmpty()) return
        expired.forEach { it.outputFile()?.delete() }
        write(kept)
    }

    private fun read(): List<HistoryEntry> {
        val source = file
        if (!source.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(source.readText())
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                HistoryEntry(
                    id = json.optString("id"),
                    sourceName = json.optString("sourceName"),
                    sourceExtension = json.optString("sourceExtension"),
                    targetExtension = json.optString("targetExtension"),
                    sourceSize = json.optLong("sourceSize"),
                    outputSize = json.optLong("outputSize"),
                    timestamp = json.optLong("timestamp"),
                    outputPath = json.optString("outputPath").takeIf { it.isNotBlank() },
                    succeeded = json.optBoolean("succeeded", true),
                    message = json.optString("message").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<HistoryEntry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("sourceName", entry.sourceName)
                        put("sourceExtension", entry.sourceExtension)
                        put("targetExtension", entry.targetExtension)
                        put("sourceSize", entry.sourceSize)
                        put("outputSize", entry.outputSize)
                        put("timestamp", entry.timestamp)
                        put("outputPath", entry.outputPath ?: "")
                        put("succeeded", entry.succeeded)
                        put("message", entry.message ?: "")
                    }
                )
            }
            file.writeText(array.toString())
        }
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
