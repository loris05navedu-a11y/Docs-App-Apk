package com.docssuite.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Ce qui prononce les phrases. L'écran ne parle qu'à cette interface : les
 * tests la remplacent par une voix factice qui répond « fini » à la demande.
 * Tous les rappels arrivent sur le fil principal.
 */
interface Speaker {
    /** `null` tant que la voix se prépare ; ensuite `true`, ou `false` avec [problem]. */
    val ready: Boolean?
    val problem: String?

    var onReady: () -> Unit
    var onDone: (id: String) -> Unit
    var onError: (id: String) -> Unit

    /** [flush] : interrompt ce qui se dit ; sinon la phrase passe à la suite. */
    fun speak(text: String, id: String, flush: Boolean)
    fun stop()
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun shutdown()
}

/** La synthèse vocale d'Android, en français, hors ligne quand la voix est installée. */
class TtsSpeaker(context: Context) : Speaker {

    private val main = Handler(Looper.getMainLooper())
    override var ready: Boolean? = null
        private set
    override var problem: String? = null
        private set
    override var onReady: () -> Unit = {}
    override var onDone: (String) -> Unit = {}
    override var onError: (String) -> Unit = {}

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> main.post { initialized(status) } }

    private fun initialized(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            problem = "Aucun moteur de synthèse vocale n'est disponible sur ce téléphone."
        } else {
            val result = tts.setLanguage(Locale.FRANCE).let {
                if (it < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.FRENCH) else it
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                ready = false
                problem = "La voix française n'est pas installée."
            } else {
                ready = true
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    main.post { this@TtsSpeaker.onDone(utteranceId) }
                }

                @Deprecated("Remplacé par la version avec code d'erreur")
                override fun onError(utteranceId: String) {
                    main.post { this@TtsSpeaker.onError(utteranceId) }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    main.post { this@TtsSpeaker.onError(utteranceId) }
                }
            })
        }
        onReady()
    }

    override fun speak(text: String, id: String, flush: Boolean) {
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    override fun stop() {
        tts.stop()
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        tts.setPitch(pitch)
    }

    override fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
