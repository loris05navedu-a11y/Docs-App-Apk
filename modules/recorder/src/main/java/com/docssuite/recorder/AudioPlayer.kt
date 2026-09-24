package com.docssuite.recorder

import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Lecture d'un enregistrement à la fois, avec vitesse et saut. */
class AudioPlayer {
    private var player: MediaPlayer? = null

    var currentId by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var speed by mutableStateOf(1f)
        private set

    fun open(id: String, file: File) {
        if (currentId == id) return
        release()
        val p = MediaPlayer()
        p.setDataSource(file.absolutePath)
        p.prepare()
        p.setOnCompletionListener {
            playing = false
            positionMs = durationMs
        }
        player = p
        currentId = id
        durationMs = p.duration.toLong().coerceAtLeast(0)
        positionMs = 0
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            if (positionMs >= durationMs - 50) p.seekTo(0)
            p.start()
            applySpeed(p)
            playing = true
        }
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val target = ms.coerceIn(0, durationMs)
        p.seekTo(target.toInt())
        positionMs = target
    }

    fun skip(deltaMs: Long) = seekTo(positionMs + deltaMs)

    /** Change la vitesse ; appliquée tout de suite en lecture, sinon au prochain départ. */
    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player?.takeIf { it.isPlaying }?.let(::applySpeed)
    }

    private fun applySpeed(p: MediaPlayer) {
        // Changer la vitesse d'un lecteur à l'arrêt le relancerait : seulement en lecture.
        runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    /** À appeler régulièrement pendant la lecture pour suivre la position. */
    fun poll() {
        val p = player ?: return
        runCatching { positionMs = p.currentPosition.toLong() }
        playing = runCatching { p.isPlaying }.getOrDefault(false)
    }

    fun release() {
        player?.release()
        player = null
        currentId = null
        playing = false
        positionMs = 0
        durationMs = 0
    }
}
