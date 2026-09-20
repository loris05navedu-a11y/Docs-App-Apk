package com.docssuite.converter.engine

import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.docssuite.converter.model.FileKind
import com.docssuite.converter.model.TargetFormat
import com.docssuite.converter.model.candidateTargets
import com.docssuite.converter.model.formatDuration
import java.io.File
import java.util.Locale

/** Pistes d'un conteneur audio/vidéo, telles que le système les voit. */
data class MediaTracks(
    val videoMime: String? = null,
    val audioMime: String? = null,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0
)

/** Ce que `MediaMuxer` accepte de recopier tel quel dans un MP4. */
private val MP4_VIDEO_MIMES = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp")
private val MP4_AUDIO_MIMES = setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")

/**
 * Ouvre réellement le fichier pour établir ce qu'on peut en faire. Les cartes
 * de conversion n'affichent que ce que cette fonction confirme : une extension
 * ne prouve rien, un `.mkv` en VP9 ne peut pas devenir un MP4 par simple
 * remballage.
 */
object MediaProbe {

    /**
     * Toute sonde qui échoue ramène le fichier au seul traitement qui marche
     * toujours, la compression. Un imprévu sur un décodeur du système ne doit
     * jamais empêcher d'ouvrir l'écran de conversion.
     */
    fun inspect(file: File, kind: FileKind, extension: String): ProbeResult = runCatching {
        when (kind) {
            FileKind.IMAGE -> probeImage(file, extension)
            FileKind.PDF -> probePdf(file)
            FileKind.AUDIO, FileKind.VIDEO -> probeMedia(file, kind, extension)
            FileKind.TEXT -> probeText(file, extension)
            FileKind.ARCHIVE, FileKind.OTHER -> ProbeResult(listOf(TargetFormat.ZIP), null)
        }
    }.getOrElse {
        ProbeResult(
            listOf(TargetFormat.ZIP),
            null,
            "Ce fichier n'a pas pu être analysé ; il peut encore être compressé"
        )
    }

    /**
     * Lit les dimensions sans charger les pixels. Le décodeur ne se contente
     * pas toujours de rendre `null` sur un fichier abîmé : il lui arrive de
     * lever une erreur, qui ne doit pas dépasser la sonde.
     */
    private fun probeImage(file: File, extension: String): ProbeResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ProbeResult(
                listOf(TargetFormat.ZIP),
                null,
                "Cette image est abîmée ou dans un format que l'appareil ne sait pas lire"
            )
        }
        return ProbeResult(
            candidateTargets(FileKind.IMAGE, extension),
            "${bounds.outWidth} × ${bounds.outHeight} px"
        )
    }

    private fun probePdf(file: File): ProbeResult {
        val pages = runCatching { PdfConversions.pageCount(file) }.getOrElse {
            return ProbeResult(
                listOf(TargetFormat.ZIP),
                null,
                "Ce PDF est protégé ou abîmé, il ne peut pas être converti"
            )
        }
        val detail = if (pages == 1) "1 page" else "$pages pages"
        return ProbeResult(candidateTargets(FileKind.PDF, "pdf"), detail)
    }

    /**
     * Compte les lignes en lisant le fichier en flux. Le charger entièrement
     * pour un simple décompte suffirait à faire tomber l'application sur un
     * journal de plusieurs centaines de mégaoctets.
     */
    private fun probeText(file: File, extension: String): ProbeResult {
        val lines = runCatching {
            file.bufferedReader().use { reader ->
                var count = 0
                while (reader.readLine() != null && count < MAX_COUNTED_LINES) count++
                count
            }
        }.getOrNull()
        val detail = when {
            lines == null -> null
            lines >= MAX_COUNTED_LINES -> "plus de $MAX_COUNTED_LINES lignes"
            lines <= 1 -> "$lines ligne"
            else -> "$lines lignes"
        }
        return ProbeResult(candidateTargets(FileKind.TEXT, extension), detail)
    }

    private fun probeMedia(file: File, kind: FileKind, extension: String): ProbeResult {
        val tracks = readTracks(file)
            ?: return ProbeResult(
                listOf(TargetFormat.ZIP),
                null,
                "Ce fichier n'est pas lisible par l'appareil"
            )

        val targets = mutableListOf<TargetFormat>()

        // MP4 : il faut une piste vidéo que le multiplexeur sait recopier.
        val videoRemuxable = tracks.videoMime in MP4_VIDEO_MIMES
        if (videoRemuxable && extension.lowercase() != "mp4") targets += TargetFormat.MP4

        // Audio : il faut un décodeur pour la piste son.
        val audioDecodable = tracks.audioMime?.let { hasDecoder(it) } == true
        if (audioDecodable) {
            if (extension.lowercase() != "m4a") targets += TargetFormat.M4A
            if (extension.lowercase() != "wav") targets += TargetFormat.WAV
        }

        targets += TargetFormat.ZIP

        val detail = buildString {
            if (tracks.durationMs > 0) append(formatDuration(tracks.durationMs))
            if (kind == FileKind.VIDEO && tracks.width > 0) {
                if (isNotEmpty()) append(" • ")
                append("${tracks.width} × ${tracks.height}")
            }
        }.ifBlank { null }

        val warning = when {
            targets.size == 1 && tracks.audioMime == null ->
                "Ce fichier ne contient aucune piste audio convertible"
            !audioDecodable && tracks.audioMime != null ->
                "L'appareil ne sait pas décoder la piste audio de ce fichier"
            else -> null
        }
        return ProbeResult(targets, detail, warning)
    }

    /** Lit les pistes sans décoder quoi que ce soit. `null` si le conteneur est illisible. */
    fun readTracks(file: File): MediaTracks? {
        var descriptor: ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            extractor.setDataSource(descriptor.fileDescriptor)
            var video: String? = null
            var audio: String? = null
            var width = 0
            var height = 0
            var duration = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    duration = maxOf(duration, format.getLong(MediaFormat.KEY_DURATION) / 1000)
                }
                when {
                    mime.startsWith("video/") && video == null -> {
                        video = mime
                        width = format.optInt(MediaFormat.KEY_WIDTH)
                        height = format.optInt(MediaFormat.KEY_HEIGHT)
                    }
                    mime.startsWith("audio/") && audio == null -> audio = mime
                }
            }
            if (video == null && audio == null) null
            else MediaTracks(video, audio, duration, width, height)
        } catch (error: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
        }
    }

    /** Durée en millisecondes, utilisée pour la progression. 0 si inconnue. */
    fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (error: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private const val MAX_COUNTED_LINES = 200_000

    private fun hasDecoder(mime: String): Boolean = runCatching {
        val format = MediaFormat.createAudioFormat(mime, 44_100, 2)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
    }.getOrDefault(false)
}

data class ProbeResult(
    val targets: List<TargetFormat>,
    val detail: String?,
    /** Message affiché quand le fichier est reconnu mais peu ou pas convertible. */
    val warning: String? = null
)

private fun MediaFormat.optInt(key: String): Int =
    if (containsKey(key)) getInteger(key) else 0

internal fun String.titleCaseExtension(): String = uppercase(Locale.FRANCE)
