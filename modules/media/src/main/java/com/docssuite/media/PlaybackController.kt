package com.docssuite.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Enveloppe de `MediaPlayer` exposée en état Compose.
 *
 * On s'en tient au lecteur de la plateforme : il décode tout ce que l'appareil
 * sait décoder (MP4, MKV, WebM, MP3, AAC, FLAC, OGG, WAV…) sans ajouter de
 * dépendance, et sa surface se branche sur une `TextureView`.
 */
class PlaybackController(private val context: Context) {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingStart = false

    var currentUri by mutableStateOf<Uri?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isPrepared by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set
    var videoWidth by mutableStateOf(0)
        private set
    var videoHeight by mutableStateOf(0)
        private set
    var speed by mutableStateOf(1f)
        private set
    var repeatOne by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Appelé quand la piste se termine sans répétition, pour enchaîner. */
    var onCompleted: (() -> Unit)? = null

    fun load(uri: Uri, autoPlay: Boolean = true) {
        release()
        error = null
        isPrepared = false
        positionMs = 0
        durationMs = 0
        videoWidth = 0
        videoHeight = 0
        currentUri = uri
        pendingStart = autoPlay

        // Surtout pas de `apply` ici : `MediaPlayer` a lui aussi des propriétés
        // `isPlaying`, `videoWidth` et `videoHeight`, qui masqueraient
        // silencieusement celles du contrôleur.
        val created = MediaPlayer()
        player = created

        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        created.setOnPreparedListener { ready ->
            isPrepared = true
            durationMs = ready.duration.coerceAtLeast(0)
            surface?.let { ready.setSurface(it) }
            applySpeed(ready)
            if (pendingStart) {
                runCatching { ready.start() }.onSuccess { isPlaying = true }
            }
        }
        created.setOnVideoSizeChangedListener { _, width, height ->
            videoWidth = width
            videoHeight = height
        }
        created.setOnCompletionListener {
            if (repeatOne) {
                seekTo(0)
                play()
            } else {
                isPlaying = false
                positionMs = durationMs
                onCompleted?.invoke()
            }
        }
        created.setOnErrorListener { _, what, _ ->
            error = when (what) {
                MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "Format non pris en charge par l'appareil"
                MediaPlayer.MEDIA_ERROR_IO -> "Fichier illisible"
                MediaPlayer.MEDIA_ERROR_MALFORMED -> "Fichier abîmé"
                else -> "Lecture impossible"
            }
            isPlaying = false
            isPrepared = false
            true
        }

        try {
            created.setDataSource(context, uri)
            created.prepareAsync()
        } catch (failure: Exception) {
            error = failure.message ?: "Lecture impossible"
            release()
        }
    }

    fun play() {
        val active = player
        if (active == null || !isPrepared) {
            // La préparation est asynchrone : on note l'intention et le
            // lecteur démarrera de lui-même une fois prêt.
            pendingStart = true
            return
        }
        runCatching { active.start() }.onSuccess { isPlaying = true }
    }

    fun pause() {
        val active = player ?: return
        if (!isPrepared) {
            pendingStart = false
            return
        }
        runCatching { active.pause() }.onSuccess { isPlaying = false }
    }

    fun toggle() = if (isPlaying) pause() else play()

    fun seekTo(milliseconds: Int) {
        val active = player ?: return
        if (!isPrepared) return
        val target = milliseconds.coerceIn(0, durationMs)
        runCatching { active.seekTo(target) }.onSuccess { positionMs = target }
    }

    fun skip(milliseconds: Int) = seekTo(positionMs + milliseconds)

    fun changeSpeed(value: Float) {
        speed = value.coerceIn(0.25f, 3f)
        player?.let { applySpeed(it) }
    }

    /**
     * Changer la vitesse relance la lecture sur certains appareils : on rétablit
     * donc l'état de pause juste après.
     */
    private fun applySpeed(player: MediaPlayer) {
        runCatching {
            val wasPlaying = isPlaying
            player.playbackParams = player.playbackParams.setSpeed(speed)
            if (!wasPlaying && player.isPlaying) player.pause()
        }
    }

    fun refreshPosition() {
        val active = player ?: return
        if (!isPrepared) return
        runCatching { positionMs = active.currentPosition.coerceIn(0, durationMs) }
    }

    fun attachSurface(value: Surface) {
        surface = value
        if (isPrepared) runCatching { player?.setSurface(value) }
    }

    fun detachSurface() {
        surface = null
        runCatching { player?.setSurface(null) }
    }

    fun release() {
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        isPlaying = false
        isPrepared = false
    }
}
