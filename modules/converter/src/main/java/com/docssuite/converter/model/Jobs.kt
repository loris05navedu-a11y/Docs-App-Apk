package com.docssuite.converter.model

import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * Fichier choisi par l'utilisateur, déjà recopié dans le cache : les API
 * Android de décodage réclament un descripteur de fichier, pas une `content://`.
 */
data class SourceFile(
    val uri: Uri,
    val displayName: String,
    val extension: String,
    val sizeBytes: Long,
    val kind: FileKind,
    val cached: File,
    val location: String? = null,
    /** Cibles réellement praticables, vérifiées sur le fichier ouvert. */
    val targets: List<TargetFormat> = emptyList(),
    /** Détail lisible : « 1920×1080 », « 3 min 42 s », « 12 pages »… */
    val detail: String? = null
) {
    val baseName: String
        get() = displayName.substringBeforeLast('.', displayName)
}

/** Progression d'un fichier dans la file. */
sealed interface ItemState {
    data object Waiting : ItemState
    data class Running(val fraction: Float) : ItemState
    data class Done(val output: OutputFile) : ItemState
    data class Failed(val message: String) : ItemState
    data object Cancelled : ItemState
}

data class QueueItem(val source: SourceFile, val state: ItemState = ItemState.Waiting)

/** Fichier produit, rangé dans le stockage privé de l'application. */
data class OutputFile(
    val file: File,
    val displayName: String,
    val format: TargetFormat,
    val sizeBytes: Long
)

data class HistoryEntry(
    val id: String,
    val sourceName: String,
    val sourceExtension: String,
    val targetExtension: String,
    val sourceSize: Long,
    val outputSize: Long,
    val timestamp: Long,
    val outputPath: String?,
    val succeeded: Boolean,
    val message: String? = null
) {
    /** Le fichier produit peut avoir été effacé depuis : on le vérifie avant de proposer de l'ouvrir. */
    fun outputFile(): File? = outputPath?.let(::File)?.takeIf { it.exists() }
}

/** Taille lisible, avec l'espace insécable et la virgule décimale du français. */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes o"
    val units = listOf("ko", "Mo", "Go", "To")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val decimals = if (value >= 100) 0 else 1
    return String.format(Locale.FRANCE, "%.${decimals}f %s", value, units[unit])
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        String.format(Locale.FRANCE, "%d h %02d min", minutes / 60, minutes % 60)
    } else {
        String.format(Locale.FRANCE, "%d min %02d s", minutes, seconds)
    }
}
