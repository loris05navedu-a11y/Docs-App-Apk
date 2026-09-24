package com.docssuite.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface RecorderStatus {
    data object Idle : RecorderStatus
    data class Active(
        val id: String,
        val paused: Boolean,
        val elapsedMs: Long,
        val level: Float,
        val markers: List<Long>
    ) : RecorderStatus
}

/** Ce que l'écran observe de l'enregistrement en cours, où qu'il tourne. */
object RecorderController {
    internal val mutableStatus = MutableStateFlow<RecorderStatus>(RecorderStatus.Idle)
    val status: StateFlow<RecorderStatus> = mutableStatus

    internal val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Messages : enregistrement terminé, ou erreur à afficher. */
    val events: SharedFlow<String> = mutableEvents

    fun start(context: Context) = ContextCompat.startForegroundService(context, intent(context, RecordingService.ACTION_START))
    fun pause(context: Context) = send(context, RecordingService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, RecordingService.ACTION_RESUME)
    fun mark(context: Context) = send(context, RecordingService.ACTION_MARK)
    fun stop(context: Context) = send(context, RecordingService.ACTION_STOP)

    private fun send(context: Context, action: String) {
        if (status.value is RecorderStatus.Active) context.startService(intent(context, action))
    }

    internal fun intent(context: Context, action: String) = Intent(context, RecordingService::class.java).setAction(action)
}

/**
 * Enregistre au micro dans un service de premier plan : l'écran peut
 * s'éteindre, on peut passer à une autre app, l'enregistrement continue
 * (depuis Android 9, une app simplement en arrière-plan perd le micro).
 * La notification permet de mettre en pause, poser un repère ou arrêter.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var clock = RecordingClock { SystemClock.elapsedRealtime() }
    private var recordingId: String? = null
    private val markers = ArrayList<Long>()
    private var ticker: Job? = null
    private var startedAt = 0L
    private lateinit var store: RecordingStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = RecordingStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_MARK -> mark()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        // Premier plan d'abord : Android exige qu'il soit déclaré avant d'ouvrir le micro.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(paused = false),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        val id = store.newId()
        val file = store.audioFile(id)
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000) // la voix reste nette, ≈ 45 Mo par heure
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
        } catch (error: Exception) {
            file.delete()
            RecorderController.mutableEvents.tryEmit("Le micro n'a pas pu démarrer : ${error.message ?: "il est peut-être utilisé par une autre app"}")
            finish()
            return
        }
        recordingId = id
        startedAt = System.currentTimeMillis()
        markers.clear()
        clock = RecordingClock { SystemClock.elapsedRealtime() }
        clock.start()
        publish()
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(150)
            }
        }
    }

    private fun publish() {
        val id = recordingId ?: return
        val amplitude = if (clock.isRunning) runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) else 0
        RecorderController.mutableStatus.value = RecorderStatus.Active(id, !clock.isRunning, clock.elapsed(), levelOf(amplitude), markers.toList())
    }

    private fun pause() {
        val r = recorder ?: return
        if (!clock.isRunning) return
        runCatching { r.pause() }.onSuccess {
            clock.pause()
            publish()
            updateNotification(paused = true)
        }
    }

    private fun resume() {
        val r = recorder ?: return
        if (clock.isRunning) return
        runCatching { r.resume() }.onSuccess {
            clock.start()
            publish()
            updateNotification(paused = false)
        }
    }

    private fun mark() {
        if (recorder == null) return
        markers.add(clock.elapsed())
        publish()
    }

    private fun stop() {
        val r = recorder ?: return finish()
        val id = recordingId ?: return finish()
        val duration = clock.elapsed()
        val stopped = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        val file = store.audioFile(id)
        // Arrêté aussitôt lancé, le fichier est vide et illisible : on ne le garde pas.
        if (stopped && file.exists() && file.length() > 0 && duration >= 500) {
            val name = "Enregistrement du " + SimpleDateFormat("d MMMM 'à' HH'h'mm", Locale.FRANCE).format(Date(startedAt))
            store.save(Recording(id, name, startedAt, duration, markers.toList(), file))
            RecorderController.mutableEvents.tryEmit("Enregistrement gardé (${formatDuration(duration)})")
        } else {
            file.delete()
            RecorderController.mutableEvents.tryEmit("Enregistrement trop court : il n'a pas été gardé")
        }
        finish()
    }

    private fun finish() {
        ticker?.cancel()
        ticker = null
        recordingId = null
        RecorderController.mutableStatus.value = RecorderStatus.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Service arrêté de force : on garde ce qui a été enregistré.
        if (recorder != null) stop()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------- notification

    private fun notification(paused: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Enregistrement", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Affichée pendant qu'un enregistrement est en cours"
                }
            )
        }
        fun action(label: String, action: String, code: Int) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(this, code, RecorderController.intent(this, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 10, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Enregistrement en pause" else "Enregistrement en cours")
            .setContentText("DocsApp Suite — Dictaphone")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - clock.elapsed())
            .setContentIntent(open)
            .addAction(if (paused) action("Reprendre", ACTION_RESUME, 1) else action("Pause", ACTION_PAUSE, 2))
            .addAction(action("Repère", ACTION_MARK, 3))
            .addAction(action("Arrêter", ACTION_STOP, 4))
            .build()
    }

    private fun updateNotification(paused: Boolean) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(paused)) }
    }

    companion object {
        const val ACTION_START = "com.docssuite.recorder.START"
        const val ACTION_PAUSE = "com.docssuite.recorder.PAUSE"
        const val ACTION_RESUME = "com.docssuite.recorder.RESUME"
        const val ACTION_MARK = "com.docssuite.recorder.MARK"
        const val ACTION_STOP = "com.docssuite.recorder.STOP"
        private const val CHANNEL = "recorder"
        private const val NOTIFICATION_ID = 4242
    }
}
