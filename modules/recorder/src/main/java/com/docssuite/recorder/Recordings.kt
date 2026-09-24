package com.docssuite.recorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.log10

/** Un enregistrement terminé. */
data class Recording(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    /** Repères posés pendant l'enregistrement, en millisecondes depuis le début. */
    val markers: List<Long>,
    val file: File
)

/**
 * Les enregistrements : un fichier audio `.m4a` par enregistrement, et à
 * côté un petit `.json` (nom, durée, repères). Ce sont des fichiers de
 * l'app, on peut les partager comme n'importe quel son.
 */
class RecordingStore(context: Context) {

    val directory = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newId(): String = UUID.randomUUID().toString()

    fun audioFile(id: String) = File(directory, "$id.m4a")
    private fun infoFile(id: String) = File(directory, "$id.json")

    fun list(): List<Recording> = directory.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { read(it.nameWithoutExtension) }
        .sortedByDescending { it.createdAt }

    fun read(id: String): Recording? = runCatching {
        val o = JSONObject(infoFile(id).readText())
        val markers = o.optJSONArray("markers") ?: JSONArray()
        Recording(
            id = id,
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            markers = (0 until markers.length()).map { markers.getLong(it) },
            file = audioFile(id)
        ).takeIf { it.file.exists() }
    }.getOrNull()

    fun save(recording: Recording) {
        val o = JSONObject()
            .put("name", recording.name)
            .put("createdAt", recording.createdAt)
            .put("durationMs", recording.durationMs)
            .put("markers", JSONArray(recording.markers))
        infoFile(recording.id).writeText(o.toString())
    }

    fun rename(id: String, name: String) {
        val current = read(id) ?: return
        save(current.copy(name = name.trim().ifBlank { current.name }))
    }

    fun delete(id: String) {
        audioFile(id).delete()
        infoFile(id).delete()
    }
}

/**
 * Durée écoulée d'un enregistrement qu'on peut mettre en pause : seul le
 * temps réellement enregistré compte, pour que les repères tombent au bon
 * endroit du fichier.
 */
class RecordingClock(private val now: () -> Long) {
    private var accumulated = 0L
    private var runningSince: Long? = null

    val isRunning: Boolean get() = runningSince != null

    fun start() {
        if (runningSince == null) runningSince = now()
    }

    fun pause() {
        runningSince?.let { accumulated += now() - it }
        runningSince = null
    }

    fun elapsed(): Long = accumulated + (runningSince?.let { now() - it } ?: 0L)
}

/**
 * Niveau affiché, de 0 à 1, à partir de l'amplitude du micro (0 à 32767) :
 * échelle en décibels, sinon une voix normale n'allumerait qu'un trait.
 */
fun levelOf(amplitude: Int): Float {
    if (amplitude <= 0) return 0f
    val db = 20 * log10(amplitude / 32767.0) // de -90 dB environ à 0 dB
    return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
}

/** 3 725 000 ms → « 1:02:05 », 65 000 → « 1:05 ». */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
