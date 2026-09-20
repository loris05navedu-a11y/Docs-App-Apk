package com.docssuite.converter.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import com.docssuite.converter.model.ConversionOptions
import java.io.File
import java.nio.ByteBuffer

private val MP4_VIDEO_MIMES = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp")
private val MP4_AUDIO_MIMES = setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")

/**
 * Conversion vers MP4 par remultiplexage : les images sont recopiées telles
 * quelles, sans réencodage. La vidéo garde donc exactement sa qualité d'origine
 * et l'opération est rapide. Quand la piste son n'est pas transportable dans un
 * MP4 (Vorbis ou Opus d'un WebM, par exemple), elle seule est réencodée en AAC.
 */
object VideoConversions {

    fun toMp4(
        source: File,
        destination: File,
        options: ConversionOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val tracks = MediaProbe.readTracks(source)
            ?: throw ConversionException("Ce fichier vidéo n'est pas lisible par l'appareil")
        val videoMime = tracks.videoMime
            ?: throw ConversionException("Ce fichier ne contient pas de piste vidéo")
        if (videoMime !in MP4_VIDEO_MIMES) {
            throw ConversionException(
                "La vidéo est encodée dans un format qu'un MP4 ne peut pas contenir"
            )
        }

        val audioNeedsTranscode = tracks.audioMime != null && tracks.audioMime !in MP4_AUDIO_MIMES
        var transcodedAudio: File? = null
        try {
            if (audioNeedsTranscode) {
                val temporary = File(destination.parentFile, "${destination.nameWithoutExtension}-audio.m4a")
                AudioConversions.toM4a(
                    source = source,
                    destination = temporary,
                    options = options,
                    onProgress = { onProgress(it * 0.6f) },
                    isCancelled = isCancelled
                )
                transcodedAudio = temporary
            }
            val from = if (audioNeedsTranscode) 0.6f else 0f
            mux(
                source = source,
                audioSource = transcodedAudio,
                copyAudioFromSource = tracks.audioMime != null && !audioNeedsTranscode,
                destination = destination,
                onProgress = { onProgress(from + it * (1f - from)) },
                isCancelled = isCancelled
            )
        } finally {
            transcodedAudio?.delete()
        }
        onProgress(1f)
    }

    private fun mux(
        source: File,
        audioSource: File?,
        copyAudioFromSource: Boolean,
        destination: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = if (audioSource != null) MediaExtractor() else null
        var videoDescriptor: ParcelFileDescriptor? = null
        var audioDescriptor: ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoDescriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            videoExtractor.setDataSource(videoDescriptor.fileDescriptor)

            val videoTrack = videoExtractor.findTrack("video/")
                ?: throw ConversionException("Piste vidéo introuvable")
            val audioTrackInSource =
                if (copyAudioFromSource) videoExtractor.findTrack("audio/") else null

            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            rotationOf(source)?.let { muxer.setOrientationHint(it) }

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val videoOut = muxer.addTrack(videoFormat)

            var audioOut = -1
            var audioTrackInTemp = -1
            if (audioSource != null && audioExtractor != null) {
                audioDescriptor =
                    ParcelFileDescriptor.open(audioSource, ParcelFileDescriptor.MODE_READ_ONLY)
                audioExtractor.setDataSource(audioDescriptor.fileDescriptor)
                audioTrackInTemp = audioExtractor.findTrack("audio/")
                    ?: throw ConversionException("La piste audio réencodée est illisible")
                audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackInTemp))
            } else if (audioTrackInSource != null) {
                audioOut = muxer.addTrack(videoExtractor.getTrackFormat(audioTrackInSource))
            }

            muxer.start()
            started = true

            val durationUs = videoFormat.longOr(MediaFormat.KEY_DURATION, 0L)
            copyTrack(videoExtractor, videoTrack, muxer, videoOut, durationUs, onProgress, isCancelled)

            if (audioOut >= 0) {
                if (audioSource != null && audioExtractor != null) {
                    copyTrack(audioExtractor, audioTrackInTemp, muxer, audioOut, 0L, {}, isCancelled)
                } else if (audioTrackInSource != null) {
                    copyTrack(videoExtractor, audioTrackInSource, muxer, audioOut, 0L, {}, isCancelled)
                }
            }
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
            runCatching { videoDescriptor?.close() }
            runCatching { audioDescriptor?.close() }
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        durationUs: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val format = extractor.getTrackFormat(trackIndex)
        val capacity = format.integerOr(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20).coerceAtLeast(64 * 1024)
        val buffer = ByteBuffer.allocate(capacity)
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (isCancelled()) throw ConversionCancelled()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            muxer.writeSampleData(outputTrack, buffer, info)
            if (durationUs > 0) {
                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
            }
            extractor.advance()
        }
        extractor.unselectTrack(trackIndex)
    }

    /** Sans cette indication, une vidéo filmée en portrait ressort couchée. */
    private fun rotationOf(source: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
        } catch (error: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private fun MediaExtractor.findTrack(prefix: String): Int? =
    (0 until trackCount).firstOrNull { index ->
        getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
    }

private fun MediaFormat.longOr(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback
