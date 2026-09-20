package com.docssuite.converter.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

private const val DEQUEUE_TIMEOUT_US = 10_000L

/** Reçoit le flux décodé, déjà ramené en entiers 16 bits entrelacés. */
internal interface PcmSink {
    fun onFormat(sampleRate: Int, channels: Int)
    fun onFrames(samples: ShortArray, count: Int)
    fun onFinished()
}

/**
 * Décode la piste audio d'un fichier vers du PCM 16 bits. `MediaCodec` utilise
 * les décodeurs matériels de l'appareil : la conversion reste locale et ne
 * dépend d'aucune bibliothèque tierce.
 */
internal object PcmDecoder {

    fun decode(
        source: File,
        sink: PcmSink,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val extractor = MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        var codec: MediaCodec? = null
        try {
            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            extractor.setDataSource(descriptor.fileDescriptor)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw ConversionException("Ce fichier ne contient aucune piste audio")

            val inputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw ConversionException("Piste audio illisible")
            val durationUs =
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    inputFormat.getLong(MediaFormat.KEY_DURATION)
                } else 0L

            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: Exception) {
                throw ConversionException("L'appareil ne sait pas décoder ce format audio")
            }
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var sampleRate = inputFormat.integerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.integerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var announced = false

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (isCancelled()) throw ConversionCancelled()

                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.integerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.integerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = format.integerOr(
                            MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT
                        )
                        sink.onFormat(sampleRate, channels)
                        announced = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outIndex >= 0) {
                        if (!announced) {
                            sink.onFormat(sampleRate, channels)
                            announced = true
                        }
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val samples = toShorts(buffer, pcmEncoding)
                                sink.onFrames(samples, samples.size)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            sink.onFinished()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { descriptor?.close() }
        }
    }

    /** Certains décodeurs rendent des flottants ; on ramène tout en 16 bits signés. */
    private fun toShorts(buffer: ByteBuffer, pcmEncoding: Int): ShortArray {
        buffer.order(ByteOrder.nativeOrder())
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            ShortArray(floats.remaining()) { index ->
                (floats.get(index).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        } else {
            val shorts = buffer.asShortBuffer()
            ShortArray(shorts.remaining()).also { shorts.get(it) }
        }
    }
}

/**
 * Ramène le flux décodé au format demandé : mixage vers la stéréo quand la
 * source a plus de deux canaux, puis rééchantillonnage par interpolation
 * linéaire si l'utilisateur a choisi une autre fréquence.
 */
internal class PcmPipeline(
    private val requestedSampleRate: Int,
    private val downstream: PcmSink
) : PcmSink {

    private var sourceChannels = 2
    private var outputChannels = 2
    private var sourceRate = 44_100
    private var outputRate = 44_100
    private var resampling = false

    private var carry: ShortArray? = null
    private var phase = 0.0

    override fun onFormat(sampleRate: Int, channels: Int) {
        sourceRate = sampleRate
        sourceChannels = channels.coerceAtLeast(1)
        outputChannels = min(sourceChannels, 2)
        outputRate = if (requestedSampleRate > 0) requestedSampleRate else sampleRate
        resampling = outputRate != sourceRate
        downstream.onFormat(outputRate, outputChannels)
    }

    override fun onFrames(samples: ShortArray, count: Int) {
        if (count == 0) return
        val mixed = downmix(samples, count)
        val frameCount = mixed.size / outputChannels
        if (frameCount == 0) return
        if (!resampling) {
            downstream.onFrames(mixed, mixed.size)
            return
        }
        val resampled = resample(mixed, frameCount)
        if (resampled.isNotEmpty()) downstream.onFrames(resampled, resampled.size)
    }

    override fun onFinished() = downstream.onFinished()

    private fun downmix(samples: ShortArray, count: Int): ShortArray {
        if (sourceChannels <= 2) {
            return if (count == samples.size) samples else samples.copyOf(count)
        }
        val frames = count / sourceChannels
        val out = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            var left = 0
            var right = 0
            var leftCount = 0
            var rightCount = 0
            for (channel in 0 until sourceChannels) {
                val value = samples[frame * sourceChannels + channel].toInt()
                if (channel % 2 == 0) {
                    left += value
                    leftCount++
                } else {
                    right += value
                    rightCount++
                }
            }
            out[frame * 2] = (left / leftCount.coerceAtLeast(1)).toShort()
            out[frame * 2 + 1] = (right / rightCount.coerceAtLeast(1)).toShort()
        }
        return out
    }

    private fun resample(frames: ShortArray, frameCount: Int): ShortArray {
        val channels = outputChannels
        val ratio = sourceRate.toDouble() / outputRate
        val previous = carry
        val total = frameCount + if (previous != null) 1 else 0
        val work = ShortArray(total * channels)
        var offset = 0
        if (previous != null) {
            previous.copyInto(work, 0)
            offset = channels
        }
        frames.copyInto(work, offset, 0, frameCount * channels)

        val span = total - 1 - phase
        val outFrames = if (span <= 0) 0 else ceil(span / ratio).toInt()
        val out = ShortArray(outFrames * channels)
        var position = phase
        var written = 0
        while (written < outFrames) {
            val index = position.toInt()
            if (index + 1 >= total) break
            val fraction = (position - index).toFloat()
            for (channel in 0 until channels) {
                val a = work[index * channels + channel].toInt()
                val b = work[(index + 1) * channels + channel].toInt()
                out[written * channels + channel] = (a + (b - a) * fraction).toInt().toShort()
            }
            written++
            position += ratio
        }
        carry = work.copyOfRange((total - 1) * channels, total * channels)
        phase = position - (total - 1)
        return if (written == outFrames) out else out.copyOf(written * channels)
    }
}

internal fun MediaFormat.integerOr(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback
