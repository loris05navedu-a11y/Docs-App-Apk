package com.docssuite.reader

/** Voix factice : note ce qu'on lui demande de dire et répond « fini » à la demande. */
class FakeSpeaker(override var ready: Boolean? = true, override var problem: String? = null) : Speaker {
    override var onReady: () -> Unit = {}
    override var onDone: (String) -> Unit = {}
    override var onError: (String) -> Unit = {}

    val queue = ArrayList<Pair<String, String>>()
    val spoken = ArrayList<String>()
    var currentRate = 1f
    var currentPitch = 1f
    var stops = 0
    var shutDown = false

    override fun speak(text: String, id: String, flush: Boolean) {
        if (flush) queue.clear()
        queue.add(id to text)
    }

    override fun stop() {
        stops++
        queue.clear()
    }

    override fun setRate(rate: Float) {
        currentRate = rate
    }

    override fun setPitch(pitch: Float) {
        currentPitch = pitch
    }

    override fun shutdown() {
        shutDown = true
    }

    /** La voix termine la phrase en tête de file. */
    fun finishOne(): String? {
        val (id, text) = queue.removeFirstOrNull() ?: return null
        spoken.add(text)
        onDone(id)
        return text
    }

    fun becomeReady(ok: Boolean, message: String? = null) {
        ready = ok
        problem = message
        onReady()
    }
}
