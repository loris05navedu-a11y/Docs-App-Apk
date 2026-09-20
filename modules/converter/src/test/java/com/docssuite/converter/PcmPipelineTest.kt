package com.docssuite.converter

import com.docssuite.converter.engine.PcmPipeline
import com.docssuite.converter.engine.PcmSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Collecte tout ce que la chaîne audio produit, pour pouvoir l'inspecter. */
private class RecordingSink : PcmSink {
    var sampleRate = 0
    var channels = 0
    val samples = mutableListOf<Short>()
    var finished = false

    override fun onFormat(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
    }

    override fun onFrames(samples: ShortArray, count: Int) {
        for (index in 0 until count) this.samples += samples[index]
    }

    override fun onFinished() {
        finished = true
    }

    val frameCount: Int get() = if (channels == 0) 0 else samples.size / channels
}

class PcmPipelineTest {

    @Test
    fun `sans changement de frequence le flux passe tel quel`() {
        val sink = RecordingSink()
        val pipeline = PcmPipeline(requestedSampleRate = 0, downstream = sink)
        pipeline.onFormat(44_100, 2)
        val input = ShortArray(8) { (it * 100).toShort() }
        pipeline.onFrames(input, input.size)
        pipeline.onFinished()

        assertEquals(44_100, sink.sampleRate)
        assertEquals(2, sink.channels)
        assertEquals(input.toList(), sink.samples)
        assertTrue(sink.finished)
    }

    @Test
    fun `diviser la frequence par deux produit deux fois moins de trames`() {
        val sink = RecordingSink()
        val pipeline = PcmPipeline(requestedSampleRate = 22_050, downstream = sink)
        pipeline.onFormat(44_100, 1)

        val frames = 1_000
        pipeline.onFrames(ShortArray(frames) { (it % 1000).toShort() }, frames)
        pipeline.onFinished()

        assertEquals(22_050, sink.sampleRate)
        assertEquals(1, sink.channels)
        // Une trame de garde est conservée entre deux blocs pour l'interpolation.
        assertTrue(
            "Obtenu ${sink.frameCount} trames",
            abs(sink.frameCount - frames / 2) <= 2
        )
    }

    @Test
    fun `le rééchantillonnage reste continu d un bloc a l autre`() {
        val sink = RecordingSink()
        val pipeline = PcmPipeline(requestedSampleRate = 22_050, downstream = sink)
        pipeline.onFormat(44_100, 1)

        // Une rampe croissante : si les blocs étaient traités isolément, la
        // sortie présenterait un décrochage à chaque frontière.
        val total = 400
        val block = 100
        var value = 0
        repeat(total / block) {
            val chunk = ShortArray(block) { (value + it).toShort() }
            value += block
            pipeline.onFrames(chunk, block)
        }
        pipeline.onFinished()

        val produced = sink.samples
        assertTrue(produced.size > 150)
        produced.zipWithNext().forEach { (a, b) ->
            assertTrue("Décrochage entre $a et $b", b >= a)
        }
    }

    @Test
    fun `plus de deux canaux sont ramenes en stereo`() {
        val sink = RecordingSink()
        val pipeline = PcmPipeline(requestedSampleRate = 0, downstream = sink)
        pipeline.onFormat(48_000, 6)

        // Deux trames de 6 canaux : les canaux pairs valent 100, les impairs 200.
        val input = ShortArray(12) { if (it % 2 == 0) 100 else 200 }
        pipeline.onFrames(input, input.size)

        assertEquals(2, sink.channels)
        assertEquals(listOf<Short>(100, 200, 100, 200), sink.samples)
    }
}
