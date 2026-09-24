package com.docssuite.recorder

import android.app.Service
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

/**
 * Le service tel qu'Android le pilote : commandes reçues comme depuis
 * l'écran ou la notification. Le micro est simulé par Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun ServiceController<RecordingService>.send(action: String): ServiceController<RecordingService> {
        withIntent(RecorderController.intent(app, action)).startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        return this
    }

    private val active get() = RecorderController.status.value as RecorderStatus.Active

    @Test
    fun `demarrer mettre en pause poser des reperes puis arreter`() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        controller.send(RecordingService.ACTION_START)

        // Au premier plan, avec une notification portant les trois commandes.
        val service = controller.get()
        val notification = shadowOf(service).lastForegroundNotification
        assertTrue("service au premier plan", notification != null)
        assertEquals(listOf("Pause", "Repère", "Arrêter"), notification.actions.map { it.title.toString() })
        assertTrue(!active.paused)

        controller.send(RecordingService.ACTION_MARK)
        controller.send(RecordingService.ACTION_MARK)
        assertEquals(2, active.markers.size)

        controller.send(RecordingService.ACTION_PAUSE)
        assertTrue("en pause", active.paused)
        controller.send(RecordingService.ACTION_RESUME)
        assertTrue("reprise", !active.paused)

        controller.send(RecordingService.ACTION_STOP)
        assertEquals(RecorderStatus.Idle, RecorderController.status.value)
        assertTrue("le service s'arrête", shadowOf(service).isStoppedBySelf)
        // Le micro simulé n'écrit rien : rien d'illisible n'est gardé.
        assertTrue(RecordingStore(app).list().isEmpty())
    }

    @Test
    fun `une commande sans enregistrement en cours ne fait rien`() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        controller.send(RecordingService.ACTION_PAUSE)
        controller.send(RecordingService.ACTION_MARK)
        assertEquals(RecorderStatus.Idle, RecorderController.status.value)
    }

    @Test
    fun `un service detruit en plein enregistrement se termine proprement`() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        controller.send(RecordingService.ACTION_START)
        assertTrue(RecorderController.status.value is RecorderStatus.Active)
        controller.destroy()
        assertEquals(RecorderStatus.Idle, RecorderController.status.value)
    }
}
