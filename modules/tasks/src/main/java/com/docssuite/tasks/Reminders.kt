package com.docssuite.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Les rappels passent par l'`AlarmManager` du système : ils sonnent même
 * app fermée. À la minute près si l'utilisateur l'autorise (Android 12+),
 * sinon à quelques minutes près, le système regroupant les réveils.
 */
object Reminders {

    private const val CHANNEL = "reminders"
    const val ACTION_FIRE = "com.docssuite.tasks.REMINDER"
    const val ACTION_DONE = "com.docssuite.tasks.DONE"
    const val ACTION_SNOOZE = "com.docssuite.tasks.SNOOZE"
    const val EXTRA_TASK = "task"
    const val SNOOZE_MS = 60 * 60 * 1000L

    private fun alarmIntent(context: Context, taskId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_TASK, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Programme (ou annule) le rappel de [task] selon son état. */
    fun sync(context: Context, task: Task, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, task.id)
        val at = task.remindAt
        if (task.done || at == null || at <= now) {
            alarms.cancel(pending)
            return
        }
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    fun cancel(context: Context, taskId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, taskId))
        NotificationManagerCompat.from(context).cancel(taskId.hashCode())
    }

    /**
     * Après un redémarrage ou un changement d'heure, toutes les alarmes ont
     * disparu : on reprogramme les rappels à venir, et on affiche tout de
     * suite ceux manqués pendant que le téléphone était éteint.
     */
    fun rescheduleAll(context: Context, now: Long = System.currentTimeMillis()) {
        val store = TaskStore(context)
        store.all().filter { !it.done && it.remindAt != null }.forEach { task ->
            if (task.remindAt!! > now) sync(context, task, now)
            else if (now - task.remindAt < 24 * 60 * 60 * 1000L) notify(context, task)
        }
    }

    fun notify(context: Context, task: Task) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Rappels de tâches", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Les rappels posés sur tes tâches"
                }
            )
        }
        fun action(label: String, action: String) = NotificationCompat.Action(
            0, label,
            PendingIntent.getBroadcast(
                context,
                (task.id + action).hashCode(),
                Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_TASK, task.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 20, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(task.title)
            .setContentText(task.notes.ifBlank { "Rappel — DocsApp Suite" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(action("Fait", ACTION_DONE))
            .addAction(action("Dans 1 h", ACTION_SNOOZE))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification) }
    }
}

/** Le rappel sonne, ou l'utilisateur répond depuis la notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_TASK) ?: return
        val store = TaskStore(context)
        val task = store.get(id) ?: return
        when (intent.action) {
            Reminders.ACTION_FIRE -> if (!task.done) Reminders.notify(context, task)
            Reminders.ACTION_DONE -> {
                NotificationManagerCompat.from(context).cancel(id.hashCode())
                // Une tâche répétée passe à sa prochaine fois, une autre est terminée.
                store.toggle(id)?.let { Reminders.sync(context, it) }
            }
            Reminders.ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(id.hashCode())
                val later = task.copy(remindAt = System.currentTimeMillis() + Reminders.SNOOZE_MS)
                store.update(later)
                Reminders.sync(context, later)
            }
        }
    }
}

/** Redémarrage, mise à jour de l'app, changement d'heure : les alarmes sont à refaire. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll(context)
    }
}
