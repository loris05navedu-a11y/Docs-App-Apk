package com.docssuite.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Formats lus par le lecteur. Le décodage passe par les codecs d'Android, donc
 * la liste réelle dépend de l'appareil : ces types sont ceux que la plateforme
 * garantit, et le sélecteur de fichiers accepte de toute façon tout média.
 */
object MediaFormats {

    val videoExtensions = listOf("mp4", "m4v", "mkv", "webm", "3gp", "3gpp", "ts", "mov", "avi")

    val audioExtensions = listOf(
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "mid", "midi", "amr", "mka"
    )

    /** Types proposés au sélecteur de fichiers. */
    val pickerMimeTypes = arrayOf("video/*", "audio/*")

    fun isVideo(name: String): Boolean = extensionOf(name) in videoExtensions

    fun isAudio(name: String): Boolean = extensionOf(name) in audioExtensions

    fun isSupported(name: String): Boolean = isVideo(name) || isAudio(name)

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    fun mimeFor(name: String): String = when {
        isVideo(name) -> "video/*"
        isAudio(name) -> "audio/*"
        else -> "*/*"
    }
}

/** Ce qu'on sait afficher d'une piste avant même de la lire. */
data class TrackInfo(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Int,
    val hasVideo: Boolean,
    val artwork: ByteArray?
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Lit les métadonnées d'un média. À appeler hors du fil principal : le
 * `MediaMetadataRetriever` ouvre et analyse réellement le fichier.
 */
fun readTrackInfo(context: Context, uri: Uri, fallbackTitle: String): TrackInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        fun get(key: Int) = runCatching { retriever.extractMetadata(key) }.getOrNull()
            ?.takeIf { it.isNotBlank() }

        TrackInfo(
            title = get(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallbackTitle,
            artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            durationMs = get(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0,
            hasVideo = get(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
            artwork = runCatching { retriever.embeddedPicture }.getOrNull()
        )
    } catch (_: Exception) {
        // Un fichier illisible ne doit pas empêcher de l'ajouter à la liste :
        // le lecteur signalera l'erreur au moment de la lecture.
        TrackInfo(fallbackTitle, null, null, 0, MediaFormats.isVideo(fallbackTitle), null)
    } finally {
        runCatching { retriever.release() }
    }
}

/** `754000` → `12:34`, avec les heures seulement si nécessaire. */
fun formatDuration(milliseconds: Int): String {
    if (milliseconds <= 0) return "0:00"
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
