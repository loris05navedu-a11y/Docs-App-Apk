package com.docssuite.reader

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** Où chaque texte a été laissé, pour reprendre à la bonne phrase. */
class ReaderPositions(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("docssuite_reader", Context.MODE_PRIVATE)

    /** (phrase, nombre de phrases) ou `null` si jamais commencé. */
    fun get(key: String): Pair<Int, Int>? {
        val raw = prefs.getString("pos:$key", null) ?: return null
        val index = raw.substringBefore('/').toIntOrNull() ?: return null
        val count = raw.substringAfter('/').toIntOrNull() ?: return null
        return index to count
    }

    fun set(key: String, index: Int, count: Int) {
        if (index <= 0 || index >= count) prefs.edit().remove("pos:$key").apply()
        else prefs.edit().putString("pos:$key", "$index/$count").apply()
    }

    var rate: Float
        get() = prefs.getFloat("rate", 1f)
        set(value) = prefs.edit().putFloat("rate", value).apply()

    var pitch: Float
        get() = prefs.getFloat("pitch", 1f)
        set(value) = prefs.edit().putFloat("pitch", value).apply()
}

/**
 * La lecture en cours. On confie à la voix la phrase courante et la
 * suivante, pour qu'il n'y ait pas de blanc entre les deux ; chaque « fini »
 * fait avancer d'une phrase. La synthèse ne sait pas se mettre en pause :
 * « Pause » arrête, « Lire » reprend au début de la phrase interrompue.
 */
class ReaderState(private val speaker: Speaker, private val positions: ReaderPositions) {

    var title by mutableStateOf("")
        private set
    var text by mutableStateOf("")
        private set
    var sentences by mutableStateOf<List<IntRange>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var rate by mutableStateOf(positions.rate)
        private set
    var pitch by mutableStateOf(positions.pitch)
        private set
    var voiceProblem by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set

    private var key by mutableStateOf<String?>(null)
    /** Change à chaque arrêt : les « fini » d'une lecture interrompue sont ignorés. */
    private var generation = 0
    private var queuedUpTo = -1
    private var startWhenReady = false

    val loaded: Boolean get() = key != null

    init {
        speaker.setRate(rate)
        speaker.setPitch(pitch)
        speaker.onReady = {
            voiceProblem = speaker.problem
            if (speaker.ready == true) {
                speaker.setRate(rate)
                speaker.setPitch(pitch)
                if (startWhenReady) play()
            } else {
                playing = false
            }
            startWhenReady = false
        }
        speaker.onDone = ::spoken
        speaker.onError = { id ->
            // Une phrase que la voix n'arrive pas à dire ne doit pas bloquer la lecture.
            spoken(id)
        }
        if (speaker.ready == false) voiceProblem = speaker.problem
    }

    /** Ouvre un texte ; [key] identifie sa position enregistrée. */
    fun load(key: String, title: String, text: String) {
        stop()
        this.key = key
        this.title = title
        this.text = text
        sentences = Sentences.split(text)
        finished = false
        val saved = positions.get(key)
        index = if (saved != null && saved.second == sentences.size) saved.first.coerceIn(0, maxOf(0, sentences.size - 1)) else 0
    }

    fun unload() {
        stop()
        key = null
        text = ""
        title = ""
        sentences = emptyList()
        index = 0
    }

    fun play() {
        if (sentences.isEmpty()) return
        if (finished || index >= sentences.size) {
            index = 0
            finished = false
        }
        when (speaker.ready) {
            null -> {
                startWhenReady = true
                playing = true
                return
            }
            false -> {
                voiceProblem = speaker.problem
                return
            }
            true -> Unit
        }
        generation++
        playing = true
        queuedUpTo = index - 1
        enqueue(flush = true)
    }

    fun pause() {
        stop()
        save()
    }

    fun toggle() = if (playing) pause() else play()

    fun jumpTo(target: Int) {
        if (sentences.isEmpty()) return
        val wasPlaying = playing
        stop()
        index = target.coerceIn(0, sentences.size - 1)
        finished = false
        save()
        if (wasPlaying) play()
    }

    fun next() = jumpTo(index + 1)

    fun previous() = jumpTo(index - 1)

    fun changeRate(value: Float) {
        rate = (value * 20).roundToInt() / 20f
        positions.rate = rate
        speaker.setRate(rate)
        restartIfPlaying()
    }

    fun changePitch(value: Float) {
        pitch = (value * 20).roundToInt() / 20f
        positions.pitch = pitch
        speaker.setPitch(pitch)
        restartIfPlaying()
    }

    private fun restartIfPlaying() {
        if (playing) {
            stop()
            play()
        }
    }

    fun sentenceText(i: Int): String = sentences.getOrNull(i)?.let { text.substring(it.first, it.last + 1) } ?: ""

    /** Minutes restantes à ~15 caractères par seconde à vitesse normale. */
    fun remainingMinutes(): Int {
        val chars = sentences.drop(index).sumOf { it.last - it.first + 1 }
        return maxOf(if (chars > 0) 1 else 0, (chars / (15f * rate) / 60f).roundToInt())
    }

    private fun enqueue(flush: Boolean) {
        var first = flush
        // La phrase en cours et la suivante.
        while (queuedUpTo < minOf(index + 1, sentences.size - 1)) {
            queuedUpTo++
            speaker.speak(sentenceText(queuedUpTo), "$generation:$queuedUpTo", first)
            first = false
        }
    }

    private fun spoken(id: String) {
        val gen = id.substringBefore(':').toIntOrNull()
        val sentence = id.substringAfter(':').toIntOrNull()
        if (!playing || gen != generation || sentence != index) return
        index++
        if (index >= sentences.size) {
            playing = false
            finished = true
            index = sentences.size - 1
            key?.let { positions.set(it, 0, sentences.size) }
            return
        }
        save()
        enqueue(flush = false)
    }

    private fun stop() {
        if (playing) {
            generation++
            speaker.stop()
        }
        playing = false
        startWhenReady = false
    }

    private fun save() {
        key?.let { positions.set(it, index, sentences.size) }
    }

    fun release() {
        pause()
        speaker.shutdown()
    }
}
