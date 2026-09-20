package com.docssuite.converter.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.docssuite.converter.model.ConversionOptions
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conversions audio. Le son est décodé en PCM par `MediaCodec` puis réécrit,
 * soit tel quel dans un WAV, soit réencodé en AAC dans un conteneur MP4 (.m4a).
 */
object AudioConversions {

    fun toWav(
        source: File,
        destination: File,
        options: ConversionOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val writer = WavSink(destination)
        PcmDecoder.decode(
            source = source,
            sink = PcmPipeline(options.sampleRate.hertz, writer),
            onProgress = onProgress,
            isCancelled = isCancelled
        )
        if (!writer.wroteAnything) {
            throw ConversionException("Aucun son n'a pu être extrait de ce fichier")
        }
        onProgress(1f)
    }

    fun toM4a(
        source: File,
        destination: File,
        options: ConversionOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val encoder = AacSink(destination, options.audioBitrate.bitsPerSecond, isCancelled)
        try {
            PcmDecoder.decode(
                source = source,
                sink = PcmPipeline(options.sampleRate.hertz, encoder),
                onProgress = { onProgress(it * 0.95f) },
                isCancelled = isCancelled
            )
        } catch (error: Throwable) {
            encoder.abort()
            throw error
        }
        if (!encoder.wroteAnything) {
            destination.delete()
            throw ConversionException("Aucun son n'a pu être extrait de ce fichier")
        }
        onProgress(1f)
    }
}

/**
 * Écrit un WAV PCM 16 bits. L'en-tête contient deux tailles inconnues tant que
 * le flux n'est pas terminé : on réserve la place puis on la complète à la fin.
 */
private class WavSink(private val destination: File) : PcmSink {

    private var output = destination.outputStream().buffered()
    private var dataBytes = 0L
    private var sampleRate = 44_100
    private var channels = 2
    private var headerReserved = false
    var wroteAnything = false
        private set

    // Le décodeur peut annoncer son format plusieurs fois ; la place de
    // l'en-tête ne doit être réservée qu'au tout début du fichier.
    override fun onFormat(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        if (headerReserved) return
        headerReserved = true
        output.write(ByteArray(44))
    }

    override fun onFrames(samples: ShortArray, count: Int) {
        if (count == 0) return
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) bytes.putShort(samples[index])
        output.write(bytes.array())
        dataBytes += count * 2L
        wroteAnything = true
    }

    override fun onFinished() {
        output.flush()
        output.close()
        RandomAccessFile(destination, "rw").use { file ->
            file.seek(0)
            file.write(header(dataBytes))
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)                       // PCM non compressé
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }
}

/** Encode le PCM en AAC et l'empaquette dans un conteneur MP4. */
private class AacSink(
    private val destination: File,
    private val bitrate: Int,
    private val isCancelled: () -> Boolean
) : PcmSink {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var framesSubmitted = 0L
    private var sampleRate = 44_100
    private var channels = 2
    private val info = MediaCodec.BufferInfo()
    var wroteAnything = false
        private set

    override fun onFormat(sampleRate: Int, channels: Int) {
        if (encoder != null) return
        this.sampleRate = sampleRate
        this.channels = channels.coerceIn(1, 2)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, this.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (error: Exception) {
            throw ConversionException("Cet appareil ne propose pas d'encodeur AAC")
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        encoder = codec
        muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    override fun onFrames(samples: ShortArray, count: Int) {
        val codec = encoder ?: return
        var offset = 0
        while (offset < count) {
            if (isCancelled()) throw ConversionCancelled()
            val index = codec.dequeueInputBuffer(10_000L)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val capacityShorts = buffer.capacity() / 2
            val chunk = minOf(capacityShorts, count - offset)
            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            shorts.put(samples, offset, chunk)
            val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
            codec.queueInputBuffer(index, 0, chunk * 2, presentationTimeUs, 0)
            framesSubmitted += chunk / channels
            offset += chunk
            drain(endOfStream = false)
        }
    }

    override fun onFinished() {
        val codec = encoder ?: return
        // L'encodeur peut n'avoir aucun tampon libre sur le moment : on le vide
        // entre deux tentatives, sans quoi la marque de fin ne partirait jamais
        // et l'attente du dernier paquet tournerait indéfiniment.
        var queued = false
        repeat(EOS_ATTEMPTS) {
            if (queued) return@repeat
            val index = codec.dequeueInputBuffer(20_000L)
            if (index >= 0) {
                val presentationTimeUs = framesSubmitted * 1_000_000L / sampleRate
                codec.queueInputBuffer(
                    index, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                queued = true
            } else {
                drain(endOfStream = false)
            }
        }
        if (queued) drain(endOfStream = true)
        release()
    }

    fun abort() {
        release()
        destination.delete()
    }

    private fun drain(endOfStream: Boolean) {
        val codec = encoder ?: return
        val output = muxer ?: return
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_NS
        while (true) {
            if (endOfStream && System.nanoTime() > deadline) return
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 100_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = output.addTrack(codec.outputFormat)
                        output.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    // L'en-tête de configuration décrit le codec ; il est porté
                    // par la piste du conteneur, pas par les données.
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        output.writeSampleData(trackIndex, buffer, info)
                        wroteAnything = true
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun release() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
    }

    private companion object {
        const val EOS_ATTEMPTS = 20
        const val DRAIN_TIMEOUT_NS = 10_000_000_000L
    }
}
