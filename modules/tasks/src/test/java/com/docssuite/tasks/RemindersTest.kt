package com.docssuite.tasks

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemindersTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before
    fun clean() {
        File(app.filesDir, "tasks.json").delete()
    }

    private val inOneHour get() = System.currentTimeMillis() + 3_600_000

    @Test
    fun `les taches et listes se retrouvent apres reouverture`() {
        val store = TaskStore(app)
        val courses = store.createList("Courses")
        store.add(courses.id, "Pain")
        store.add(TaskStore.DEFAULT_LIST, "Appeler Léa", remindAt = 1_000)
        val reopened = TaskStore(app)
        assertEquals(listOf("Mes tâches", "Courses"), reopened.lists().map { it.name })
        assertEquals(listOf("Pain"), reopened.inList(courses.id).map { it.title })
        assertEquals(1_000L, reopened.inList(TaskStore.DEFAULT_LIST).single().remindAt)
    }

    @Test
    fun `cocher une tache repetee la fait passer a la prochaine fois`() {
        val store = TaskStore(app)
        val now = System.currentTimeMillis()
        val task = store.add(TaskStore.DEFAULT_LIST, "Médicament", remindAt = now - 3_600_000, repeat = Repeat.DAILY)
        val next = store.toggle(task.id, now)!!
        assertFalse(next.done)
        assertTrue(next.remindAt!! > now)
        assertTrue(next.remindAt!! - (now - 3_600_000) <= 24 * 3_600_000L)
        val simple = store.add(TaskStore.DEFAULT_LIST, "Pain")
        assertTrue(store.toggle(simple.id)!!.done)
    }

    @Test
    fun `un rappel a venir est programme et annule une fois la tache faite`() {
        val store = TaskStore(app)
        val task = store.add(TaskStore.DEFAULT_LIST, "Dentiste", remindAt = inOneHour)
        Reminders.sync(app, task)
        assertEquals(task.remindAt, alarms.nextScheduledAlarm!!.triggerAtTime)

        Reminders.sync(app, store.toggle(task.id)!!)
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test
    fun `le rappel affiche une notification avec fait et plus tard`() {
        val store = TaskStore(app)
        val task = store.add(TaskStore.DEFAULT_LIST, "Rendre le dossier", remindAt = inOneHour)
        ReminderReceiver().onReceive(app, Intent(Reminders.ACTION_FIRE).putExtra(Reminders.EXTRA_TASK, task.id))
        val notification = notifications.allNotifications.single()
        assertEquals("Rendre le dossier", shadowOf(notification).contentTitle)
        assertEquals(listOf("Fait", "Dans 1 h"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun `fait depuis la notification coche la tache`() {
        val store = TaskStore(app)
        val task = store.add(TaskStore.DEFAULT_LIST, "Sortir les poubelles", remindAt = inOneHour)
        ReminderReceiver().onReceive(app, Intent(Reminders.ACTION_DONE).putExtra(Reminders.EXTRA_TASK, task.id))
        assertTrue(TaskStore(app).get(task.id)!!.done)
    }

    @Test
    fun `plus tard repousse le rappel d une heure`() {
        val store = TaskStore(app)
        val task = store.add(TaskStore.DEFAULT_LIST, "Appeler la banque", remindAt = System.currentTimeMillis())
        val before = System.currentTimeMillis()
        ReminderReceiver().onReceive(app, Intent(Reminders.ACTION_SNOOZE).putExtra(Reminders.EXTRA_TASK, task.id))
        val later = TaskStore(app).get(task.id)!!.remindAt!!
        assertTrue(later >= before + Reminders.SNOOZE_MS)
        assertEquals(later, alarms.nextScheduledAlarm!!.triggerAtTime)
    }

    @Test
    fun `apres un redemarrage les rappels reviennent et ceux manques s affichent`() {
        val store = TaskStore(app)
        val future = store.add(TaskStore.DEFAULT_LIST, "Futur", remindAt = inOneHour)
        store.add(TaskStore.DEFAULT_LIST, "Manqué pendant l'extinction", remindAt = System.currentTimeMillis() - 600_000)
        store.add(TaskStore.DEFAULT_LIST, "Déjà fait", remindAt = inOneHour).let { store.toggle(it.id) }

        RescheduleReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(future.remindAt, alarms.nextScheduledAlarm!!.triggerAtTime)
        assertEquals(listOf("Manqué pendant l'extinction"), notifications.allNotifications.map { shadowOf(it).contentTitle.toString() })
    }

    @Test
    fun `la derniere liste ne peut pas etre supprimee`() {
        val store = TaskStore(app)
        assertTrue(store.deleteList(TaskStore.DEFAULT_LIST).isEmpty())
        assertEquals(1, store.lists().size)
    }
}
